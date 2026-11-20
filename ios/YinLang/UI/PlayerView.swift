import SwiftUI
import AVKit
import MediaPlayer

struct MiniPlayer: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @AppStorage("mini-lyrics") private var lyricMode = "off"
    private var active: Int? { player.lyrics.lastIndex { $0.time <= player.elapsed } }
    private var successNotice: ResolutionNotice? {
        guard let notice = player.resolutionNotice, notice.phase == .success || notice.phase == .cached else { return nil }
        return notice
    }
    var body: some View {
        if let item = player.current {
            VStack(spacing: 5) {
                HStack(spacing: 6) {
                    Button { player.toggle() } label: { Artwork(item: item, size: 48).overlay { Color.black.opacity(0.4); YLIcon(systemName: player.playing ? "pause.fill" : "play.fill", size: 24).foregroundStyle(.white) }.clipShape(RoundedRectangle(cornerRadius: 11)).contentShape(Rectangle()) }.buttonStyle(.plain).accessibilityLabel(player.playing ? "暂停" : "播放").accessibilityIdentifier("mini-play-pause").onLongPressGesture { player.showPlayer = true }
                    Button { player.showPlayer = true } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            if let notice = successNotice {
                                VStack(spacing: 3) {
                                    Text(notice.title).font(.system(size: 13, weight: .bold)).lineLimit(1)
                                    if !notice.detail.isEmpty { Text(notice.detail).font(.system(size: 11)).lineLimit(1) }
                                }.foregroundStyle(Palette.accent).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 35).accessibilityElement(children: .combine).accessibilityIdentifier("mini-success-notice")
                            } else {
                            Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1)
                            if lyricMode != "off", let active {
                                Text(player.lyrics[active].text).font(.system(size: 12)).foregroundStyle(Palette.accent).lineLimit(1)
                                if lyricMode == "double", player.lyrics.indices.contains(active + 1) { Text(player.lyrics[active + 1].text).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1) }
                            } else { Text(item.subtitle).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1) }
                            }
                            GeometryReader { proxy in Capsule().fill(Palette.soft).overlay(alignment: .leading) { Capsule().fill(Palette.accent).frame(width: proxy.size.width * min(1, player.duration > 0 ? player.elapsed / player.duration : 0)) } }.frame(height: 3).padding(.top, 1)
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

struct PlayerView: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("player-dark") private var dark = true
    @AppStorage("player-vinyl") private var vinyl = false
    @AppStorage("player-keep-screen-on") private var keepScreenOn = false
    @State private var lyrics = false
    @State private var fullLyrics = false
    @State private var menu = false
    @State private var sleep = false
    @State private var cast = false
    @State private var chooseMode = false
    init() {
#if DEBUG
        _lyrics = State(initialValue: UITestFixtures.active && UITestFixtures.screen == "lyrics")
#endif
    }
    private var ink: Color { Color(rgb: dark ? 0xfffbff : 0x291e24) }
    private var muted: Color { Color(rgb: dark ? 0xece4ef : 0x624f55) }
    private var accent: Color { Color(rgb: dark ? 0xffcedd : 0x9a3e54) }
    private var activeLine: LyricLine? { player.lyrics.last { $0.time <= player.elapsed } }
    var body: some View {
        GeometryReader { proxy in
            let compact = proxy.size.height < 560
            let landscape = proxy.size.width > proxy.size.height
            ZStack {
                Color(rgb: dark ? 0x211820 : 0xf0e6e1).ignoresSafeArea()
                PlayerBackdrop(url: player.current.flatMap(store.artwork), size: proxy.size, dark: dark).equatable().ignoresSafeArea().allowsHitTesting(false)
                VStack(spacing: 0) {
                    if !fullLyrics || landscape { Button { dismiss() } label: { Capsule().fill(muted.opacity(0.5)).frame(width: 42, height: 4).frame(width: 64, height: landscape && compact ? 28 : 48) }.accessibilityLabel("收起播放器") }
                    if landscape {
                        HStack(spacing: compact ? 16 : 28) {
                            artworkStage(maximumEdge: 520)
                                .frame(width: max(0, (proxy.size.width - (compact ? 48 : 80)) * 0.43))
                                .contentShape(Rectangle())
                                .accessibilityElement(children: .ignore).accessibilityLabel("播放器封面")
                                .accessibilityIdentifier("player-landscape-artwork")
                            VStack(spacing: 0) {
                                lyricStage(expandable: false, compact: compact)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .accessibilityElement(children: .contain)
                                    .accessibilityIdentifier("player-landscape-lyrics")
                                footer(compact: compact, landscape: true).padding(.top, compact ? 2 : 10)
                                    .accessibilityElement(children: .contain)
                                    .accessibilityIdentifier("player-landscape-controls")
                            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                        }.padding(.horizontal, compact ? 16 : 26).padding(.bottom, compact ? 4 : 12)
                    } else {
                        stage.frame(maxWidth: .infinity, maxHeight: .infinity).padding(.horizontal, 20)
                        .contentShape(Rectangle())
                        .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { value in
                            guard player.current?.kind != "episode", abs(value.translation.width) >= 42,
                                  abs(value.translation.width) > abs(value.translation.height) * 1.25 else { return }
                            withAnimation { lyrics = value.translation.width < 0; fullLyrics = false }
                        }, including: .all)
                        .accessibilityIdentifier(lyrics ? "player-lyrics-stage" : "player-artwork-stage")
                        if !fullLyrics { footer(compact: compact).padding(.horizontal, 25).padding(.top, compact ? 3 : 10).padding(.bottom, compact ? 3 : 12) }
                    }
                }.foregroundStyle(ink)
            }
        }
            .preferredColorScheme(dark ? .dark : .light)
            .onAppear { UIApplication.shared.isIdleTimerDisabled = keepScreenOn }
            .onChange(of: keepScreenOn) { _, value in UIApplication.shared.isIdleTimerDisabled = value }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
            .sheet(isPresented: $player.showQueue) { QueueView().environmentObject(store).environmentObject(player) }
            .sheet(isPresented: $menu) { PlayerMenuView().environmentObject(store).environmentObject(player) }
            .sheet(isPresented: $cast) { CastView(player: player).environmentObject(player) }
            .sheet(isPresented: $chooseMode) { PlayerChoice(title: "播放模式", rows: PlayMode.allCases.map { $0.title }, selected: player.mode.title) { value in if let mode = PlayMode.allCases.first(where: { $0.title == value }) { player.mode = mode } } }
            .sheet(isPresented: $sleep) { PlayerChoice(title: "定时关闭", rows: ["15 分钟","30 分钟","45 分钟","60 分钟","90 分钟","播完当前内容暂停","关闭定时"], selected: "") { value in if value == "播完当前内容暂停" { player.sleepAfterCurrent = true; player.sleepUntil = nil } else { player.setSleep(Int(value.components(separatedBy: " ").first ?? "")) } } }
    }
    @ViewBuilder private var stage: some View {
        if lyrics { lyricStage() }
        else { artworkStage() }
    }
    private func lyricStage(expandable: Bool = true, compact: Bool = false) -> some View {
        ScrollViewReader { reader in
                ScrollView {
                    VStack(spacing: compact ? 16 : 24) {
                        if player.lyrics.isEmpty { Text(player.lyricText.isEmpty ? (player.current?.kind == "episode" ? "有声书暂无歌词" : "暂无歌词") : player.lyricText).font(.system(size: compact ? 16 : 20)).lineSpacing(compact ? 8 : 14).frame(maxWidth: .infinity).padding(.vertical, compact ? 12 : 60) }
                        ForEach(player.lyrics) { line in Button { player.seek(line.time) } label: { Text(line.text.isEmpty ? "♪" : line.text).font(.system(size: activeLine?.id == line.id ? (compact ? 19 : 25) : (compact ? 16 : 21), weight: activeLine?.id == line.id ? .bold : .medium)).foregroundStyle(ink.opacity(activeLine?.id == line.id ? 1 : 0.35)).multilineTextAlignment(.center).frame(maxWidth: .infinity) }.buttonStyle(.plain).id(line.id) }
                    }.padding(.vertical, compact ? 12 : 50).padding(.horizontal, compact ? 8 : 24).contentShape(Rectangle())
                }.onChange(of: activeLine?.id, initial: true) { _, id in if let id { withAnimation { reader.scrollTo(id, anchor: .center) } } }.onTapGesture { if expandable { withAnimation { fullLyrics.toggle() } } }
            }
    }
    private func artworkStage(maximumEdge: CGFloat = 350) -> some View {
            GeometryReader { proxy in
                let edge = max(0, min(maximumEdge, min(proxy.size.width, proxy.size.height)))
                if let item = player.current {
                    Group {
                        if vinyl && item.kind != "episode" {
                            PlayerVinyl(item: item, edge: edge, playing: player.playing)
                        } else { Artwork(item: item, size: item.kind == "episode" ? edge * 0.77 : edge).shadow(color: .black.opacity(0.25), radius: 8, y: 5) }
                    }.position(x: proxy.size.width / 2, y: proxy.size.height / 2)
                }
            }
    }
    private func footer(compact: Bool, landscape: Bool = false) -> some View {
        let tight = landscape && compact
        return VStack(spacing: compact ? 3 : 8) {
            GeometryReader { metadata in
              HStack(spacing: 7) {
                VStack(alignment: .leading, spacing: tight ? 3 : 6) { Text(player.current?.title ?? "音浪").font(.system(size: tight ? 18 : 23, weight: .bold)).lineLimit(compact ? 1 : 2); Text(player.current?.subtitle ?? "").font(.system(size: tight ? 12 : 14)).foregroundStyle(muted).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading)
                if metadata.size.width >= 560 {
                    Color.clear.frame(width: min(300, metadata.size.width * 0.42)).overlay { ResolutionNoticeView(dark: dark, compact: true, includesSuccess: false) }
                }
                if let item = player.current, item.kind != "episode" { Button { store.toggleLike(item) } label: { YLIcon(systemName: store.isLiked(item) ? "star.fill" : "star", size: 20).frame(width: 44, height: 48) }.accessibilityLabel("收藏歌曲") }
                Button { menu = true } label: { YLIcon(systemName: "ellipsis", size: 20).frame(width: 44, height: 48) }.accessibilityLabel("播放器菜单")
              }.frame(height: metadata.size.height).overlay(alignment: .top) {
                  if metadata.size.width < 560 { ResolutionNoticeView(dark: dark, compact: true, includesSuccess: false).offset(y: -58) }
              }
            }.frame(height: tight ? 54 : 68)
            BufferedProgress(value: player.elapsed, duration: player.duration, buffered: player.buffered, color: accent, change: player.seek).frame(height: tight ? 20 : 26)
            HStack { Text(clockText(player.elapsed)); Spacer(); Text(clockText(player.duration)) }.font(.system(size: 12).monospacedDigit()).foregroundStyle(muted)
            Text(player.audioInfo.text).font(.system(size: tight ? 11 : 12, weight: .semibold)).foregroundStyle(muted).lineLimit(1).frame(maxWidth: .infinity, minHeight: tight ? 16 : 20).accessibilityIdentifier("player-audio-info")
            GeometryReader { controls in
                Button { player.previous() } label: { YLIcon(systemName: "backward.end.fill", size: tight ? 24 : compact ? 28 : 33).frame(width: tight ? 44 : compact ? 50 : 62, height: tight ? 44 : compact ? 50 : 62) }.accessibilityLabel("上一首").accessibilityIdentifier("player-previous").position(x: controls.size.width * 0.25, y: controls.size.height / 2)
                Button { player.toggle() } label: { Group { if player.loading { ProgressView().tint(ink) } else { YLIcon(systemName: player.playing ? "pause.fill" : "play.fill", size: tight ? 30 : compact ? 36 : 44) } }.frame(width: tight ? 44 : compact ? 57 : 70, height: tight ? 44 : compact ? 57 : 70) }.accessibilityLabel(player.playing ? "暂停" : "播放").position(x: controls.size.width / 2, y: controls.size.height / 2)
                Button { player.next() } label: { YLIcon(systemName: "forward.end.fill", size: tight ? 24 : compact ? 28 : 33).frame(width: tight ? 44 : compact ? 50 : 62, height: tight ? 44 : compact ? 50 : 62).contentShape(Rectangle()) }.accessibilityLabel("下一首").accessibilityIdentifier("player-next").position(x: controls.size.width * 0.75, y: controls.size.height / 2)
            }.frame(height: tight ? 44 : compact ? 57 : 70)
            if !compact { SystemVolume().tint(accent).frame(height: 28).padding(.horizontal, 10) }
            HStack(spacing: 0) {
                if player.current?.kind == "episode" { tool("gobackward.15", "后退15秒") { player.seek(player.elapsed - 15) }; tool("goforward.30", "前进30秒") { player.seek(player.elapsed + 30) } }
                else {
                    if landscape { tool(vinyl ? "photo" : "opticaldisc", vinyl ? "显示封面" : "显示旋转唱片") { vinyl.toggle() } }
                    else { tool("quote.bubble", "显示歌词") { withAnimation { lyrics.toggle() } } }
                    tool(player.mode.icon, player.mode.title) { let modes = PlayMode.allCases; if let index = modes.firstIndex(of: player.mode) { player.mode = modes[(index + 1) % modes.count] } }.onLongPressGesture { chooseMode = true }.accessibilityIdentifier("player-mode")
                }
                tool(dark ? "sun.max" : "moon", "播放器明暗") { dark.toggle() }
                tool("timer", "定时关闭") { sleep = true }
                tool("airplayaudio", "投送") { cast = true }.accessibilityIdentifier("player-cast")
                tool("list.bullet", "播放队列") { player.showQueue = true }
            }
        }.buttonStyle(.plain)
    }
    private func tool(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View { Button(action: action) { YLIcon(systemName: icon, size: 22).frame(maxWidth: .infinity).frame(height: 44) }.accessibilityLabel(label) }
}
private struct PlayerBackdrop: View, Equatable {
    let url: URL?
    let size: CGSize
    let dark: Bool
    var body: some View {
        RemoteImage(url: url) { image in image.resizable().scaledToFill().frame(width: size.width, height: size.height).clipped().blur(radius: 75).opacity(dark ? 0.35 : 0.22) } placeholder: { Color.clear }
    }
}
private struct PlayerVinyl: View {
    let item: MediaItem
    let edge: CGFloat
    let playing: Bool
    @State private var rotating = false
    var body: some View {
        ZStack {
            Circle().fill(Color(white: 0.06))
            ForEach(0..<22) { index in Circle().stroke(.white.opacity(index % 3 == 0 ? 0.12 : 0.05), lineWidth: 1).padding(CGFloat(10 + index * 4)) }
            Artwork(item: item, size: edge * 0.43).clipShape(Circle())
            Circle().fill(.black).frame(width: 13, height: 13)
        }.frame(width: edge, height: edge).rotationEffect(.degrees(rotating ? 360 : 0))
            .animation(playing ? .linear(duration: 24).repeatForever(autoreverses: false) : nil, value: rotating)
            .onAppear { rotating = playing }.onChange(of: playing) { _, value in rotating = value }
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

struct AirPlayPicker: UIViewRepresentable {
    var dark: Bool
    func makeUIView(context: Context) -> AVRoutePickerView { let view = AVRoutePickerView(); view.prioritizesVideoDevices = false; return view }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) { uiView.tintColor = dark ? .white : .darkGray; uiView.activeTintColor = .systemMint }
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
    var body: some View {
        HStack(spacing: 8) {
            SongRow(item: item, rank: position + 1, remove: remove) {
                guard offset == 0, horizontal != true, Date() >= suppressPlayUntil else { return }
                player.play(player.queue, at: position)
            }
            if position == player.index { Image(systemName: "waveform").foregroundStyle(Palette.accent).padding(.trailing, 8).accessibilityLabel("正在播放").accessibilityIdentifier("queue-current-" + item.id) }
        }.androidSurface().offset(x: offset)
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
