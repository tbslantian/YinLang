import Foundation
import CryptoKit
import Network

struct Navidrome {
    let base: String
    let user: String
    let password: String
    var identity: String { Disk.hash(base + "|" + user) }
    func endpoint(_ command: String, _ parameters: [URLQueryItem] = []) throws -> URL {
        let address = base.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard var parts = URLComponents(string: address), ["http", "https"].contains(parts.scheme?.lowercased() ?? ""), parts.host != nil, !user.isEmpty else { throw AppFailure.message("请填写有效的服务器地址和账号") }
        if parts.path.hasSuffix("/rest") { parts.path = String(parts.path.dropLast(5)) }
        parts.path += "/rest/\(command).view"
        let salt = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        parts.queryItems = [URLQueryItem(name: "u", value: user), .init(name: "t", value: token), .init(name: "s", value: salt), .init(name: "v", value: "1.16.1"), .init(name: "c", value: "YinLang"), .init(name: "f", value: "json")] + parameters
        guard let url = parts.url else { throw AppFailure.message("服务器地址无效") }; return url
    }
    func get(_ command: String, _ parameters: [URLQueryItem] = []) async throws -> JSONObject {
#if DEBUG
        if UITestFixtures.active { return UITestFixtures.nav(command) }
#endif
        let (data, response) = try await URLSession.shared.data(from: endpoint(command, parameters))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppFailure.message("音乐库服务器请求失败") }
        let root = (try JSONSerialization.jsonObject(with: data) as? JSONObject)?.object("subsonic-response") ?? [:]
        guard root.string("status") == "ok" else { throw AppFailure.message(root.object("error").string("message")) }; return root
    }
    func search(_ query: String, offset: Int = 0) async throws -> [JSONObject] {
        try await get("search3", [.init(name: "query", value: query), .init(name: "songCount", value: "50"), .init(name: "songOffset", value: "\(offset)"), .init(name: "albumCount", value: "0"), .init(name: "artistCount", value: "0")]).object("searchResult3").rows("song")
    }
    func playlist(_ id: String) async throws -> JSONObject { try await get("getPlaylist", [.init(name: "id", value: id)]).object("playlist") }
    func songCount() async throws -> Int? {
        let status = try await get("getScanStatus").object("scanStatus")
        guard let count = Int(status.string("count")), count >= 0 else { return nil }
        return count
    }
    func entryCoverIDs(playlists: Bool) async throws -> [String] {
        let rows: [JSONObject]
        if playlists {
            let lists = try await get("getPlaylists").object("playlists").rows("playlist")
            if lists.contains(where: { !$0.string("coverArt").isEmpty }) { rows = lists }
            else if let first = lists.first(where: { (($0["songCount"] as? NSNumber)?.intValue ?? 0) > 0 }), !first.string("id").isEmpty {
                rows = try await playlist(first.string("id")).rows("entry")
            } else { rows = [] }
        } else {
            rows = try await get("getAlbumList2", [.init(name: "type", value: "newest"), .init(name: "size", value: "8"), .init(name: "offset", value: "0")]).object("albumList2").rows("album")
        }
        var seen: Set<String> = []
        return Array(rows.map { $0.string("coverArt") }.filter { !$0.isEmpty && seen.insert($0).inserted }.prefix(6))
    }
    func canEdit(_ playlist: JSONObject) -> Bool {
        if let readOnly = playlist["readonly"] as? Bool { return !readOnly }
        let owner = playlist.string("owner").trimmingCharacters(in: .whitespacesAndNewlines)
        return !owner.isEmpty && owner.caseInsensitiveCompare(user) == .orderedSame
    }
    func remove(_ id: String, expected: [MediaItem], position: Int) async throws {
        guard expected.indices.contains(position) else { throw AppFailure.message("无效的歌曲位置") }
        let current = try await playlist(id)
        guard canEdit(current) else { throw AppFailure.message("当前账号没有修改此歌单的权限") }
        guard current.rows("entry").map({ $0.string("id") }) == expected.map(\.key) else { throw AppFailure.message("歌单已发生变化，请刷新后重试") }
        _ = try await get("updatePlaylist", [.init(name: "playlistId", value: id), .init(name: "songIndexToRemove", value: "\(position)")])
    }
    func stream(_ item: MediaItem, original: Bool = false) throws -> URL {
        let defaults = UserDefaults.standard
        let networkKey = NetworkRoute.shared.cellular ? "nav-bitrate-mobile" : "nav-bitrate-wifi"
        let bitrate = original ? "0" : defaults.string(forKey: networkKey) ?? defaults.string(forKey: "nav-bitrate") ?? "0"
        return try endpoint(original ? "download" : "stream", [.init(name: "id", value: item.key), .init(name: "maxBitRate", value: bitrate), .init(name: "format", value: bitrate == "0" ? "raw" : defaults.string(forKey: "nav-format") ?? "mp3")])
    }
    func cover(_ id: String, size: Int = 400) -> String {
#if DEBUG
        if UITestFixtures.active { return "fixture:home_music_light" }
#endif
        return (try? endpoint("getCoverArt", [.init(name: "id", value: id), .init(name: "size", value: "\(size)")]).absoluteString) ?? "" }
    func lyrics(_ item: MediaItem) async -> String {
        if let result = try? await get("getLyricsBySongId", [.init(name: "id", value: item.key)]) {
            let lists = result.object("lyricsList").rows("structuredLyrics")
            for list in lists.sorted(by: { ($0["synced"] as? Bool ?? false) && !($1["synced"] as? Bool ?? false) }) {
                let lines = list.rows("line"); if lines.isEmpty { continue }
                return lines.map { line in
                    guard list["synced"] as? Bool == true, let start = line["start"] as? Double else { return line.string("value") }
                    let ms = max(0, Int(start + (list["offset"] as? Double ?? 0)))
                    return String(format: "[%02d:%02d.%03d]", ms / 60000, ms / 1000 % 60, ms % 1000) + line.string("value")
                }.joined(separator: "\n")
            }
        }
        return (try? await get("getLyrics", [.init(name: "artist", value: item.subtitle), .init(name: "title", value: item.title)]).object("lyrics").string("value")) ?? ""
    }
}

private final class NetworkRoute: @unchecked Sendable {
    static let shared = NetworkRoute()
    private let monitor = NWPathMonitor()
    private init() { monitor.start(queue: DispatchQueue(label: "YinLang.NetworkRoute")) }
    var cellular: Bool { monitor.currentPath.usesInterfaceType(.cellular) }
}
