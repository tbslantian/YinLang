import Foundation

enum LibraryTrackSort: String, CaseIterable {
    case original, added, name, artist, album, duration
    var title: String {
        switch self {
        case .original: return "歌单原顺序"
        case .added: return "加入日期"
        case .name: return "歌曲名称"
        case .artist: return "艺术家"
        case .album: return "专辑"
        case .duration: return "时长"
        }
    }
    static func parseDate(_ text: String) -> Date? {
        let iso = ISO8601DateFormatter()
        if let date = iso.date(from: text) { return date }
        iso.formatOptions.insert(.withFractionalSeconds)
        if let date = iso.date(from: text) { return date }
        let day = DateFormatter(); day.locale = Locale(identifier: "en_US_POSIX"); day.timeZone = TimeZone(secondsFromGMT: 0); day.dateFormat = "yyyy-MM-dd"
        return text.count == 10 ? day.date(from: text) : nil
    }
    static func playlistDate(_ item: MediaItem) -> Date? { parseDate(item.raw.string("playlistAddedAt", "addedAt", "dateAdded")) }
    static func date(_ item: MediaItem) -> Date? { playlistDate(item) ?? (item.source == "library" ? parseDate(item.raw.string("created")) : nil) }
    func apply(_ items: [MediaItem], descending: Bool) -> [MediaItem] {
        guard self != .original else { return items }
        let dates = self == .added ? items.map(Self.date) : []
        return items.enumerated().sorted { left, right in
            let a = left.element, b = right.element
            let result: ComparisonResult
            switch self {
            case .added:
                guard let x = dates[left.offset] else { return false }
                guard let y = dates[right.offset] else { return true }
                result = x == y ? .orderedSame : x < y ? .orderedAscending : .orderedDescending
            case .duration: result = a.duration == b.duration ? .orderedSame : a.duration < b.duration ? .orderedAscending : .orderedDescending
            case .name: result = a.title.localizedStandardCompare(b.title)
            case .artist, .album:
                let x = self == .artist ? a.subtitle : a.raw.string("album", "albumName")
                let y = self == .artist ? b.subtitle : b.raw.string("album", "albumName")
                let primary = x.localizedStandardCompare(y)
                result = primary == .orderedSame ? a.title.localizedStandardCompare(b.title) : primary
            case .original: return false
            }
            return result == .orderedSame ? left.offset < right.offset : result == (descending ? .orderedDescending : .orderedAscending)
        }.map(\.element)
    }
}
