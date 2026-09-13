import Foundation
import Network
import NetworkExtension
import Libbox

final class YayPlatform: NSObject, LibboxPlatformInterfaceProtocol {
    private unowned let tunnel: PacketTunnelProvider
    private var monitor: NWPathMonitor?
    init(_ tunnel: PacketTunnelProvider) { self.tunnel = tunnel }
    func localDNSTransport() -> LibboxLocalDNSTransportProtocol? { nil }
    func usePlatformAutoDetectInterfaceControl() -> Bool { false }
    func autoDetectInterfaceControl(_ fd: Int32) throws { }
    // Compatibility names used by later libbox headers; pinned 1.12 uses InterfaceControl.
    func usePlatformAutoDetectControl() -> Bool { false }
    func autoDetectControl(_ fd: Int32) throws { }
    func openTun(_ options: LibboxTunOptionsProtocol?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        guard let options, let ret0_ else { throw YayFailure(status: 0, message: "Missing tunnel options") }
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = NSNumber(value: options.getMTU())
        settings.ipv4Settings = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.252"])
        settings.ipv4Settings?.includedRoutes = [NEIPv4Route.default()]
        settings.ipv6Settings = NEIPv6Settings(addresses: ["fdfe:dcba:9876::1"], networkPrefixLengths: [126])
        settings.ipv6Settings?.includedRoutes = [NEIPv6Route.default()]
        settings.dnsSettings = NEDNSSettings(servers: ["172.19.0.2"])
        settings.dnsSettings?.matchDomains = [""]
        let semaphore = DispatchSemaphore(value: 0); var result: Error?
        tunnel.setTunnelNetworkSettings(settings) { error in result = error; semaphore.signal() }
        guard semaphore.wait(timeout: .now() + 15) == .success else { throw YayFailure(status: 0, message: "Tunnel setup timed out") }
        if let result { throw result }
        // Use the core's public descriptor helper; avoid packetFlow KVC/private-property access.
        let fd = LibboxGetTunnelFileDescriptor()
        guard fd >= 0 else { throw YayFailure(status: 0, message: "Tunnel descriptor unavailable") }
        ret0_.pointee = fd
    }
    func writeLog(_ message: String?) { }
    func useProcFS() -> Bool { false }
    func findConnectionOwner(_ ipProtocol: Int32, sourceAddress: String?, sourcePort: Int32, destinationAddress: String?, destinationPort: Int32, ret0_: UnsafeMutablePointer<Int32>?) throws { throw YayFailure(status: 0, message: "Process lookup is not used") }
    func packageName(byUid uid: Int32, error: NSErrorPointer) -> String { "" }
    func uid(byPackageName packageName: String?, ret0_: UnsafeMutablePointer<Int32>?) throws { throw YayFailure(status: 0, message: "Package lookup is not used") }
    func startDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
        let m = NWPathMonitor(); monitor = m
        let semaphore = DispatchSemaphore(value: 0)
        m.pathUpdateHandler = { path in
            if let first = path.availableInterfaces.first, path.status == .satisfied { listener?.updateDefaultInterface(first.name, interfaceIndex: Int32(first.index), isExpensive: path.isExpensive, isConstrained: path.isConstrained) }
            else { listener?.updateDefaultInterface("", interfaceIndex: -1, isExpensive: false, isConstrained: false) }
            semaphore.signal()
        }
        m.start(queue: DispatchQueue(label: "yay.network"))
        guard semaphore.wait(timeout: .now() + 10) == .success else { throw YayFailure(status: 0, message: "Network monitor unavailable") }
    }
    func closeDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws { shutdown() }
    func shutdown() { monitor?.cancel(); monitor = nil }
    func getInterfaces() throws -> LibboxNetworkInterfaceIteratorProtocol {
        let interfaces = (monitor?.currentPath.availableInterfaces ?? []).map { item -> LibboxNetworkInterface in
            let value = LibboxNetworkInterface(); value.name = item.name; value.index = Int32(item.index)
            value.type = item.type == .wifi ? LibboxInterfaceTypeWIFI : item.type == .cellular ? LibboxInterfaceTypeCellular : item.type == .wiredEthernet ? LibboxInterfaceTypeEthernet : LibboxInterfaceTypeOther
            return value
        }
        return InterfaceList(interfaces)
    }
    func underNetworkExtension() -> Bool { true }
    func includeAllNetworks() -> Bool { false }
    func readWIFIState() -> LibboxWIFIState? { nil }
    func systemCertificates() -> LibboxStringIteratorProtocol? { nil }
    func clearDNSCache() { }
    func send(_ notification: LibboxNotification?) throws { }
    final class InterfaceList: NSObject, LibboxNetworkInterfaceIteratorProtocol {
        var values: [LibboxNetworkInterface]; var index = 0
        init(_ values: [LibboxNetworkInterface]) { self.values = values }
        func hasNext() -> Bool { index < values.count }
        func next() -> LibboxNetworkInterface? { guard hasNext() else { return nil }; defer { index += 1 }; return values[index] }
    }
}
