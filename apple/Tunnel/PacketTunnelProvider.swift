import Foundation
import NetworkExtension
import Libbox

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var box: LibboxBoxService?
    private var platform: YayPlatform?
    private var renewal: Task<Void, Never>?
    private var watchdog: Task<Void, Never>?
    private let gate = NSLock()
    private var deadline = ContinuousClock.now
    private var expires: TimeInterval = 0
    private var startTask: Task<Void, Never>?
    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        guard let server = options?["serverID"] as? String else { completionHandler(YayFailure(status: 0, message: "Open Yay VPN and select a country.")); return }
        startTask = Task {
            do {
                let api = try YayAPI(); let requested = ContinuousClock.now
                let grant = try await api.call("POST", "/v1/connect", ["server_id": server])
                try Task.checkCancellation()
                guard var config = grant["config"] as? [String: Any], let lease = grant["lease_seconds"] as? Double, let expiry = grant["expires_at"] as? Double, let revision = grant["revision"] as? Int else { throw YayFailure(status: 0, message: "Invalid access grant") }
                gate.withLock { deadline = requested.advanced(by: .seconds(lease)); expires = expiry }
                // Apple app-extension routing is installed through NetworkExtension, not a shell process.
                if var inbounds = config["inbounds"] as? [[String: Any]], !inbounds.isEmpty { inbounds[0]["stack"] = "system"; config["inbounds"] = inbounds }
                let bytes = try JSONSerialization.data(withJSONObject: config)
                let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let setup = LibboxSetupOptions(); setup.basePath = directory.path; setup.workingPath = directory.path; setup.tempPath = NSTemporaryDirectory()
                var error: NSError?; LibboxSetup(setup, &error); if let error { throw error }
                platform = YayPlatform(self)
                box = LibboxNewService(String(decoding: bytes, as: UTF8.self), platform, &error)
                if let error { throw error }; guard let box else { throw YayFailure(status: 0, message: "Native engine unavailable") }
                try box.start(); try box.yayCheckConnection(); try Task.checkCancellation()
                guard gate.withLock({ ContinuousClock.now < deadline && Date().timeIntervalSince1970 < expires }) else { throw YayFailure(status: 403, message: "Access expired") }
                watchdog = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        guard let self, !Task.isCancelled else { return }
                        if self.gate.withLock({ ContinuousClock.now >= self.deadline || Date().timeIntervalSince1970 >= self.expires }) { self.cancelTunnelWithError(YayFailure(status: 403, message: "Access check expired")); return }
                    }
                }
                renewal = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 45_000_000_000)
                        guard let self, !Task.isCancelled else { return }
                        let requested = ContinuousClock.now
                        do {
                            let result = try await api.call("POST", "/v1/heartbeat", ["server_id": server, "revision": revision])
                            guard let seconds = result["lease_seconds"] as? Double, let expiry = result["expires_at"] as? Double else { continue }
                            self.gate.withLock { self.deadline = requested.advanced(by: .seconds(seconds)); self.expires = expiry }
                        } catch let error as YayFailure { self.cancelTunnelWithError(error); return } catch { /* Existing watchdog lease remains authoritative. */ }
                    }
                }
                completionHandler(nil)
            } catch { try? box?.close(); box = nil; platform?.shutdown(); platform = nil; completionHandler(error) }
        }
    }
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)? = nil) {
        guard messageData == Data("health".utf8) else { completionHandler?(nil); return }
        Task {
            do {
                guard let box else { throw YayFailure(status: 0, message: "Tunnel stopped") }
                try box.yayCheckConnection()
                completionHandler?(Data("ok".utf8))
            } catch { completionHandler?(Data("failed".utf8)) }
        }
    }
    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        startTask?.cancel(); renewal?.cancel(); watchdog?.cancel()
        Task { [weak self] in
            await self?.startTask?.value
            try? self?.box?.close(); self?.box = nil; self?.platform?.shutdown(); self?.platform = nil
            completionHandler()
        }
    }
}
