import Foundation
import AVFoundation
import MediaPlayer
import SwiftUI

@MainActor
final class Player: ObservableObject {
    @Published var queue: [MediaItem] = []
    @Published var index = 0
    @Published var playing = false
    @Published var loading = false
    @Published var elapsed = 0.0
    @Published var duration = 0.0
    @Published var buffered = 0.0
    @Published var lyrics: [LyricLine] = []
    @Published var lyricText = ""
    @Published var status = "选择一首喜欢的音乐"
    @Published var mode: PlayMode = .sequence
    @Published var speed: Float = 1 { didSet { if playing && !casting { audio.rate = speed }; saveBookRate() } }
    @Published var sleepUntil: Date?
    @Published var sleepAfterCurrent = false
    @Published var showPlayer = false
    @Published var showQueue = false
    @Published var casting = false
    let dlna = DLNA()
    private let audio = AVPlayer()
    private var tick: Any?
    private var clock: Timer?
    private var endObserver: NSObjectProtocol?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var itemObservation: NSKeyValueObservation?
    private var load: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var generation = UUID()
    private var store: AppStore?
    private var lastSavedSecond = -1
    private var wasPlayingBeforeInterruption = false
    private var pendingSeek: Double?
    private var remotePolling = false
    private var lastRemotePoll = Date.distantPast
    var current: MediaItem? { queue.indices.contains(index) ? queue[index] : nil }
    init() {
        clock = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in Task { @MainActor in guard let self else { return }; self.update(self.audio.currentTime()) } }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in
                guard let self, notification.object as? AVPlayerItem === self.audio.currentItem else { return }
                if self.sleepAfterCurrent { self.pause(); self.sleepAfterCurrent = false } else { self.next(manual: false) }
            }
        }
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            let info = notification.userInfo
            Task { @MainActor in
                guard let self, let raw = info?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                if type == .began { self.wasPlayingBeforeInterruption = self.playing; self.pause() }
                else if self.wasPlayingBeforeInterruption, let options = info?[AVAudioSessionInterruptionOptionKey] as? UInt, AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { self.resume() }
            }
        }
        routeChange = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            if notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { Task { @MainActor in self?.pause() } }
        }
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.resume() }; return .success }
        center.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() }; return .success }
        center.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        center.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.previous() }; return .success }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(event.positionTime) }; return .success
        }
        center.skipForwardCommand.preferredIntervals = [30]; center.skipBackwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.addTarget { [weak self] _ in Task { @MainActor in guard let self else { return }; self.seek(self.elapsed + 30) }; return .success }
        center.skipBackwardCommand.addTarget { [weak self] _ in Task { @MainActor in guard let self else { return }; self.seek(self.elapsed - 15) }; return .success }
    }
    func attach(_ store: AppStore) { self.store = store }
    func play(_ items: [MediaItem], at index: Int = 0) {
        guard items.indices.contains(index) else { return }; saveProgress(); queue = items; self.index = index; start()
    }
    private func start() {
        guard let current, let store else { return }
        load?.cancel(); artworkTask?.cancel(); audio.pause(); audio.replaceCurrentItem(with: nil)
        generation = UUID(); let token = generation
        loading = true; playing = false; elapsed = 0; duration = 0; buffered = 0; lyrics = []; lyricText = ""; status = "正在解析音频…"
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        if current.kind == "episode" { speed = UserDefaults.standard.float(forKey: "speed:" + current.raw.string("_book")); if speed < 0.25 { speed = 1 } }
        else { speed = 1 }
        load = Task {
            do {
                let (url, headers, source) = try await store.resolve(current)
                try Task.checkCancellation(); guard generation == token else { return }
                if let receiver = dlna.current {
                    try await dlna.cast(current, resolved: url, headers: headers, to: receiver)
                    status = "DLNA · " + receiver.name; loading = false; playing = true; duration = current.duration; return
                }
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio, options: [.allowAirPlay])
                try AVAudioSession.sharedInstance().setActive(true)
                let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
                let item = AVPlayerItem(asset: asset)
                item.audioTimePitchAlgorithm = .spectral
                AudioEffects.shared.apply()
                if let track = try? await asset.loadTracks(withMediaType: .audio).first { _ = YLAttachAudioEffects(item, track) }
                try Task.checkCancellation(); guard generation == token else { return }
                itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                    Task { @MainActor in
                        guard let self, self.generation == token else { return }
                        if item.status == .failed { self.loading = false; self.playing = false; self.status = item.error?.localizedDescription ?? "音频格式无法播放"; self.store?.message = self.status }
                        else if item.status == .readyToPlay { self.loading = false }
                    }
                }
                audio.replaceCurrentItem(with: item); status = source
                if let position = pendingSeek { seek(position); pendingSeek = nil }
                if current.kind == "episode" {
                    let previous = UserDefaults.standard.double(forKey: "progress:" + current.id)
                    if previous > 0 { seek(previous) }
                    store.nowBook = current; Disk.save(Optional(current), "last-book.json")
                }
                resume(); updateNowPlaying()
                artworkTask = Task { [weak self] in
                    guard let self, let imageURL = store.artwork(current), let (data, _) = try? await URLSession.shared.data(from: imageURL), let image = UIImage(data: data), self.generation == token else { return }
                    var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }; MPNowPlayingInfoCenter.default().nowPlayingInfo = info
                }
                if current.kind == "song" {
                    let text = await store.lyrics(current)
                    guard generation == token else { return }; lyricText = text; lyrics = LyricLine.parse(text)
                }
                if !url.isFileURL {
                    let limit = UserDefaults.standard.object(forKey: current.kind == "episode" ? "book-cache" : "music-cache") as? Int ?? 512
                    let actualQuality = current.kind == "song" && current.source != "library" ? source.components(separatedBy: " · ").last : nil
                    let cached = await MediaCache.shared.store(store.cacheKey(current, quality: actualQuality), url: url, headers: headers, books: current.kind == "episode", limitMB: limit)
                    if generation == token, cached != nil { status = source + " · 已可离线播放" }
                }
            } catch {
                guard generation == token, !Task.isCancelled else { return }; loading = false; status = error.localizedDescription; store.message = status
            }
        }
    }
    func toggle() { playing ? pause() : resume() }
    func resume() {
        if let receiver = dlna.current { playing = true; Task { _ = try? await dlna.soap(receiver, action: "Play", values: [("Speed", "1")]) }; return }
        guard audio.currentItem != nil else { if current != nil { start() }; return }; try? AVAudioSession.sharedInstance().setActive(true); audio.playImmediately(atRate: speed); playing = true; updateNowPlaying()
    }
    func pause() { audio.pause(); if let receiver = dlna.current { Task { _ = try? await dlna.soap(receiver, action: "Pause") } }; playing = false; saveProgress(); updateNowPlaying() }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }; elapsed = max(0, duration > 0 ? min(duration, seconds) : seconds)
        if let receiver = dlna.current { let value = Int(elapsed); Task { _ = try? await dlna.soap(receiver, action: "Seek", values: [("Unit", "REL_TIME"), ("Target", String(format: "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60))]) } }
        else { audio.seek(to: CMTime(seconds: elapsed, preferredTimescale: 600)) }; updateNowPlaying()
    }
    func cast(to device: DLNADevice) async {
        guard let current, let store else { return }
        do { let (url, headers, _) = try await store.resolve(current); try await dlna.cast(current, resolved: url, headers: headers, to: device); audio.pause(); casting = true; playing = true; status = "DLNA · " + device.name }
        catch { store.message = error.localizedDescription }
    }
    func stopCasting() async { let position = elapsed; await dlna.stop(); casting = false; pendingSeek = position; start() }
    func next(manual: Bool = true) { guard let next = mode.nextIndex(current: index, count: queue.count, manual: manual) else { pause(); return }; saveProgress(); index = next; start() }
    func previous() { if elapsed > 3 { seek(0) } else if !queue.isEmpty { saveProgress(); index = (index - 1 + queue.count) % queue.count; start() } }
    func insertNext(_ item: MediaItem) { if queue.isEmpty { play([item]) } else { queue.insert(item, at: min(index + 1, queue.count)) } }
    func remove(_ position: Int) { guard queue.indices.contains(position) else { return }; if position == index { pause(); queue.remove(at: position); if !queue.isEmpty { index = min(index, queue.count - 1); start() } } else { queue.remove(at: position); if position < index { index -= 1 } } }
    func setSleep(_ minutes: Int?) { sleepUntil = minutes.map { Date().addingTimeInterval(Double($0 * 60)) }; sleepAfterCurrent = false }
    private func update(_ time: CMTime) {
        if let receiver = dlna.current {
            if let deadline = sleepUntil, Date() >= deadline { pause(); sleepUntil = nil }
            if !remotePolling && Date().timeIntervalSince(lastRemotePoll) >= 2 {
                remotePolling = true; lastRemotePoll = Date()
                Task { defer { remotePolling = false }
                    if let xml = try? await dlna.soap(receiver, action: "GetPositionInfo") {
                        elapsed = DLNA.seconds(DLNA.xmlValue(xml, tag: "RelTime")); duration = DLNA.seconds(DLNA.xmlValue(xml, tag: "TrackDuration")); saveProgress(); updateNowPlaying()
                    }
                    if playing, let xml = try? await dlna.soap(receiver, action: "GetTransportInfo"), DLNA.xmlValue(xml, tag: "CurrentTransportState") == "STOPPED", duration > 0, elapsed >= duration - 2 {
                        if sleepAfterCurrent { playing = false; sleepAfterCurrent = false } else { next(manual: false) }
                    }
                }
            }; return
        }
        let seconds = time.seconds; if seconds.isFinite { elapsed = max(0, seconds) }
        if let value = audio.currentItem?.duration.seconds, value.isFinite { duration = max(0, value) }
        buffered = audio.currentItem?.loadedTimeRanges.compactMap { $0.timeRangeValue.end.seconds }.filter(\.isFinite).max() ?? 0
        if let deadline = sleepUntil, Date() >= deadline { pause(); sleepUntil = nil }
        if Int(elapsed) % 5 == 0 && lastSavedSecond != Int(elapsed) { saveProgress(); lastSavedSecond = Int(elapsed) }
        updateNowPlaying()
    }
    private func saveBookRate() { if let current, current.kind == "episode" { UserDefaults.standard.set(speed, forKey: "speed:" + current.raw.string("_book")) } }
    private func saveProgress() { if let current { UserDefaults.standard.set(elapsed, forKey: "progress:" + current.id); if current.kind == "song" { Disk.save([current], "recent-music.json") } } }
    private func updateNowPlaying() {
        guard let current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPMediaItemPropertyTitle] = current.title; info[MPMediaItemPropertyArtist] = current.subtitle
        info[MPMediaItemPropertyPlaybackDuration] = duration; info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed; info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? speed : 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
