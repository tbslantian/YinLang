import Foundation

@MainActor
final class AudioEffects: ObservableObject {
    static let shared = AudioEffects()
    static let frequencies = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]
    static let presets: [(String, [Float])] = [
        ("原声", [0,0,0,0,0,0,0,0,0,0]),
        ("流行", [6,5,-3,-2,5,4,-4,-3,6,4]),
        ("舞曲", [4,3,-4,-6,0,0,3,4,4,5]),
        ("摇滚", [7,6,2,1,-3,-4,2,1,4,5]),
        ("古典", [6,7,1,2,-1,1,-4,-6,-7,-8]),
        ("人声", [-5,-6,-4,-3,3,4,5,4,-3,-3]),
        ("慢歌", [5,4,2,0,-2,0,3,6,7,8]),
        ("电子乐", [6,5,0,-5,-4,0,6,8,8,7]),
        ("重低音", [8,7,5,4,0,0,0,0,0,0]),
        ("柔和", [-5,-5,-4,-4,3,2,4,4,0,0])
    ]
    struct Configuration: Codable {
        var enabled = false
        var gains: [Float] = Array(repeating: 0, count: 10)
        var wet: Float = 0
        var pitch: Float = 0
        var surround: Float = 0
    }
    @Published var config: Configuration = Disk.read("effects.json", fallback: Configuration()) { didSet { apply() } }
    @Published var custom: [String: Configuration] = Disk.read("effects-presets.json", fallback: [:])
    private init() { if config.gains.count != 10 { config.gains = Array(repeating: 0, count: 10) }; apply() }
    func apply() { config.gains.withUnsafeBufferPointer { YLSetEffects(config.enabled, $0.baseAddress, config.wet, config.pitch, config.surround) }; Disk.save(config, "effects.json") }
    func preset(_ gains: [Float]) { config.gains = gains; config.enabled = true }
    func save(_ name: String) { guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }; custom[name] = config; Disk.save(custom, "effects-presets.json") }
}
