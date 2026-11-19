import Foundation

struct NextTrackSettings: Equatable {
    static let parseKey = "music-preparse-next"
    static let cacheKey = "music-precache-next"
    let preparse: Bool
    let precache: Bool
    init(defaults: UserDefaults = .standard) {
        preparse = defaults.object(forKey: Self.parseKey) as? Bool ?? true
        precache = defaults.object(forKey: Self.cacheKey) as? Bool ?? false
    }
    var enabled: Bool { preparse || precache }
    func shouldStart(elapsed: Double, duration: Double) -> Bool {
        enabled && elapsed.isFinite && duration.isFinite && duration > 0 && elapsed / duration >= 0.7
    }
}
