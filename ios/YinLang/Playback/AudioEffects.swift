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
        var wet: Float = 0.25
        var dry: Float = 1
        var room: String = "关闭"
        var pitch: Float = 0
        var surround: Float = 0
    }
    @Published var config: Configuration = Disk.read("effects.json", fallback: Configuration()) { didSet { apply() } }
    @Published var custom: [String: Configuration] = Disk.read("effects-presets.json", fallback: [:])
    private init() { if config.gains.count != 10 { config.gains = Array(repeating: 0, count: 10) }; apply() }
    func apply() {
        let rooms: [String: (Float, Float)] = ["小房间":(0.5,0.4), "室内":(0.8,0.55), "影院":(1.4,0.65), "大厅":(2,0.78), "教堂":(3.2,0.88), "板式":(0.7,0.8)]
        let room = rooms[config.room]
        YLSetEnvironment(room == nil ? 1 : config.dry, room?.0 ?? 1, room?.1 ?? 0.67)
        config.gains.withUnsafeBufferPointer { YLSetEffects(config.enabled, $0.baseAddress, room == nil ? 0 : config.wet, config.pitch, config.surround) }; Disk.save(config, "effects.json")
    }
    func preset(_ gains: [Float]) { config.gains = gains; config.enabled = true }
    func save(_ name: String) { guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }; custom[name] = config; Disk.save(custom, "effects-presets.json") }
}
