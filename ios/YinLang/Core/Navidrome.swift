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
    private func nativeEndpoint(_ path: String) throws -> URL {
        let address = base.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard var parts = URLComponents(string: address), ["http", "https"].contains(parts.scheme?.lowercased() ?? ""), parts.host != nil else { throw AppFailure.message("服务器地址无效") }
        if parts.path.hasSuffix("/rest") { parts.path = String(parts.path.dropLast(5)) }
        parts.path += path; parts.queryItems = nil; parts.fragment = nil
        guard let url = parts.url else { throw AppFailure.message("服务器地址无效") }; return url
    }
    // Image upload is a Navidrome native API, not a Subsonic updatePlaylist
    // parameter. Authenticate only for this action; never persist/log the JWT.
    func changePlaylistCover(_ id: String, jpeg: Data?) async throws -> JSONObject {
        guard !id.isEmpty, id.range(of: #"^[A-Za-z0-9_-]+$"#, options: .regularExpression) != nil else { throw AppFailure.message("歌单标识无效") }
        let latest = try await playlist(id)
        guard canEdit(latest) else { throw AppFailure.message("当前账号没有修改此歌单封面的权限") }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 90
        let session = URLSession(configuration: config, delegate: NavidromeNoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var login = URLRequest(url: try nativeEndpoint("/auth/login")); login.httpMethod = "POST"
        login.setValue("application/json", forHTTPHeaderField: "Content-Type")
        login.httpBody = try JSONSerialization.data(withJSONObject: ["username": user, "password": password])
        let (authData, authResponse) = try await session.data(for: login)
        guard let authHTTP = authResponse as? HTTPURLResponse else { throw AppFailure.message("Navidrome 登录响应无效") }
        guard authHTTP.statusCode == 200,
              let auth = try? JSONSerialization.jsonObject(with: authData) as? JSONObject,
              !auth.string("token").isEmpty else {
            if [404, 405, 501].contains(authHTTP.statusCode) { throw AppFailure.message("此服务器不支持 Navidrome 原生登录和封面上传，请升级 Navidrome") }
            throw AppFailure.message("封面上传需 Navidrome 原生账号登录，请检查用户名、密码或代理登录配置")
        }
        var upload = URLRequest(url: try nativeEndpoint("/api/playlist/\(id)/image"))
        upload.httpMethod = jpeg == nil ? "DELETE" : "POST"
        upload.setValue("Bearer " + auth.string("token"), forHTTPHeaderField: "X-ND-Authorization")
        upload.setValue("application/json", forHTTPHeaderField: "Accept")
        if let jpeg {
            guard !jpeg.isEmpty, jpeg.count <= 4 * 1_048_576 else { throw AppFailure.message("封面文件过大，请选择较小的图片") }
            let boundary = "YinLang-" + UUID().uuidString
            upload.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            var data = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"playlist-cover.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8)
            data.append(jpeg); data.append(Data("\r\n--\(boundary)--\r\n".utf8)); upload.httpBody = data
        }
        let (data, response) = try await session.data(for: upload)
        guard let http = response as? HTTPURLResponse else { throw AppFailure.message("服务器封面上传响应无效") }
        switch http.statusCode {
        case 200...299: break
        case 401: throw AppFailure.message("封面上传登录已失效，请重试")
        case 403: throw AppFailure.message("服务器拒绝修改封面：需要歌单编辑权限，并为普通用户开启 EnableArtworkUpload，或使用管理员账号")
        case 404, 405, 501: throw AppFailure.message("此 Navidrome 版本未提供歌单封面上传接口，请升级服务器")
        case 413: throw AppFailure.message("图片超过服务器上传大小限制")
        default: throw AppFailure.message("修改服务器歌单封面失败（HTTP \(http.statusCode)），请稍后重试")
        }
        guard let result = try? JSONSerialization.jsonObject(with: data) as? JSONObject,
              result.string("status") == "ok" else { throw AppFailure.message("服务器未确认封面保存成功，请刷新检查") }
        // The server's artwork worker can refresh asynchronously. The caller
        // invalidates local image caches even if this metadata refresh fails.
        return (try? await playlist(id)) ?? latest
    }
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
    func ownsPlaylist(_ playlist: JSONObject) -> Bool {
        let owner = playlist.string("owner")
        return owner.isEmpty || owner.caseInsensitiveCompare(user) == .orderedSame
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
        let settings = playbackSettings(original: original)
        return try endpoint(original ? "download" : "stream", [.init(name: "id", value: item.key), .init(name: "maxBitRate", value: settings.bitrate), .init(name: "format", value: settings.format)])
    }
    static var playbackBitratePreferenceKey: String { NetworkRoute.shared.cellular ? "nav-bitrate-mobile" : "nav-bitrate-wifi" }
    func playbackSettings(original: Bool = false) -> (bitrate: String, format: String) {
        let defaults = UserDefaults.standard
        let networkKey = Self.playbackBitratePreferenceKey
        let bitrate = original ? "0" : defaults.string(forKey: networkKey) ?? defaults.string(forKey: "nav-bitrate") ?? "0"
        return (bitrate, bitrate == "0" ? "raw" : defaults.string(forKey: "nav-format") ?? "mp3")
    }
    func cover(_ id: String, size: Int = 400) -> String {
#if DEBUG
        if UITestFixtures.active { return "fixture:home_music_light" }
#endif
        guard !id.isEmpty else { return "" }
        // The endpoint uses a fresh authentication salt. Keep the resulting cover
        // URL stable across progress ticks, while separating accounts/passwords.
        let revision = UserDefaults.standard.string(forKey: "nav-cover-revision:" + identity + ":" + id) ?? ""
        let key = Disk.hash(jsonText([base, user, password, id, String(size), revision]))
        return NavidromeCoverURLs.shared.address(key) {
            var parameters: [URLQueryItem] = [.init(name: "id", value: id), .init(name: "size", value: "\(size)")]
            if !revision.isEmpty { parameters.append(.init(name: "_yl_cover_revision", value: revision)) }
            return (try? endpoint("getCoverArt", parameters).absoluteString) ?? ""
        }
    }
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

private final class NavidromeNoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Credentials and upload tokens must stay on the configured endpoint.
        completionHandler(nil)
    }
}

private final class NavidromeCoverURLs: @unchecked Sendable {
    static let shared = NavidromeCoverURLs()
    private let lock = NSLock()
    private var values: [String: String] = [:]
    func address(_ key: String, create: () -> String) -> String {
        lock.withLock {
            if let value = values[key] { return value }
            let value = create()
            if values.count >= 1024 { values.removeAll() }
            if !value.isEmpty { values[key] = value }
            return value
        }
    }
}

private final class NetworkRoute: @unchecked Sendable {
    static let shared = NetworkRoute()
    private let monitor = NWPathMonitor()
    private init() { monitor.start(queue: DispatchQueue(label: "YinLang.NetworkRoute")) }
    var cellular: Bool { monitor.currentPath.usesInterfaceType(.cellular) }
}
