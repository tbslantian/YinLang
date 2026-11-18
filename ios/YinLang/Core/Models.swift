import Foundation

typealias JSONObject = [String: Any]

enum AppFailure: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

func jsonText(_ value: Any) -> String {
    guard JSONSerialization.isValidJSONObject(value),
          let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]),
          let text = String(data: data, encoding: .utf8) else { return "null" }
    return text
}

func quote(_ text: String) -> String {
    let array = jsonText([text]); return String(array.dropFirst().dropLast())
}

extension Dictionary where Key == String, Value == Any {
    func string(_ keys: String...) -> String {
        for key in keys {
            if let text = self[key] as? String, !text.isEmpty, text != "null" { return text }
            if let number = self[key] as? NSNumber { return number.stringValue }
        }
        return ""
    }
    func rows(_ key: String) -> [JSONObject] { self[key] as? [JSONObject] ?? [] }
    func object(_ key: String) -> JSONObject { self[key] as? JSONObject ?? [:] }
}

struct MediaItem: Identifiable, Codable, Equatable, Hashable {
    var key: String
    var title: String
    var subtitle: String
    var cover: String
    var source: String
    var payload: Data
    var kind: String = "song"
    var id: String { source + ":" + kind + ":" + (source == "library" ? raw.string("_account") + ":" : "") + key }
    var raw: JSONObject { (try? JSONSerialization.jsonObject(with: payload)) as? JSONObject ?? [:] }
    var duration: Double {
        if let seconds = raw["duration"] as? Double { return seconds }
        let text = raw.string("interval", "duration")
        return text.split(separator: ":").reduce(0.0) { $0 * 60 + (Double($1) ?? 0) }
    }
    init(_ row: JSONObject, source: String? = nil, kind: String = "song") {
        self.source = source ?? row.string("source")
        self.kind = kind
        key = row.string("songmid", "songId", "copyrightId", "id", "hash", "bangid", "bookUrl", "url")
        title = row.string("title", "name", "label")
        subtitle = row.string("singer", "artist", "author", "description", "intro")
        cover = row.string("img", "pic", "cover", "coverUrl", "coverArt", "imgurl")
        payload = (try? JSONSerialization.data(withJSONObject: row, options: .sortedKeys)) ?? Data("{}".utf8)
    }
}

enum SongMatch {
    static func normalized(_ text: String) -> String {
        text.folding(options: [.widthInsensitive, .caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX")).unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }
    static func artists(_ text: String) -> Set<String> {
        let text = text.replacingOccurrences(of: #"(?i)\s+(?:feat\.?|ft\.?)\s+"#, with: "/", options: .regularExpression)
        return Set(text.components(separatedBy: CharacterSet(charactersIn: "、/&,，;；")).map(normalized).filter { !$0.isEmpty })
    }
    static func same(_ wanted: MediaItem, _ found: MediaItem) -> Bool {
        guard !normalized(wanted.title).isEmpty, normalized(wanted.title) == normalized(found.title), !artists(wanted.subtitle).intersection(artists(found.subtitle)).isEmpty else { return false }
        return wanted.duration <= 0 || found.duration <= 0 || abs(wanted.duration - found.duration) <= max(8, min(wanted.duration, found.duration) / 20)
    }
}

struct Plugin: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var version: String
    var script: String
    var address: String
    var books: Bool
    var enabled: Bool = true
}

enum Platform: String, CaseIterable, Identifiable {
    case all, wy, tx, kg, kw, mg
    var id: String { rawValue }
    var title: String {
        switch self { case .all: return "聚合"; case .wy: return "网易云"; case .tx: return "QQ 音乐"
        case .kg: return "酷狗"; case .kw: return "酷我"; case .mg: return "咪咕" }
    }
}

enum PlayMode: String, CaseIterable, Codable {
    case sequence, loop, single, shuffle
    var title: String { switch self { case .sequence: return "顺序播放"; case .loop: return "列表循环"; case .single: return "单曲循环"; case .shuffle: return "随机播放" } }
    var icon: String { switch self { case .sequence: return "arrow.right"; case .loop: return "repeat"; case .single: return "repeat.1"; case .shuffle: return "shuffle" } }
    func nextIndex(current: Int, count: Int, manual: Bool) -> Int? {
        guard count > 0 else { return nil }
        if self == .single && !manual { return current }
        if self == .shuffle { return count == 1 ? 0 : (0..<count).filter { $0 != current }.randomElement() }
        if current + 1 < count { return current + 1 }
        return self == .sequence && !manual ? nil : 0
    }
}

struct LyricLine: Identifiable, Equatable {
    var time: Double
    var text: String
    var id: String { "\(time):\(text)" }
    static func parse(_ text: String) -> [LyricLine] {
        let regex = try! NSRegularExpression(pattern: #"\[(\d+):(\d+(?:\.\d+)?)\]"#)
        var lines: [LyricLine] = []
        for line in text.components(separatedBy: .newlines) {
            let range = NSRange(line.startIndex..., in: line)
            let matches = regex.matches(in: line, range: range)
            let words = regex.stringByReplacingMatches(in: line, range: range, withTemplate: "").trimmingCharacters(in: .whitespaces)
            for match in matches {
                guard let m = Range(match.range(at: 1), in: line), let s = Range(match.range(at: 2), in: line) else { continue }
                lines.append(.init(time: (Double(line[m]) ?? 0) * 60 + (Double(line[s]) ?? 0), text: words))
            }
        }
        return lines.sorted { $0.time < $1.time }
    }
}

struct DownloadedItem: Identifiable, Codable {
    var item: MediaItem
    var filename: String
    var id: String { item.id }
}
