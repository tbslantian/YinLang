import Foundation
import Security
import CryptoKit

enum Disk {
    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var support: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("YinLang") }
    static var cache: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("YinLang") }
    static func directory(_ url: URL) -> URL { try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true); return url }
    static func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func save<T: Encodable>(_ value: T, _ name: String) { if let data = try? JSONEncoder().encode(value) { try? data.write(to: directory(support).appendingPathComponent(name), options: .atomic) } }
    static func read<T: Decodable>(_ name: String, fallback: T) -> T {
        guard let data = try? Data(contentsOf: support.appendingPathComponent(name)), let value = try? JSONDecoder().decode(T.self, from: data) else { return fallback }; return value
    }
}

enum Keychain {
    private static func query(_ key: String) -> [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.yinlang.ios", kSecAttrAccount as String: key] }
    static func read(_ key: String) -> String {
        var q = query(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?; guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    static func write(_ key: String, _ value: String) throws {
        let q = query(key)
        if value.isEmpty { SecItemDelete(q as CFDictionary); return }
        let changes: [String: Any] = [kSecValueData as String: Data(value.utf8)]
        var status = SecItemUpdate(q as CFDictionary, changes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = q; insert.merge(changes) { _, new in new }; insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(insert as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw AppFailure.message("无法保存凭据（\(status)）") }
    }
}

actor MediaCache {
    static let shared = MediaCache()
    private var pending: [String: Task<URL?, Never>] = [:]
    func cached(_ key: String, books: Bool) -> URL? {
        let url = folder(books).appendingPathComponent(Disk.hash(key))
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return url
    }
    private func folder(_ books: Bool) -> URL { Disk.directory(Disk.cache.appendingPathComponent(books ? "books" : "music")) }
    func store(_ key: String, url: URL, headers: [String: String], books: Bool, limitMB: Int) async -> URL? {
        guard limitMB > 0, !url.pathExtension.lowercased().contains("m3u8") else { return nil }
        if let found = cached(key, books: books) { return found }
        if let task = pending[key] { return await task.value }
        let destination = folder(books).appendingPathComponent(Disk.hash(key))
        let task = Task<URL?, Never> {
            do {
                var request = URLRequest(url: url); request.allHTTPHeaderFields = headers; request.timeoutInterval = 90
                let (temp, response) = try await URLSession.shared.download(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      !((http.mimeType ?? "").contains("text") || (http.mimeType ?? "").contains("json") || (http.mimeType ?? "").contains("mpegurl")) else { return nil }
                let size = (try temp.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                guard size > 0, size <= limitMB * 1_048_576 else { try? FileManager.default.removeItem(at: temp); return nil }
                try FileManager.default.moveItem(at: temp, to: destination); return destination
            } catch { return nil }
        }
        pending[key] = task
        let result = await task.value; pending.removeValue(forKey: key); trim(books: books, limitMB: limitMB); return result
    }
    func trim(books: Bool, limitMB: Int) {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder(books), includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
        let entries = files.compactMap { url -> (URL, Int, Date)? in guard let info = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }; return (url, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast) }.sorted { $0.2 < $1.2 }
        var size = entries.reduce(0) { $0 + $1.1 }
        for entry in entries where size > limitMB * 1_048_576 { try? FileManager.default.removeItem(at: entry.0); size -= entry.1 }
    }
    func size(books: Bool) -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder(books), includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }
    func clear(books: Bool) { let url = folder(books); try? FileManager.default.removeItem(at: url) }
}
