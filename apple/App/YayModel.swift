import Foundation
import SwiftUI
import Network
import NetworkExtension

@MainActor final class YayModel: ObservableObject {
    @Published var signedIn = false
    @Published var connected = false
    @Published var busy = false
    @Published var status = ""
    @Published var country = "Singapore"
    @Published var countries: [String] = []
    @Published var pings: [String: Int] = [:]
    private var nodes: [[String: Any]] = []
    private var measurements: [String: Int] = [:]
    private var measuredAt = Date.distantPast
    private var api: YayAPI?
    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?
    private var currentTask: Task<Void, Never>?
    init() {
        do { api = try YayAPI(); signedIn = api?.signedIn == true } catch { status = error.localizedDescription }
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.manager?.connection.status != .connected { self.connected = false }
            }
        }
    }
    func login(_ username: String, _ password: String) async {
        busy = true; defer { busy = false }
        do { guard let api else { throw YayFailure(status: 0, message: "Apple signing configuration is incomplete") }; try await api.login(username, password); signedIn = true; await refresh() }
        catch { status = error.localizedDescription }
    }
    func refresh() async {
        guard let api else { return }
        do {
            let b = try await api.call("GET", "/v1/bootstrap")
            nodes = b["servers"] as? [[String: Any]] ?? []
            countries = Array(Set(nodes.compactMap { $0["location"] as? String })).sorted()
            if !countries.contains(country) { country = countries.first ?? "" }
            measurements.removeAll(); pings.removeAll()
            let saved = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = saved.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == Self.extensionID }
            // Reopening an active session does not mark it verified until the user reconnects/checks it.
            if manager?.connection.status == .connected { connected = await verifyInternet() }
        } catch {
            if let failure = error as? YayFailure, failure.status == 401 || failure.status == 403 { try? YayAPI.save("token", nil); signedIn = false }
            status = error.localizedDescription
        }
    }
    static var extensionID: String { Bundle.main.bundleIdentifier! + ".tunnel" }
    func measureAll() {
        guard !busy, manager?.connection.status != .connected else { return }
        currentTask = Task { busy = true; defer { busy = false }; do { try await measure(nodes) } catch { status = error.localizedDescription } }
    }
    private func measure(_ selected: [[String: Any]]) async throws {
        guard let api else { return }
        for (index, node) in selected.enumerated() {
            try Task.checkCancellation()
            guard let id = node["id"] as? String else { continue }
            let grant = try await api.call("POST", "/v1/connect", ["server_id": id])
            guard let config = grant["config"] as? [String: Any], let outbound = (config["outbounds"] as? [[String: Any]])?.first, let host = outbound["server"] as? String, let port = outbound["server_port"] as? Int else { continue }
            let ms = await Self.tcp(host, port); measurements[id] = ms
            let location = (node["location"] as? String) ?? ""
            let successful = nodes.filter { $0["location"] as? String == location }.compactMap { measurements[($0["id"] as? String) ?? ""] }.filter { $0 >= 0 }
            pings[location] = successful.min() ?? -1; status = "\(index + 1) / \(selected.count)"
        }
        measuredAt = Date()
    }
    nonisolated static func tcp(_ host: String, _ port: Int) async -> Int {
        guard let endpointPort = NWEndpoint.Port(rawValue: UInt16(exactly: port) ?? 0) else { return -1 }
        return await withCheckedContinuation { continuation in
            let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
            let queue = DispatchQueue(label: "yay.ping"), start = ContinuousClock.now
            var finished = false
            func finish(_ value: Int) { guard !finished else { return }; finished = true; connection.stateUpdateHandler = nil; connection.cancel(); continuation.resume(returning: value) }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: let elapsed = start.duration(to: .now).components; finish(max(1, Int(elapsed.seconds * 1000 + elapsed.attoseconds / 1_000_000_000_000_000)))
                case .failed, .cancelled: finish(-1)
                default: break
                }
            }
            connection.start(queue: queue); queue.asyncAfter(deadline: .now() + 1.5) { finish(-1) }
        }
    }
    func toggle() {
        if busy { currentTask?.cancel(); manager?.connection.stopVPNTunnel(); return }
        if manager?.connection.status == .connected { manager?.connection.stopVPNTunnel(); connected = false; return }
        currentTask = Task {
            busy = true; defer { busy = false }
            do {
                let selected = nodes.filter { $0["location"] as? String == country }
                if Date().timeIntervalSince(measuredAt) > 120 || selected.contains(where: { measurements[($0["id"] as? String) ?? ""] == nil }) { try await measure(selected) }
                let ids = selected.compactMap { $0["id"] as? String }.filter { measurements[$0, default: -1] >= 0 }.sorted { measurements[$0]! < measurements[$1]! }
                guard let first = ids.first else { throw YayFailure(status: 0, message: "No reachable server. Try another country.") }
                let best = measurements[first]!, near = ids.filter { measurements[$0]! <= best + max(10, best / 20) }.shuffled()
                let ordered = near + ids.filter { !near.contains($0) }
                let vpn = manager ?? NETunnelProviderManager()
                let proto = NETunnelProviderProtocol(); proto.providerBundleIdentifier = Self.extensionID; proto.serverAddress = "Yay VPN"
                vpn.protocolConfiguration = proto; vpn.localizedDescription = "Yay VPN"; vpn.isEnabled = true
                try await vpn.saveToPreferences(); try await vpn.loadFromPreferences(); manager = vpn
                for id in ordered.prefix(3) {
                    try Task.checkCancellation(); try vpn.connection.startVPNTunnel(options: ["serverID": id as NSString])
                    for _ in 0..<35 { try await Task.sleep(nanoseconds: 1_000_000_000); if vpn.connection.status == .connected || vpn.connection.status == .disconnected { break } }
                    if vpn.connection.status == .connected, await verifyInternet() { connected = true; status = ""; return }
                    vpn.connection.stopVPNTunnel()
                    for _ in 0..<10 { if vpn.connection.status == .disconnected { break }; try await Task.sleep(nanoseconds: 500_000_000) }
                }
                throw YayFailure(status: 0, message: "VPN internet check failed")
            } catch { manager?.connection.stopVPNTunnel(); connected = false; status = error.localizedDescription }
        }
    }
    private func verifyInternet() async -> Bool {
        guard let session = manager?.connection as? NETunnelProviderSession else { return false }
        return await withCheckedContinuation { continuation in
            // Replies and timeout are serialized on the main queue and resume exactly once.
            var finished = false
            func finish(_ success: Bool) { guard !finished else { return }; finished = true; continuation.resume(returning: success) }
            do {
                try session.sendProviderMessage(Data("health".utf8)) { data in
                    DispatchQueue.main.async { finish(data == Data("ok".utf8)) }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 10) { finish(false) }
            } catch { finish(false) }
        }
    }
    func logout() async { currentTask?.cancel(); manager?.connection.stopVPNTunnel(); connected = false; await api?.logout(); signedIn = false; nodes.removeAll(); measurements.removeAll(); pings.removeAll() }
}
