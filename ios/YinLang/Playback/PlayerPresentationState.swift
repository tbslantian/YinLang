import Combine
import SwiftUI

@MainActor
final class PlayerProgressClock: ObservableObject {
    @Published var elapsed = 0.0
    @Published var duration = 0.0
    @Published var buffered = 0.0
}

// The screen shell never subscribes to elapsed/buffered/lyrics. Keep the audio
// engine's public model intact for queues, remote commands and other screens.
@MainActor
final class PlayerPresentationState: ObservableObject {
    @Published private(set) var current: MediaItem?
    @Published private(set) var playing: Bool
    @Published private(set) var loading: Bool
    @Published private(set) var mode: PlayMode
    @Published private(set) var audioInfo: AudioStreamInfo
    @Published private(set) var airPlayActive: Bool
    @Published private(set) var casting: Bool
    @Published private(set) var showQueue: Bool
    private var subscriptions = Set<AnyCancellable>()

    init(_ player: Player) {
        current = player.current; playing = player.playing; loading = player.loading
        mode = player.mode; audioInfo = player.audioInfo; airPlayActive = player.airPlayActive
        casting = player.casting; showQueue = player.showQueue
        player.$queue.combineLatest(player.$index).map { queue, index in
            queue.indices.contains(index) ? queue[index] : nil
        }.removeDuplicates().sink { [weak self] in self?.current = $0 }.store(in: &subscriptions)
        player.$playing.removeDuplicates().sink { [weak self] in self?.playing = $0 }.store(in: &subscriptions)
        player.$loading.removeDuplicates().sink { [weak self] in self?.loading = $0 }.store(in: &subscriptions)
        player.$mode.removeDuplicates().sink { [weak self] in self?.mode = $0 }.store(in: &subscriptions)
        player.$audioInfo.removeDuplicates().sink { [weak self] in self?.audioInfo = $0 }.store(in: &subscriptions)
        player.$airPlayActive.removeDuplicates().sink { [weak self] in self?.airPlayActive = $0 }.store(in: &subscriptions)
        player.$casting.removeDuplicates().sink { [weak self] in self?.casting = $0 }.store(in: &subscriptions)
        player.$showQueue.removeDuplicates().sink { [weak self] in self?.showQueue = $0 }.store(in: &subscriptions)
    }
}

// Resolving a Navidrome cover also reads account credentials. Cache that result
// so a layout toggle never calls Keychain or generates a new auth salt.
@MainActor
final class PlayerArtworkSource: ObservableObject {
    private var item: MediaItem?
    private var connection = -1
    private var address: URL?
    func url(for item: MediaItem?, store: AppStore) -> URL? {
        if self.item != item || connection != store.connectionRevision {
            self.item = item; connection = store.connectionRevision
            address = item.flatMap(store.artwork)
        }
        return address
    }
}

@MainActor
private final class PlayerProgressState: ObservableObject {
    struct Value: Equatable { var elapsed: Double; var duration: Double; var buffered: Double }
    @Published private(set) var value: Value
    private var subscription: AnyCancellable?
    init(_ player: Player) {
        value = Value(elapsed: player.elapsed, duration: player.duration, buffered: player.buffered)
        subscription = player.progress.$elapsed.combineLatest(player.progress.$duration, player.progress.$buffered)
            .map { Value(elapsed: $0.0, duration: $0.1, buffered: $0.2) }.removeDuplicates()
            .sink { [weak self] in self?.value = $0 }
    }
}

struct PlayerProgressSection: View {
    let player: Player
    let color: Color
    let muted: Color
    let tight: Bool
    let compact: Bool
    @StateObject private var state: PlayerProgressState
    init(player: Player, color: Color, muted: Color, tight: Bool, compact: Bool) {
        self.player = player; self.color = color; self.muted = muted; self.tight = tight; self.compact = compact
        _state = StateObject(wrappedValue: PlayerProgressState(player))
    }
    var body: some View {
        VStack(spacing: compact ? 3 : 8) {
            BufferedProgress(value: state.value.elapsed, duration: state.value.duration, buffered: state.value.buffered,
                             color: color, change: player.seek).frame(height: tight ? 20 : 26)
            HStack { Text(clockText(state.value.elapsed)); Spacer(); Text(clockText(state.value.duration)) }
                .font(.system(size: 12).monospacedDigit()).foregroundStyle(muted)
        }
    }
}

@MainActor
private final class PlayerNoticeState: ObservableObject {
    @Published private(set) var notice: ResolutionNotice?
    private var subscription: AnyCancellable?
    init(_ player: Player) {
        notice = player.resolutionNotice
        subscription = player.$resolutionNotice.removeDuplicates { $0?.id == $1?.id }
            .sink { [weak self] in self?.notice = $0 }
    }
}

struct PlayerNotice: View {
    let color: Color
    @StateObject private var state: PlayerNoticeState
    init(player: Player, color: Color) { self.color = color; _state = StateObject(wrappedValue: PlayerNoticeState(player)) }
    var body: some View {
        if let notice = state.notice {
            VStack(spacing: 3) {
                HStack(spacing: 5) {
                    if notice.phase == .resolving || notice.phase == .validating { ProgressView().controlSize(.mini).tint(color) }
                    Text(notice.title).font(.system(size: 13, weight: .bold)).lineLimit(1)
                }.frame(maxWidth: .infinity)
                if !notice.detail.isEmpty { Text(notice.detail).font(.system(size: 11)).lineLimit(1) }
            }.foregroundStyle(color).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine).accessibilityIdentifier("resolution-notice").allowsHitTesting(false)
        }
    }
}

// Preserve enqueue order when pausing, skipping and changing quality rapidly.
// Encoding, directory lookup and atomic file writes all run off the main queue.
final class PlaybackHistoryWriter {
    private let queue = DispatchQueue(label: "app.yinlang.playback-history", qos: .utility)
    func save(_ item: MediaItem, id: String, position: Double) {
        queue.async {
            UserDefaults.standard.set(position, forKey: "progress:" + id)
            if item.kind == "song" { Disk.save([item], "recent-music.json") }
        }
    }
    func saveBook(_ item: MediaItem) { queue.async { Disk.save(Optional(item), "last-book.json") } }
}
