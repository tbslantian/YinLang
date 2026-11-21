import SwiftUI
import AVKit
import MediaPlayer
import QuartzCore

struct MiniPlayer: View {
    @EnvironmentObject private var player: Player
    var body: some View { MiniPlayerContent(clock: player.progress) }
}

private struct MiniPlayerContent: View {
    @ObservedObject var clock: PlayerProgressClock
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @AppStorage("mini-lyrics") private var lyricMode = "off"
    private var active: Int? { player.lyrics.lastIndex { $0.time <= clock.elapsed } }
    var body: some View {
        if let item = player.current {
            VStack(spacing: 5) {
                HStack(spacing: 6) {
                    MiniPlayerCover(item: item)
                    Button { player.showPlayer = true } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            if player.resolutionNotice != nil {
                                ResolutionNoticeView().frame(maxWidth: .infinity, minHeight: 35)
                            } else {
                            Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1)
                            if lyricMode != "off", let active {
                                Text(player.lyrics[active].text).font(.system(size: 12)).foregroundStyle(Palette.accent).lineLimit(1)
                                if lyricMode == "double", player.lyrics.indices.contains(active + 1) { Text(player.lyrics[active + 1].text).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1) }
                            } else { Text(item.subtitle).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1) }
                            }
                            GeometryReader { proxy in Capsule().fill(Palette.soft).overlay(alignment: .leading) { Capsule().fill(Palette.accent).frame(width: proxy.size.width * min(1, clock.duration > 0 ? clock.elapsed / clock.duration : 0)) } }.frame(height: 3).padding(.top, 1)
                        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("mini-open-player")
                    HStack(spacing: 0) {
                        Button { player.previous() } label: { YLIcon(systemName: "backward.end.fill", size: 21).frame(width: 44, height: 48).contentShape(Rectangle()) }.accessibilityLabel("上一首").accessibilityIdentifier("mini-previous")
                        Button { player.next() } label: { YLIcon(systemName: "forward.end.fill", size: 21).frame(width: 44, height: 48).contentShape(Rectangle()) }.accessibilityLabel("下一首").accessibilityIdentifier("mini-next")
                        Button { player.showQueue = true } label: { YLIcon(systemName: "list.bullet", size: 21).frame(width: 44, height: 48).contentShape(Rectangle()) }.accessibilityLabel("播放队列").accessibilityIdentifier("mini-queue")
                    }.buttonStyle(.plain).foregroundStyle(Palette.accent)
                }.padding(.horizontal, 8).padding(.vertical, 6).background(Palette.paper.opacity(0.95), in: RoundedRectangle(cornerRadius: 30)).overlay { RoundedRectangle(cornerRadius: 30).stroke(.white.opacity(0.25), lineWidth: 1) }.shadow(color: .black.opacity(0.1), radius: 6, y: 2)
            }.padding(.horizontal, 14).padding(.bottom, 10).padding(.top, 5)
        }
    }
}

private struct MiniPlayerCover: View {
    @EnvironmentObject var player: Player
    let item: MediaItem
    @State private var coverControlVisible = true
    @State private var coverControlRevision = 0
    var body: some View {
        Button {
            player.toggle()
            coverControlVisible = true
            coverControlRevision += 1
        } label: {
            Artwork(item: item, size: 48).overlay {
                if coverControlVisible {
                    Color.black.opacity(0.4)
                    YLIcon(systemName: player.playing ? "pause.fill" : "play.fill", size: 24).foregroundStyle(.white)
                }
            }.clipShape(RoundedRectangle(cornerRadius: 11)).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(player.playing ? "暂停" : "播放").accessibilityIdentifier("mini-play-pause").onLongPressGesture { player.showPlayer = true }
            .task(id: coverControlRevision) {
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
                withAnimation(.easeOut(duration: 0.18)) { coverControlVisible = false }
            }
    }
}

struct PlayerView: View {
    @EnvironmentObject private var player: Player
    var body: some View { PlayerScreen(player: player) }
}

// Progress has its own publisher, so no identity-only equality gate is needed.
// Track changes must reach both the native artwork and SwiftUI metadata views.
private struct PlayerScreen: View {
    let engine: Player
    @StateObject private var state: PlayerPresentationState
    @StateObject private var artworkSource = PlayerArtworkSource()
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("player-dark") private var dark = true
    @AppStorage("player-vinyl") private var vinyl = false
    @AppStorage("player-keep-screen-on") private var keepScreenOn = false
    @State private var lyrics = false
    @State private var fullLyrics = false
    @State private var landscapeControlsHidden = false
    @State private var footerHeight: CGFloat = 0
    @State private var menu = false
    @State private var sleep = false
    @State private var cast = false
    @State private var chooseMode = false
    @State private var qualityItem: MediaItem?
    init(player: Player) {
        engine = player; _state = StateObject(wrappedValue: PlayerPresentationState(player))
#if DEBUG
        _lyrics = State(initialValue: UITestFixtures.active && UITestFixtures.screen == "lyrics")
#endif
    }
    private var ink: Color { Color(rgb: dark ? 0xfffbff : 0x291e24) }
    private var muted: Color { Color(rgb: dark ? 0xece4ef : 0x624f55) }
    private var accent: Color { Color(rgb: dark ? 0xffcedd : 0x9a3e54) }
    private var controlsAnimation: Animation? { reduceMotion ? nil : .easeInOut(duration: 0.22) }
    var body: some View {
        // Capture the value before entering deferred layout closures. Artwork,
        // labels and actions all render the same current-song snapshot.
        let current = state.current
        return GeometryReader { proxy in
            let compact = proxy.size.height < 560
            let wide = proxy.size.width > proxy.size.height
            let hidden = wide ? landscapeControlsHidden : fullLyrics
            let handleHeight: CGFloat = wide && compact ? 28 : 48
            let horizontal: CGFloat = wide ? (compact ? 16 : 26) : 20
            let bottom: CGFloat = wide ? (compact ? 4 : 12) : (compact ? 3 : 12)
            let height = max(0, proxy.size.height - handleHeight - bottom)
            let artWidth = max(0, wide ? (proxy.size.width - (compact ? 48 : 80)) * 0.43 : proxy.size.width - 40)
            let gap: CGFloat = wide ? (compact ? 16 : 28) : 0
            let lyricX = wide ? horizontal + artWidth + gap : horizontal
            let lyricWidth = max(0, wide ? proxy.size.width - lyricX - horizontal : proxy.size.width - horizontal * 2)
            let artHeight = max(0, wide ? height : height - footerHeight)
            let edge = max(0, min(wide ? 520 : 350, min(artWidth, artHeight)))
            let artVisible = current != nil && (wide || !lyrics)
            let lyricVisible = wide || lyrics
            let url = artworkSource.url(for: current, store: store)
            ZStack(alignment: .topLeading) {
                Color(rgb: dark ? 0x211820 : 0xf0e6e1).ignoresSafeArea()
                PlayerBackdrop(url: url, size: proxy.size, dark: dark).equatable().ignoresSafeArea().allowsHitTesting(false)
                // Stable identities across full-screen, artwork/lyrics, record/
                // cover and orientation changes. Only actual size/content
                // changes require the native surfaces to measure again.
                PlayerArtworkSurface(url: url, book: current?.kind == "episode", vinyl: vinyl,
                                     playing: state.playing, active: artVisible && scenePhase == .active)
                    .frame(width: edge, height: edge)
                    .position(x: horizontal + artWidth / 2, y: handleHeight + artHeight / 2)
                    .opacity(artVisible && footerHeight > 0 ? 1 : 0).animation(controlsAnimation, value: artVisible)
                    .allowsHitTesting(false).accessibilityHidden(!artVisible)
                    .accessibilityLabel("播放器封面").accessibilityIdentifier(wide ? "player-landscape-artwork" : "player-artwork-stage")
                NativePlayerLyrics(player: engine, compact: compact, ink: UIColor(ink),
                    visibleHeight: hidden ? height : max(0, height - footerHeight), active: lyricVisible && footerHeight > 0 && scenePhase == .active,
                    landscape: wide, emptyText: current?.kind == "episode" ? "有声书暂无歌词" : "暂无歌词") {
                        if wide { landscapeControlsHidden.toggle() } else { fullLyrics.toggle() }
                    }
                    .frame(width: lyricWidth, height: height)
                    .position(x: lyricX + lyricWidth / 2, y: handleHeight + height / 2)
                    .opacity(lyricVisible && footerHeight > 0 ? 1 : 0)
                    .animation(controlsAnimation, value: lyricVisible)
                    .allowsHitTesting(lyricVisible).accessibilityHidden(!lyricVisible)
                    .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { value in
                        guard !wide, current?.kind != "episode", abs(value.translation.width) >= 42,
                              abs(value.translation.width) > abs(value.translation.height) * 1.25 else { return }
                        lyrics = value.translation.width < 0; fullLyrics = false
                    })
                    .accessibilityIdentifier(wide ? "player-landscape-lyrics" : "player-lyrics-stage")
                // A gesture surface is kept separate from the native artwork.
                // In portrait lyrics mode the native scroll view handles taps.
                Color.clear.frame(width: wide ? artWidth : lyricWidth, height: artHeight)
                    .contentShape(Rectangle())
                    .position(x: horizontal + artWidth / 2, y: handleHeight + artHeight / 2)
                    .allowsHitTesting(!wide && !lyrics)
                    .gesture(DragGesture(minimumDistance: 20).onEnded { value in
                        guard current?.kind != "episode", abs(value.translation.width) >= 42,
                              abs(value.translation.width) > abs(value.translation.height) * 1.25 else { return }
                        lyrics = value.translation.width < 0; fullLyrics = false
                    })
                footer(current: current, compact: compact, landscape: wide)
                    .padding(.top, compact ? (wide ? 2 : 3) : 10)
                    .padding(.bottom, bottom)
                    .frame(width: max(0, wide ? lyricWidth : proxy.size.width - 50))
                    .fixedSize(horizontal: false, vertical: true)
                    .background { GeometryReader { controls in Color.clear.preference(key: PlayerFooterHeightKey.self, value: controls.size.height) } }
                    .position(x: wide ? lyricX + lyricWidth / 2 : proxy.size.width / 2,
                              y: proxy.size.height - footerHeight / 2)
                    .opacity(hidden || footerHeight <= 0 ? 0 : 1).offset(y: hidden ? 10 : 0)
                    .animation(controlsAnimation, value: hidden)
                    .allowsHitTesting(!hidden).accessibilityHidden(hidden)
                    .accessibilityIdentifier(wide ? "player-landscape-controls" : "player-controls")
                Button { dismiss() } label: {
                    Capsule().fill(muted.opacity(0.5)).frame(width: 42, height: 4).frame(width: 64, height: handleHeight)
                }.position(x: proxy.size.width / 2, y: handleHeight / 2)
                    .opacity(hidden ? 0 : 1).offset(y: hidden ? -8 : 0)
                    .animation(controlsAnimation, value: hidden).allowsHitTesting(!hidden).accessibilityHidden(hidden)
                    .accessibilityLabel("收起播放器")
                PlayerNotice(player: engine, color: accent).frame(width: min(340, lyricWidth))
                    .position(x: lyricX + lyricWidth / 2, y: proxy.size.height - bottom - 28)
                    .opacity(hidden ? 1 : 0).allowsHitTesting(false).accessibilityHidden(!hidden)
            }.foregroundStyle(ink)
                .onPreferenceChange(PlayerFooterHeightKey.self) { height in
                    if height > 0, abs(height - footerHeight) > 0.5 { footerHeight = height }
                }
                .statusBarHidden(wide)
                .onChange(of: wide) { _, _ in landscapeControlsHidden = false; fullLyrics = false }
                .onChange(of: vinyl) { _, _ in if !wide { lyrics = false; fullLyrics = false } }
        }
        .preferredColorScheme(dark ? .dark : .light)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = keepScreenOn }
        .onChange(of: keepScreenOn) { _, value in UIApplication.shared.isIdleTimerDisabled = value }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .sheet(isPresented: Binding(get: { state.showQueue }, set: { engine.showQueue = $0 })) { QueueView().environmentObject(store).environmentObject(engine) }
        .sheet(isPresented: $menu) { PlayerMenuView().environmentObject(store).environmentObject(engine) }
        .sheet(isPresented: $cast) { CastView(player: engine).environmentObject(store).environmentObject(engine) }
        .sheet(item: $qualityItem) { item in PlayerQualityChoice(item: item).environmentObject(store).environmentObject(engine) }
        .sheet(isPresented: $chooseMode) { PlayerChoice(title: "播放模式", rows: PlayMode.allCases.map { $0.title }, selected: state.mode.title) { value in if let mode = PlayMode.allCases.first(where: { $0.title == value }) { engine.mode = mode } } }
        .sheet(isPresented: $sleep) { PlayerChoice(title: "定时关闭", rows: ["15 分钟","30 分钟","45 分钟","60 分钟","90 分钟","播完当前内容暂停","关闭定时"], selected: "") { value in if value == "播完当前内容暂停" { engine.sleepAfterCurrent = true; engine.sleepUntil = nil } else { engine.setSleep(Int(value.components(separatedBy: " ").first ?? "")) } } }
    }
    private func footer(current: MediaItem?, compact: Bool, landscape: Bool = false) -> some View {
        let tight = landscape && compact
        let playing = state.playing
        let loading = state.loading
        return VStack(spacing: compact ? 3 : 8) {
            PlayerMetadataHeader(engine: engine, state: state, compact: compact, tight: tight,
                                 muted: muted, accent: accent) { menu = true }
            PlayerProgressSection(player: engine, color: accent, muted: muted, tight: tight, compact: compact)
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                Button { qualityItem = current } label: {
                    Text(state.audioInfo.text).font(.system(size: tight ? 11 : 12, weight: .semibold)).foregroundStyle(muted).lineLimit(1)
                        .frame(minHeight: tight ? 16 : 20).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(!PlayerQualityChoice.canSelect(current))
                    .accessibilityLabel("音质 " + state.audioInfo.text).accessibilityHint("选择播放音质").accessibilityIdentifier("player-audio-info")
                Spacer(minLength: 0)
            }.frame(minHeight: tight ? 16 : 20)
            GeometryReader { controls in
                // Offset fixed-size controls from the center. position() wraps
                // its child in a full-size frame; no wrapper should be tappable.
                ZStack {
                    trackButton("backward.end.fill", "上一首", "player-previous", size: tight ? 24 : compact ? 28 : 33) { engine.previous() }
                        .offset(x: -controls.size.width / 4)
                    Button { engine.toggle() } label: { Group { if loading { ProgressView().tint(ink) } else { YLIcon(systemName: playing ? "pause.fill" : "play.fill", size: tight ? 30 : compact ? 36 : 44) } }.frame(width: tight ? 44 : compact ? 57 : 70, height: tight ? 44 : compact ? 57 : 70) }.accessibilityLabel(playing ? "暂停" : "播放")
                    trackButton("forward.end.fill", "下一首", "player-next", size: tight ? 24 : compact ? 28 : 33) { engine.next() }
                        .offset(x: controls.size.width / 4)
                }.frame(width: controls.size.width, height: controls.size.height)
            }.frame(height: tight ? 44 : compact ? 57 : 70)
            if !compact || state.airPlayActive { SystemVolume().tint(accent).frame(height: 28).padding(.horizontal, 10) }
            HStack(spacing: 0) {
                if current?.kind == "episode" { tool("gobackward.15", "后退15秒") { engine.seek(engine.elapsed - 15) }; tool("goforward.30", "前进30秒") { engine.seek(engine.elapsed + 30) } }
                else {
                    if landscape { tool(vinyl ? "photo" : "opticaldisc", vinyl ? "显示封面" : "显示旋转唱片") { vinyl.toggle() } }
                    else { tool("quote.bubble", "显示歌词") { lyrics.toggle(); if !lyrics { fullLyrics = false } } }
                    tool(state.mode.icon, state.mode.title) { let modes = PlayMode.allCases; if let index = modes.firstIndex(of: engine.mode) { engine.mode = modes[(index + 1) % modes.count] } }.onLongPressGesture { chooseMode = true }.accessibilityIdentifier("player-mode")
                }
                tool(dark ? "sun.max" : "moon", "播放器明暗") { dark.toggle() }
                tool("timer", "定时关闭") { sleep = true }
                if state.casting {
                    tool("hifispeaker", "管理 DLNA 投送") { cast = true }.accessibilityIdentifier("player-cast")
                } else {
                    PlayerRoutePicker(player: engine, dark: dark, tintColor: UIColor(accent)).frame(maxWidth: .infinity).frame(height: 44)
                        .accessibilityLabel("AirPlay 投送").accessibilityIdentifier("player-cast")
                }
                tool("list.bullet", "播放队列") { engine.showQueue = true }
            }
        }.buttonStyle(.plain)
    }
    private func trackButton(_ icon: String, _ label: String, _ identifier: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) { YLIcon(systemName: icon, size: size).contentShape(Rectangle()) }
            .buttonStyle(.plain).frame(width: size, height: size).clipped().contentShape(.interaction, Rectangle())
            .accessibilityLabel(label).accessibilityIdentifier(identifier)
    }
    private func tool(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View { Button(action: action) { YLIcon(systemName: icon, size: 22).frame(maxWidth: .infinity).frame(height: 44) }.accessibilityLabel(label) }
}
// Subscribe at the text's own boundary instead of relying on a parent layout
// closure to notice mutations of a referenced state object. Only the metadata
// header refreshes; the volume, AirPlay and progress controls retain identity.
private struct PlayerMetadataHeader: View {
    let engine: Player
    @ObservedObject var state: PlayerPresentationState
    @EnvironmentObject private var store: AppStore
    let compact: Bool
    let tight: Bool
    let muted: Color
    let accent: Color
    let showMenu: () -> Void
    var body: some View {
        let current = state.current
        let title = current?.title ?? "音浪"
        let subtitle = current?.subtitle ?? ""
        let liked = current.map(store.isLiked) ?? false
        return GeometryReader { metadata in
            HStack(spacing: 7) {
                VStack(alignment: .leading, spacing: tight ? 3 : 6) {
                    Text(title).font(.system(size: tight ? 18 : 23, weight: .bold)).lineLimit(compact ? 1 : 2)
                        .accessibilityIdentifier("player-track-title")
                    Text(subtitle).font(.system(size: tight ? 12 : 14)).foregroundStyle(muted).lineLimit(1)
                        .accessibilityIdentifier("player-track-artist")
                }.frame(maxWidth: .infinity, alignment: .leading)
                if metadata.size.width >= 560 {
                    Color.clear.frame(width: min(300, metadata.size.width * 0.42)).overlay {
                        PlayerNotice(player: engine, color: accent)
                    }
                }
                if let current, current.kind != "episode" {
                    Button { store.toggleLike(current) } label: {
                        YLIcon(systemName: liked ? "star.fill" : "star", size: 20).frame(width: 44, height: 48)
                    }.accessibilityLabel("收藏歌曲")
                }
                Button(action: showMenu) {
                    YLIcon(systemName: "ellipsis", size: 20).frame(width: 44, height: 48)
                }.accessibilityLabel("播放器菜单")
            }.frame(height: metadata.size.height).overlay(alignment: .top) {
                if metadata.size.width < 560 {
                    PlayerNotice(player: engine, color: accent).frame(maxWidth: 340).offset(y: -52)
                }
            }
        }.frame(height: tight ? 54 : 68)
    }
}

private struct PlayerFooterHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

struct PlayerVinyl: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var artworkSource = PlayerArtworkSource()
    let item: MediaItem
    let edge: CGFloat
    let playing: Bool
    var body: some View {
        PlayerArtworkSurface(url: artworkSource.url(for: item, store: store), book: item.kind == "episode", vinyl: true,
                             playing: playing, active: scenePhase == .active).frame(width: edge, height: edge)
    }
}

struct BufferedProgress: View {
    var value: Double
    var duration: Double
    var buffered: Double
    var color: Color
    var change: (Double) -> Void
    @State private var preview: Double?
    var body: some View {
        GeometryReader { proxy in
            let span = max(1, duration)
            let progress = min(1, max(0, (preview ?? value) / span))
            ZStack(alignment: .leading) {
                Capsule().fill(color.opacity(0.15)).frame(height: 7)
                Capsule().fill(color.opacity(0.28)).frame(width: proxy.size.width * min(1, max(0, buffered / span)), height: 7)
                Capsule().fill(color).frame(width: proxy.size.width * progress, height: 7)
                Circle().fill(color).frame(width: 14, height: 14).offset(x: max(0, (proxy.size.width - 14) * progress))
            }.frame(height: proxy.size.height).contentShape(Rectangle()).gesture(DragGesture(minimumDistance: 0).onChanged { preview = min(1, max(0, $0.location.x / max(1, proxy.size.width))) * span }.onEnded { _ in if let preview { change(preview) }; preview = nil })
        }.accessibilityElement().accessibilityLabel("播放进度").accessibilityValue(clockText(value) + " / " + clockText(duration)).accessibilityAdjustableAction { direction in change(max(0, min(duration, value + (direction == .increment ? 5 : -5)))) }
    }
}
struct PlayerChoice: View {
    @Environment(\.dismiss) private var dismiss
    var title: String
    var rows: [String]
    var selected: String
    var choose: (String) -> Void
    var body: some View { NavigationStack { AndroidList { ForEach(rows, id: \.self) { value in Button { choose(value); dismiss() } label: { HStack(spacing: 0) { Text(value == selected ? "✓" : "").font(.system(size: 23, weight: .bold)).foregroundStyle(Palette.accent).frame(width: 40); Text(value).font(.system(size: 18)).foregroundStyle(Palette.ink); Spacer() }.padding(.horizontal, 10).padding(.vertical, 18).contentShape(Rectangle()) } } }.androidPage(title, category: "") }.presentationDetents([.medium, .large]).presentationCornerRadius(30).presentationDragIndicator(.visible) }
}

struct AirPlayPicker: View {
    @EnvironmentObject private var player: Player
    var dark: Bool
    var tintColor: UIColor? = nil
    var body: some View { PlayerRoutePicker(player: player, dark: dark, tintColor: tintColor) }
}

struct PlayerRoutePicker: UIViewRepresentable {
    let player: Player
    var dark: Bool
    var tintColor: UIColor? = nil
    func makeCoordinator() -> Coordinator { Coordinator(player: player) }
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView(); view.prioritizesVideoDevices = false; view.delegate = context.coordinator; return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        context.coordinator.player = player; uiView.tintColor = tintColor ?? (dark ? .white : .darkGray); uiView.activeTintColor = .systemMint
    }
    @MainActor
    final class Coordinator: NSObject, AVRoutePickerViewDelegate {
        var player: Player
        init(player: Player) { self.player = player }
        func routePickerViewWillBeginPresentingRoutes(_ routePickerView: AVRoutePickerView) { player.prepareAirPlayRoutePicker() }
        func routePickerViewDidEndPresentingRoutes(_ routePickerView: AVRoutePickerView) { player.refreshAirPlayRoute() }
    }
}

struct SystemVolume: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { let view = MPVolumeView(frame: .zero); view.showsRouteButton = false; return view }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

struct EffectsView: View {
    @StateObject private var effects = AudioEffects.shared
    @State private var name = ""
    @State private var savePreset = false
    @State private var presetName = ""
    var body: some View {
        NavigationStack {
            AndroidForm {
                AndroidSection { Toggle("启用音效", isOn: $effects.config.enabled); Button("恢复原声（保留设置）") { effects.config.enabled = false } }
                AndroidSection("十段均衡器") {
                    AndroidChoice(title: "快速预设", selection: $presetName, options: AudioEffects.presets.map { ($0.0,$0.0) }).onChange(of: presetName) { _, value in if let preset = AudioEffects.presets.first(where: { $0.0 == value }) { effects.preset(preset.1) } }
                    ForEach(0..<10, id: \.self) { band in HStack { Text(AudioEffects.frequencies[band]).font(.caption.monospacedDigit()).frame(width: 33); Slider(value: Binding(get: { Double(effects.config.gains[band]) }, set: { effects.config.gains[band] = Float($0) }), in: -12...12, step: 0.5); Text(String(format: "%+.1f", effects.config.gains[band])).font(.caption.monospacedDigit()).frame(width: 40) } }
                }
                AndroidSection("环境与音高") {
                    AndroidChoice(title: "环境混响", selection: $effects.config.room, options: ["关闭", "小房间", "室内", "影院", "大厅", "教堂", "板式"].map { ($0,$0) })
                    Text("干声：\(Int(effects.config.dry * 100))%"); Slider(value: $effects.config.dry, in: 0...1)
                    Text("混响干湿：\(Int(effects.config.wet * 100))% "); Slider(value: $effects.config.wet, in: 0...1)
                    Text("独立升降调：\(Int(effects.config.pitch)) 半音"); Slider(value: $effects.config.pitch, in: -12...12, step: 1)
                    Text("耳机立体声环绕：\(Int(effects.config.surround * 100))%"); Slider(value: $effects.config.surround, in: 0...1)
                }
                AndroidSection("自定义预设") {
                    Button("保存当前设置") { savePreset = true }
                    ForEach(effects.custom.keys.sorted(), id: \.self) { key in Button(key) { if let config = effects.custom[key] { effects.config = config } } }
                }
                AndroidSection("媒体音量") { SystemVolume().frame(height: 44) }
                AndroidSection { Text("音效对支持音频处理的本机音轨生效；HLS 与外部投送取决于系统输出路径。").font(.caption).foregroundStyle(Palette.muted) }
            }.androidPage("音效").alert("保存预设", isPresented: $savePreset) { TextField("名称", text: $name); Button("保存") { effects.save(name); name = "" }; Button("取消", role: .cancel) {} }
        }
    }
}

struct QueueView: View {
    @EnvironmentObject var player: Player
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack { AndroidList {
            ForEach(Array(player.queue.enumerated()), id: \.offset) { position, item in QueueSwipeRow(item: item, position: position) }

        }.androidPage("播放队列（\(player.queue.count)）") { Button("完成") { dismiss() }.font(.system(size: 14, weight: .bold)) } }.presentationDetents([.medium, .large]).presentationCornerRadius(30).presentationDragIndicator(.visible)
    }
}

private struct QueueSwipeRow: View {
    @EnvironmentObject private var player: Player
    let item: MediaItem
    let position: Int
    @State private var offset: CGFloat = 0
    @State private var dragOrigin: CGFloat = 0
    @State private var horizontal: Bool?
    @State private var suppressPlayUntil = Date.distantPast
    private let actionWidth: CGFloat = 88
    private var highlighted: Bool { SongPlaybackHighlight.matches(item, player: player, queuePosition: position) }
    var body: some View {
        HStack(spacing: 8) {
            SongRow(item: item, rank: position + 1, queuePosition: position, remove: remove) {
                guard offset == 0, horizontal != true, Date() >= suppressPlayUntil else { return }
                player.play(player.queue, at: position)
            }
            if position == player.index { Image(systemName: "waveform").foregroundStyle(Palette.accent).padding(.trailing, 8).accessibilityLabel("正在播放").accessibilityIdentifier("queue-current-" + item.id) }
        }.songPlaybackSurface(highlighted: highlighted, marker: false).offset(x: offset)
            .background(alignment: .trailing) {
                Button(action: remove) {
                    VStack(spacing: 5) { YLIcon(systemName: "trash", size: 20); Text("移除").font(.system(size: 13, weight: .semibold)) }
                        .foregroundStyle(.white).frame(width: actionWidth).frame(maxHeight: .infinity).background(Color.red).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel("从队列移除 " + item.title).accessibilityIdentifier("queue-remove-\(position)")
                    .opacity(offset < 0 ? 1 : 0).allowsHitTesting(offset < 0).accessibilityHidden(offset >= 0)
            }
            .clipShape(RoundedRectangle(cornerRadius: 16)).contentShape(Rectangle())
            // AndroidList is a ScrollView; native List swipeActions do not apply.
            .simultaneousGesture(DragGesture(minimumDistance: 18).onChanged { value in
                if horizontal == nil {
                    horizontal = abs(value.translation.width) > abs(value.translation.height) * 1.25
                    dragOrigin = offset
                }
                guard horizontal == true else { return }
                suppressPlayUntil = Date().addingTimeInterval(0.5)
                offset = min(0, max(-actionWidth, dragOrigin + value.translation.width))
            }.onEnded { value in
                defer { horizontal = nil }
                guard horizontal == true else { return }
                suppressPlayUntil = Date().addingTimeInterval(0.5)
                withAnimation(.easeOut(duration: 0.2)) { offset = dragOrigin + value.translation.width < -actionWidth / 2 ? -actionWidth : 0 }
            })
            .onChange(of: item.id) { _, _ in reset() }
            .onChange(of: player.queue.count) { _, _ in reset() }
            .accessibilityElement(children: .contain).accessibilityIdentifier("queue-row-\(position)")
            .accessibilityAction(named: Text("从队列移除"), remove)
    }
    private func reset() { offset = 0; horizontal = nil; dragOrigin = 0 }
    private func remove() { reset(); player.remove(position) }
}
