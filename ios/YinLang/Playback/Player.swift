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
    @Published var audioInfo = AudioStreamInfo()
    @Published private(set) var resolutionNotice: ResolutionNotice?
    @Published var mode: PlayMode = .sequence
    @Published var speed: Float = 1 { didSet { if playing && !casting { audio.rate = speed }; saveBookRate() } }
    @Published var sleepUntil: Date?
    @Published var sleepAfterCurrent = false
    @Published var showPlayer = false
    @Published var showQueue = false
    @Published var casting = false
    @Published private(set) var audioOutputName = "本机"
    @Published private(set) var airPlayActive = false
    let dlna = DLNA()
    private let audio = AVPlayer()
    private var clock: Timer?
    private var endObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var itemObservation: NSKeyValueObservation?
    private var load: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var externalMetadata = ExternalPlaybackMetadata()
    private var audioInfoTask: Task<Void, Never>?
    private var cacheTask: Task<Void, Never>?
    private typealias ResolvedAudio = (URL, [String: String], String)
    private struct NextPreparation {
        let id: UUID
        let index: Int
        let itemID: String
        let context: String
        let task: Task<ResolvedAudio, Error>
        var cachedURL: URL?
    }
    private var nextPreparation: NextPreparation?
    private var preparationCacheTask: Task<Void, Never>?
    private var lastPreparationAttempt = ""
    private var noticeTask: Task<Void, Never>?
    private var resolutionSucceeded = false
    private var bookResumeTask: Task<Void, Never>?
    private var generation = UUID()
    private var store: AppStore?
    private var lastSavedSecond = -1
    private var wasPlayingBeforeInterruption = false
    private var remotePolling = false
    private var lastRemotePoll = Date.distantPast
    var current: MediaItem? { queue.indices.contains(index) ? queue[index] : nil }
    init() {
        audio.allowsExternalPlayback = true
        refreshAudioOutput()
        clock = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in Task { @MainActor in
            guard let self else { return }
#if DEBUG
            if UITestFixtures.active { return }
#endif
            self.update(self.audio.currentTime())
        } }
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
            let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            let previous = notification.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription
            Task { @MainActor in self?.audioRouteChanged(reason: reason, previous: previous) }
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
        guard items.indices.contains(index) else { return }; bookResumeTask?.cancel(); saveProgress(); queue = items; self.index = index; start()
    }
    func resumeBook(_ saved: MediaItem) {
        guard let store else { return }
        if current?.id == saved.id, queue.count > 1 { resume(); showPlayer = true; return }
        bookResumeTask?.cancel()
        let token = generation
        bookResumeTask = Task {
            do {
                let book = try BookPlayback.book(for: saved, shelf: store.bookshelf)
                let detail = try await store.bookCall(book.source, pluginID: book.raw.string("_plugin"), action: "bookDetail", args: [book.key, true, true]) as? JSONObject ?? [:]
                try Task.checkCancellation(); guard generation == token else { return }
                let episodes = BookPlayback.episodes(detail, book: book)
                let position = try BookPlayback.resumeIndex(saved, episodes: episodes)
                // Complete this task before play() cancels pending resume work.
                bookResumeTask = nil
                play(episodes, at: position); showPlayer = true
            } catch {
                if !Task.isCancelled && generation == token { store.message = error.localizedDescription }
            }
        }
    }
    private func start(useLocal: Bool = true, resumeAt: Double? = nil, prepared: Task<ResolvedAudio, Error>? = nil, allowCacheWrites: Bool = true) {
        guard let current, let store else { return }
        load?.cancel(); artworkTask?.cancel(); audioInfoTask?.cancel(); cacheTask?.cancel(); cancelPreparation(); itemObservation = nil
        noticeTask?.cancel(); resolutionNotice = nil; resolutionSucceeded = prepared != nil; audioInfo = AudioStreamInfo()
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver); self.failureObserver = nil }
        audio.pause(); audio.replaceCurrentItem(with: nil)
        generation = UUID(); let token = generation
        loading = true; playing = false; elapsed = 0; duration = 0; buffered = 0; lyrics = []; lyricText = ""; status = current.source == "library" ? "正在载入音乐库音频…" : prepared == nil ? "正在解析音频…" : "正在载入已准备的音频…"
        externalMetadata = ExternalPlaybackMetadata()
        if prepared == nil { publishResolution(ResolutionNotice(phase: .resolving, detail: current.title)) }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        if useLocal {
            if current.kind == "episode" { speed = UserDefaults.standard.float(forKey: "speed:" + current.raw.string("_book")); if speed < 0.25 { speed = 1 } }
            else { speed = 1 }
        }
        load = Task {
            do {
                let progress: (ResolutionNotice) -> Void = { [weak self] notice in
                    guard let self, self.generation == token, !Task.isCancelled else { return }
                    self.publishResolution(notice)
                }
                let resolved: ResolvedAudio
                if let prepared {
                    do { resolved = try await withTaskCancellationHandler(operation: { try await prepared.value }, onCancel: { prepared.cancel() }) }
                    catch {
                        try Task.checkCancellation(); resolutionSucceeded = false
                        publishResolution(ResolutionNotice(phase: .resolving, detail: current.title))
                        resolved = try await store.resolve(current, useLocal: useLocal, progress: progress)
                    }
                } else { resolved = try await store.resolve(current, useLocal: useLocal, progress: progress) }
                let (url, headers, source) = resolved
                try Task.checkCancellation(); guard generation == token else { return }
                if let receiver = dlna.current {
                    try await dlna.cast(current, resolved: url, headers: headers, to: receiver)
                    status = "DLNA · " + receiver.name; loading = false; playing = true; duration = current.duration
                    if !resolutionSucceeded { publishResolution(ResolutionNotice(phase: .success, detail: status)) }
                    return
                }
                try activateAudioSession()
                let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
                let item = AVPlayerItem(asset: asset)
                item.externalMetadata = externalMetadata.items(for: current)
                item.audioTimePitchAlgorithm = .spectral
                AudioEffects.shared.apply()
                if let track = try? await asset.loadTracks(withMediaType: .audio).first {
                    _ = YLAttachAudioEffects(item, track)
                    audioInfoTask = Task { [weak self] in
                        var info = await AudioStreamInfo.read(track, asset: asset, url: url, headers: headers)
                        if current.source == "library", store.nav?.playbackSettings().bitrate == "0" {
                            // Original Navidrome stream metadata describes this
                            // file; do not apply it to a transcoded response.
                            if info.bitsPerSecond <= 0, let rate = Double(current.raw.string("bitRate")), rate > 0 { info.bitsPerSecond = rate * 1000 }
                            if info.sampleRate <= 0, let sample = Double(current.raw.string("samplingRate")), sample > 0 { info.sampleRate = sample }
                        }
                        guard let self, self.generation == token, !Task.isCancelled else { return }
                        self.audioInfo = info
                    }
                }
                try Task.checkCancellation(); guard generation == token else { return }
                itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                    Task { @MainActor in
                        guard let self, self.generation == token, self.audio.currentItem === item else { return }
                        if item.status == .failed { self.handleFailure(item, url: url, token: token, retryLocal: useLocal, retryPrepared: prepared != nil) }
                        else if item.status == .readyToPlay {
                            self.loading = false
                            if !self.resolutionSucceeded { self.publishResolution(ResolutionNotice(phase: .success, detail: source)) }
                        }
                    }
                }
                failureObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                    Task { @MainActor in self?.handleFailure(item, url: url, token: token, retryLocal: useLocal, retryPrepared: prepared != nil) }
                }
                audio.replaceCurrentItem(with: item); status = source
                if current.kind == "episode" {
                    let previous = UserDefaults.standard.double(forKey: "progress:" + current.id)
                    if let resumeAt { seek(resumeAt) } else if previous > 0 { seek(previous) }
                    store.nowBook = current; Disk.save(Optional(current), "last-book.json")
                } else if let resumeAt { seek(resumeAt) }
                resume(); updateNowPlaying()
                // Cache independently of lyrics; a slow lyric source must not
                // postpone writing an already verified audio response.
                if !url.isFileURL && allowCacheWrites {
                    cacheTask = Task { [weak self] in
                        guard let cached = await store.cacheResolved(current, url: url, headers: headers, source: source), !Task.isCancelled,
                              let self, self.generation == token else { return }
                        self.status = source + " · 已可离线播放"
                        await self.announceCached(current.title, token: token)
                        _ = cached
                    }
                }
                artworkTask = Task { [weak self] in
                    guard let self, let imageURL = store.artwork(current), let image = await ArtworkCache.shared.image(imageURL), !Task.isCancelled, self.generation == token else { return }
                    let jpeg = await Task.detached(priority: .utility) { image.jpegData(compressionQuality: 0.85) }.value
                    guard !Task.isCancelled, self.generation == token else { return }
                    self.externalMetadata.setArtwork(image, jpeg: jpeg)
                    self.updateExternalMetadata(); self.updateNowPlaying()
                }
                if current.kind == "song" {
                    let text = await store.lyrics(current)
                    guard !Task.isCancelled, generation == token else { return }; lyricText = text; lyrics = LyricLine.parse(text)
                    externalMetadata.setLyrics(text, lines: lyrics); updateExternalMetadata()
                }
            } catch {
                guard generation == token, !Task.isCancelled else { return }; loading = false; status = error.localizedDescription; store.message = status
                publishResolution(ResolutionNotice(phase: .failure, detail: status))
            }
        }
    }
    func publishResolution(_ notice: ResolutionNotice, for item: MediaItem? = nil) {
        // Navidrome streams directly from its server. Keep cache-completion
        // notices, but never label its load, success or failure as source parsing.
        guard (item ?? current)?.source != "library" || notice.phase == .cached else { return }
        noticeTask?.cancel()
        if notice.phase == .success { resolutionSucceeded = true }
        withAnimation(.easeOut(duration: 0.18)) { resolutionNotice = notice }
        guard notice.phase == .success || notice.phase == .cached || notice.phase == .failure else { return }
        noticeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: notice.phase == .failure ? 8_500_000_000 : 2_000_000_000)
            guard let self, !Task.isCancelled, self.resolutionNotice?.id == notice.id else { return }
            withAnimation(.easeOut(duration: 0.18)) { self.resolutionNotice = nil }
        }
    }
    private func announceCached(_ title: String, token: UUID) async {
        if resolutionNotice?.phase == .success { try? await Task.sleep(nanoseconds: 2_100_000_000) }
        guard generation == token, !Task.isCancelled else { return }
        publishResolution(ResolutionNotice(phase: .cached, detail: title))
    }
    private func cancelPreparation() {
        nextPreparation?.task.cancel(); nextPreparation = nil
        preparationCacheTask?.cancel(); preparationCacheTask = nil; lastPreparationAttempt = ""
    }
    private var preparationContext: String {
        guard let store else { return "" }
        let settings = NextTrackSettings()
        return jsonText([generation.uuidString, store.resolutionConfiguration, mode.rawValue, String(settings.preparse), String(settings.precache)] + queue.map(\.id))
    }
    // Called by the playback clock; exposed internally so regression tests can
    // drive the 70% boundary without waiting through a whole song.
    func prepareNextIfNeeded() {
        let settings = NextTrackSettings(), context = preparationContext
        if nextPreparation?.context != context { cancelPreparation() }
        guard settings.enabled, playing, !loading, !casting, current?.kind == "song", let store else {
            if !settings.enabled { cancelPreparation() }; return
        }
        guard settings.shouldStart(elapsed: elapsed, duration: duration), nextPreparation == nil, lastPreparationAttempt != context,
              let next = mode.nextIndex(current: index, count: queue.count, manual: false), next != index, queue.indices.contains(next) else { return }
        let item = queue[next], token = generation, id = UUID()
        guard item.kind == "song" else { return }
        lastPreparationAttempt = context
        let task = Task<ResolvedAudio, Error> { [weak self] in
            let result = try await store.resolve(item)
            try Task.checkCancellation()
            // A manual/automatic next may already be awaiting this same task.
            // It still owns the verified result even after the old page closes.
            guard let self, self.generation == token, self.nextPreparation?.id == id else { return result }
            self.publishResolution(ResolutionNotice(phase: .success, detail: "下一首 · " + item.title), for: item)
            if settings.precache && !result.0.isFileURL {
                self.preparationCacheTask = Task { [weak self] in
                    guard let cached = await store.cacheResolved(item, url: result.0, headers: result.1, source: result.2), !Task.isCancelled,
                          let self, self.generation == token, self.nextPreparation?.id == id else { return }
                    self.nextPreparation?.cachedURL = cached
                    await self.announceCached("下一首 · " + item.title, token: token)
                }
            }
            return result
        }
        nextPreparation = NextPreparation(id: id, index: next, itemID: item.id, context: context, task: task)
    }
    private func takePreparation(at index: Int) -> Task<ResolvedAudio, Error>? {
        guard let prepared = nextPreparation, prepared.context == preparationContext,
              prepared.index == index, queue.indices.contains(index), prepared.itemID == queue[index].id else { return nil }
        nextPreparation = nil
        // Let an in-flight cache write finish; the new current-track cache task
        // joins the same MediaCache operation instead of downloading twice.
        preparationCacheTask = nil
        return Task {
            if let url = prepared.cachedURL { return (url, [:], "离线缓存") }
            return try await withTaskCancellationHandler(operation: { try await prepared.task.value }, onCancel: { prepared.task.cancel() })
        }
    }
    private func handleFailure(_ item: AVPlayerItem, url: URL, token: UUID, retryLocal: Bool, retryPrepared: Bool) {
        guard generation == token, audio.currentItem === item else { return }
        if url.isFileURL && retryLocal {
            let position = elapsed
            // Detach first: status KVO and end-failure notifications can both fire.
            itemObservation = nil; audio.pause(); audio.replaceCurrentItem(with: nil)
            loading = true; playing = false; status = "本地音频失效，正在重新获取…"
            load?.cancel()
            // Re-resolve once without reading, removing or rewriting local files.
            load = nil
            start(useLocal: false, resumeAt: position, allowCacheWrites: false)
        } else if retryPrepared {
            let position = elapsed
            itemObservation = nil; audio.pause(); audio.replaceCurrentItem(with: nil)
            start(useLocal: false, resumeAt: position)
        } else {
            loading = false; playing = false; status = item.error?.localizedDescription ?? "音频格式无法播放"; store?.message = status
            publishResolution(ResolutionNotice(phase: .failure, detail: status))
        }
    }
    func toggle() { playing ? pause() : resume() }
    func reloadQuality() { start(useLocal: false, resumeAt: elapsed) }
    private func activateAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            // Long-form playback already supports AirPlay. Explicit allowAirPlay is
            // a playAndRecord option and fails with OSStatus -50 on physical devices.
            if session.category != .playback || session.mode != .default || session.routeSharingPolicy != .longFormAudio {
                try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            }
            try session.setActive(true)
            refreshAudioOutput()
        } catch {
            let failure = error as NSError
            throw AppFailure.message("初始化音频会话失败：\(failure.localizedDescription) [\(failure.domain)/\(failure.code)]")
        }
    }
    func prepareAirPlayRoutePicker() {
        guard current != nil, dlna.current == nil else { return }
        do { try activateAudioSession(); updateExternalMetadata(); updateNowPlaying() }
        catch { store?.message = error.localizedDescription }
    }
    func refreshAirPlayRoute() {
        refreshAudioOutput(); updateExternalMetadata(); updateNowPlaying()
    }
    private func refreshAudioOutput() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let name = outputs.map(\.portName).joined(separator: "、")
        let wireless = outputs.contains { $0.portType == .airPlay }
        if audioOutputName != (name.isEmpty ? "本机" : name) { audioOutputName = name.isEmpty ? "本机" : name }
        if airPlayActive != wireless { airPlayActive = wireless }
    }
    private func audioRouteChanged(reason: UInt?, previous: AVAudioSessionRouteDescription?) {
        refreshAudioOutput()
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let returnedToPhone = outputs.contains { $0.portType == .builtInSpeaker || $0.portType == .builtInReceiver }
        let personalPorts: [AVAudioSession.Port] = [.headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .usbAudio, .airPlay]
        let lostPersonalRoute = previous?.outputs.contains { personalPorts.contains($0.portType) } == true
        // Replacing one external route with AirPlay is not unplugging headphones.
        if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue,
           dlna.current == nil, returnedToPhone, lostPersonalRoute,
           UserDefaults.standard.object(forKey: "player-pause-on-unplug") as? Bool ?? true { pause() }
        refreshAirPlayRoute()
    }
    private func updateExternalMetadata() {
        guard let current, dlna.current == nil else { return }
        audio.currentItem?.externalMetadata = externalMetadata.items(for: current)
    }
    func resume() {
        if let receiver = dlna.current { playing = true; Task { _ = try? await dlna.soap(receiver, action: "Play", values: [("Speed", "1")]) }; return }
        guard let item = audio.currentItem else { if current != nil { start() }; return }
        if item.status == .failed { start(); return }
        do { try activateAudioSession(); audio.playImmediately(atRate: speed); playing = true; updateNowPlaying() }
        catch { playing = false; loading = false; status = error.localizedDescription; store?.message = status }
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
    func stopCasting() async { let position = elapsed; await dlna.stop(); casting = false; start(resumeAt: position) }
    func next(manual: Bool = true) {
        let reserved = nextPreparation.flatMap { $0.context == preparationContext ? $0.index : nil }
        guard let next = reserved ?? mode.nextIndex(current: index, count: queue.count, manual: manual) else { pause(); return }
        let prepared = takePreparation(at: next)
        saveProgress(); index = next; start(prepared: prepared)
    }
    func previous() { if elapsed > 3 { seek(0) } else if !queue.isEmpty { saveProgress(); index = (index - 1 + queue.count) % queue.count; start() } }
    func insertNext(_ item: MediaItem) { if queue.isEmpty { play([item]) } else { queue.insert(item, at: min(index + 1, queue.count)) } }
    func remove(_ position: Int) {
        guard queue.indices.contains(position) else { return }
        if position != index { queue.remove(at: position); if position < index { index -= 1 }; return }
        pause(); queue.remove(at: position)
        if !queue.isEmpty { index = min(index, queue.count - 1); start(); return }
        load?.cancel(); artworkTask?.cancel(); audioInfoTask?.cancel(); cacheTask?.cancel(); cancelPreparation(); noticeTask?.cancel(); bookResumeTask?.cancel(); generation = UUID(); itemObservation = nil
        audioInfo = AudioStreamInfo(); resolutionNotice = nil; resolutionSucceeded = false
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver); self.failureObserver = nil }
        audio.replaceCurrentItem(with: nil); index = 0; loading = false; elapsed = 0; duration = 0; buffered = 0; lyrics = []; lyricText = ""; status = "选择一首喜欢的音乐"
        externalMetadata = ExternalPlaybackMetadata()
        updateNowPlaying()
    }
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
        let seconds = time.seconds; if seconds.isFinite, elapsed != max(0, seconds) { elapsed = max(0, seconds) }
        if let value = audio.currentItem?.duration.seconds, value.isFinite, duration != max(0, value) { duration = max(0, value) }
        let available = audio.currentItem?.loadedTimeRanges.compactMap { $0.timeRangeValue.end.seconds }.filter(\.isFinite).max() ?? 0
        if buffered != available { buffered = available }
        if let deadline = sleepUntil, Date() >= deadline { pause(); sleepUntil = nil }
        if Int(elapsed) % 5 == 0 && lastSavedSecond != Int(elapsed) { saveProgress(); lastSavedSecond = Int(elapsed) }
        prepareNextIfNeeded(); updateNowPlaying()
    }
    private func saveBookRate() { if let current, current.kind == "episode" { UserDefaults.standard.set(speed, forKey: "speed:" + current.raw.string("_book")) } }
    private func saveProgress() { if let current { UserDefaults.standard.set(elapsed, forKey: "progress:" + current.id); if current.kind == "song" { Disk.save([current], "recent-music.json") } } }
    private func updateNowPlaying() {
        guard let current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = current.title; info[MPMediaItemPropertyArtist] = current.subtitle
        info[MPMediaItemPropertyAlbumTitle] = current.kind == "episode" ? current.subtitle : current.raw.string("albumName", "album")
        if let artwork = externalMetadata.artwork { info[MPMediaItemPropertyArtwork] = artwork }
        info[MPNowPlayingInfoPropertyExternalContentIdentifier] = current.id
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = index
        info[MPNowPlayingInfoPropertyPlaybackQueueCount] = queue.count
        info[MPMediaItemPropertyPlaybackDuration] = duration; info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed; info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? speed : 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
