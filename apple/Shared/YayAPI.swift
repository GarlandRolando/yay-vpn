import Foundation
import CryptoKit
import Security

struct YayFailure: Error, LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}
private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
final class YayAPI {
    static let base = "https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api"
    private let key: P256.Signing.PrivateKey
    private let session: URLSession
    init() throws {
        if let data = try Self.read("device") { key = try P256.Signing.PrivateKey(rawRepresentation: data) }
        else { key = P256.Signing.PrivateKey(); try Self.save("device", key.rawRepresentation) }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 25
        config.urlCache = nil; config.httpCookieStorage = nil
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    private static func query(_ name: String) throws -> [String: Any] {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "YayKeychainGroup") as? String, !group.contains("$(") else {
            throw YayFailure(status: 0, message: "Configure the Apple signing team and shared Keychain group.")
        }
        var result: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "YayVPN", kSecAttrAccount as String: name, kSecAttrAccessGroup as String: group]
        #if os(macOS)
        result[kSecUseDataProtectionKeychain as String] = true
        #endif
        return result
    }
    static func read(_ name: String) throws -> Data? {
        var q = try query(name); q[kSecReturnData as String] = true
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw YayFailure(status: Int(status), message: "Secure storage unavailable") }
        return result as? Data
    }
    static func save(_ name: String, _ value: Data?) throws {
        let q = try query(name)
        if value == nil { let result = SecItemDelete(q as CFDictionary); guard result == errSecSuccess || result == errSecItemNotFound else { throw YayFailure(status: Int(result), message: "Could not clear secure storage") }; return }
        let attrs: [String: Any] = [kSecValueData as String: value!, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var result = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if result == errSecItemNotFound { result = SecItemAdd(q.merging(attrs) { _, new in new } as CFDictionary, nil) }
        guard result == errSecSuccess else { throw YayFailure(status: Int(result), message: "Could not save secure session") }
    }
    var signedIn: Bool { (try? Self.read("token")) != nil }
    func call(_ method: String, _ path: String, _ body: [String: Any]? = nil) async throws -> [String: Any] {
        let bytes = try body.map { try JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) } ?? Data()
        let timestamp = String(Int(Date().timeIntervalSince1970)), nonce = UUID().uuidString
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let message = [method, path, timestamp, nonce, hash].joined(separator: "\n")
        let signature = try key.signature(for: Data(message.utf8)).derRepresentation.base64EncodedString()
        var request = URLRequest(url: URL(string: Self.base + path)!)
        request.httpMethod = method; request.httpBody = body == nil ? nil : bytes
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(timestamp, forHTTPHeaderField: "X-Yay-Time"); request.setValue(nonce, forHTTPHeaderField: "X-Yay-Nonce"); request.setValue(signature, forHTTPHeaderField: "X-Yay-Signature")
        if let data = try Self.read("token"), let token = String(data: data, encoding: .utf8) { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard data.count <= 2 * 1024 * 1024, let result = try JSONSerialization.jsonObject(with: data) as? [String: Any], let http = response as? HTTPURLResponse else { throw YayFailure(status: 0, message: "Unexpected cloud response") }
        guard (200..<300).contains(http.statusCode) else { throw YayFailure(status: http.statusCode, message: (result["error"] as? String) ?? "Cloud request failed") }
        return result
    }
    func login(_ username: String, _ password: String) async throws {
        // SubjectPublicKeyInfo prefix for prime256v1, followed by the uncompressed EC point.
        let prefix: [UInt8] = [0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]
        let publicKey = (Data(prefix) + key.publicKey.x963Representation).base64EncodedString()
        #if os(macOS)
        let device = "macOS"
        #else
        let device = "iPhone / iPad"
        #endif
        let result = try await call("POST", "/v1/login", ["username": username, "password": password, "public_key": publicKey, "device_name": device])
        guard let token = result["token"] as? String else { throw YayFailure(status: 0, message: "Missing session") }
        try Self.save("token", Data(token.utf8))
    }
    func logout() async { _ = try? await call("POST", "/v1/logout", [:]); try? Self.save("token", nil) }
}
