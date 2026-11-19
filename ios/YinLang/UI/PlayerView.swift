import SwiftUI
import AVKit
import MediaPlayer

struct MiniPlayer: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @State private var showToggle = true
    var body: some View {
        if let item = player.current {
            HStack(spacing: 12) {
                Button { player.toggle(); showToggle = true; Task { try? await Task.sleep(nanoseconds: 2_000_000_000); showToggle = false } } label: {
                    Artwork(item: item, size: 48).overlay { if showToggle || !player.playing { Image(systemName: player.playing ? "pause.fill" : "play.fill").foregroundStyle(.white).shadow(radius: 4).padding(10).background(.black.opacity(0.25), in: Circle()) } }
                }.accessibilityLabel(player.playing ? "暂停" : "播放").onLongPressGesture { player.showPlayer = true }
                Button { player.showPlayer = true } label: { VStack(alignment: .leading, spacing: 4) { Text(item.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1); Text(player.loading ? "正在解析…" : item.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()) }.buttonStyle(.plain)
                Button { player.showQueue = true } label: { Image(systemName: "list.bullet").font(.title3).frame(width: 38, height: 44) }.accessibilityLabel("播放队列")
            }.padding(.horizontal, 16).padding(.vertical, 10).background(.ultraThinMaterial).overlay(alignment: .top) { GeometryReader { proxy in Rectangle().fill(Palette.accent).frame(width: proxy.size.width * min(1, player.duration > 0 ? player.elapsed / player.duration : 0), height: 2) }.frame(height: 2) }
                .task(id: item.id) { showToggle = true; try? await Task.sleep(nanoseconds: 2_000_000_000); if !Task.isCancelled { showToggle = false } }
        }
    }
}

struct PlayerView: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme
    @State private var lyrics = false
    @State private var dark = true
    @AppStorage("player-vinyl") private var vinyl = false
    @State private var sleep = false
    @State private var rotation = false
    @State private var effects = false
    @State private var cast = false
    private var foreground: Color { dark ? .white : Color(red: 0.1, green: 0.15, blue: 0.14) }
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                (dark ? Color(red: 0.06, green: 0.11, blue: 0.12) : Color(red: 0.88, green: 0.92, blue: 0.88)).ignoresSafeArea()
                if let current = player.current { Artwork(item: current, size: max(proxy.size.width, proxy.size.height)).blur(radius: 90).opacity(dark ? 0.3 : 0.18).ignoresSafeArea().allowsHitTesting(false) }
                VStack(spacing: 0) {
                    HStack { Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44) }.accessibilityLabel("收起播放器"); Spacer(); Text(player.current?.kind == "episode" ? "正在收听" : "正在播放").font(.caption).tracking(3); Spacer(); playerMenu.frame(width: 44, height: 44) }.padding(.horizontal, 12)
                    if proxy.size.width > 700 && proxy.size.width > proxy.size.height {
                        HStack(spacing: 40) { stage(width: min(proxy.size.height - 120, proxy.size.width * 0.42)).frame(maxWidth: .infinity); VStack { Spacer(); metadata; controls; Spacer() }.frame(maxWidth: 500) }.padding(.horizontal, 35)
                    } else {
                        Spacer(minLength: 10)
                        stage(width: min(proxy.size.width - 64, proxy.size.height * 0.43)).frame(maxWidth: .infinity, maxHeight: .infinity)
                        Spacer(minLength: 20)
                        metadata.padding(.horizontal, 30)
                        controls.padding(.horizontal, 30).padding(.bottom, 20)
                    }
                }.foregroundStyle(foreground).frame(maxWidth: 1400).frame(maxWidth: .infinity)
            }
        }.presentationDragIndicator(.visible)
            .sheet(isPresented: $player.showQueue) { QueueView().environmentObject(store).environmentObject(player) }
            .sheet(isPresented: $effects) { EffectsView() }
            .sheet(isPresented: $cast) { CastView(player: player).environmentObject(player) }
            .confirmationDialog("睡眠定时", isPresented: $sleep, titleVisibility: .visible) {
                ForEach([15, 30, 45, 60, 90], id: \.self) { minutes in Button("\(minutes) 分钟后暂停") { player.setSleep(minutes) } }
                Button("播完当前内容暂停") { player.sleepAfterCurrent = true; player.sleepUntil = nil }
                Button("关闭定时") { player.setSleep(nil) }
            }
    }
    @ViewBuilder private func stage(width: CGFloat) -> some View {
        if lyrics {
            ScrollViewReader { reader in
                ScrollView {
                    VStack(spacing: 24) {
                        if player.lyrics.isEmpty { Text(player.lyricText.isEmpty ? "暂无歌词" : player.lyricText).font(.title3).lineSpacing(14) }
                        ForEach(player.lyrics) { line in
                            let active = activeLine?.id == line.id
                            Button { player.seek(line.time) } label: { Text(line.text.isEmpty ? "♪" : line.text).font(.system(size: active ? 25 : 21, weight: active ? .bold : .medium)).foregroundStyle(foreground.opacity(active ? 1 : 0.35)).multilineTextAlignment(.center).frame(maxWidth: .infinity) }.id(line.id).buttonStyle(.plain)
                        }
                    }.padding(.vertical, 60).padding(.horizontal, 24)
                }.onChange(of: activeLine?.id) { _, id in if let id { withAnimation { reader.scrollTo(id, anchor: .center) } } }
            }.frame(maxHeight: .infinity)
        } else if let item = player.current {
            if vinyl {
                ZStack {
                    Circle().fill(Color(white: 0.06)).shadow(color: .black.opacity(0.3), radius: 25, y: 15)
                    ForEach(0..<22) { index in Circle().stroke(.white.opacity(index % 3 == 0 ? 0.12 : 0.05), lineWidth: 1).padding(CGFloat(10 + index * 4)) }
                    Artwork(item: item, size: width * 0.43).clipShape(Circle()); Circle().fill(.black).frame(width: 13, height: 13)
                }.frame(width: width, height: width).rotationEffect(.degrees(rotation ? 360 : 0)).animation(player.playing ? .linear(duration: 24).repeatForever(autoreverses: false) : nil, value: rotation).onAppear { rotation = true }.onChange(of: player.playing) { _, playing in rotation = playing }
            } else { Artwork(item: item, size: width).shadow(color: .black.opacity(0.25), radius: 24, y: 18) }
        }
    }
    private var activeLine: LyricLine? { player.lyrics.last { $0.time <= player.elapsed } }
    private var metadata: some View {
        HStack(spacing: 15) { VStack(alignment: .leading, spacing: 8) { Text(player.current?.title ?? "音浪").font(.title2.bold()).lineLimit(2); Text(player.current?.subtitle ?? "").font(.subheadline).opacity(0.6).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading)
            Button { if let current = player.current { store.toggleLike(current) } } label: { Image(systemName: (player.current.map { store.isLiked($0) } ?? false) ? "heart.fill" : "heart").font(.title2) }.accessibilityLabel("喜欢")
        }.padding(.bottom, 20)
    }
    private var controls: some View {
        VStack(spacing: 16) {
            VStack(spacing: 7) {
                Slider(value: Binding(get: { player.elapsed }, set: { player.seek($0) }), in: 0...max(1, player.duration)).tint(foreground).accessibilityLabel("播放进度")
                HStack { Text(clockText(player.elapsed)); Spacer(); Text(clockText(player.duration)) }.font(.caption.monospacedDigit()).opacity(0.55)
                Text(player.status).font(.system(size: 10)).opacity(0.5).lineLimit(1)
            }
            HStack(spacing: 45) {
                if player.current?.kind == "episode" { Button { player.seek(player.elapsed - 15) } label: { Image(systemName: "gobackward.15").font(.title) }.accessibilityLabel("后退15秒") }
                else { Button { player.previous() } label: { Image(systemName: "backward.end.fill").font(.title) }.accessibilityLabel("上一首") }
                Button { player.toggle() } label: { ZStack { Circle().fill(foreground.opacity(0.12)).frame(width: 78, height: 78); if player.loading { ProgressView().tint(foreground) } else { Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.system(size: 32)).offset(x: player.playing ? 0 : 2) } } }.accessibilityLabel(player.playing ? "暂停" : "播放")
                if player.current?.kind == "episode" { Button { player.seek(player.elapsed + 30) } label: { Image(systemName: "goforward.30").font(.title) }.accessibilityLabel("前进30秒") }
                else { Button { player.next() } label: { Image(systemName: "forward.end.fill").font(.title) }.accessibilityLabel("下一首") }
            }.frame(maxWidth: .infinity).padding(.vertical, 5)
            HStack(spacing: 0) {
                controlButton(lyrics ? "quote.bubble.fill" : "quote.bubble", label: "歌词") { withAnimation { lyrics.toggle() } }
                Menu { ForEach(PlayMode.allCases, id: \.self) { mode in Button(mode.title) { player.mode = mode } } } label: { Image(systemName: player.mode.icon).frame(maxWidth: .infinity).frame(height: 44) }.accessibilityLabel(player.mode.title)
                controlButton(dark ? "sun.max" : "moon", label: "播放器明暗") { dark.toggle() }
                controlButton(player.sleepUntil != nil || player.sleepAfterCurrent ? "timer.circle.fill" : "timer", label: "睡眠定时") { sleep = true }
                controlButton(player.casting ? "airplayaudio.circle.fill" : "airplayaudio", label: "投送") { cast = true }
                controlButton("list.bullet", label: "播放队列") { player.showQueue = true }
            }.font(.system(size: 20))
        }
    }
    private func controlButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View { Button(action: action) { Image(systemName: icon).frame(maxWidth: .infinity).frame(height: 44) }.accessibilityLabel(label) }
    private var playerMenu: some View {
        Menu {
            if let current = player.current {
                Button(store.isLiked(current) ? "取消喜欢" : "喜欢", systemImage: "heart") { store.toggleLike(current) }
                if current.kind == "song" { Button("下载到本机", systemImage: "arrow.down.circle") { Task { await store.download(current) } } }
            }
            Toggle("旋转黑胶", isOn: $vinyl)
            Menu("播放速度") { ForEach([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3], id: \.self) { rate in Button(String(format: "%g×", rate)) { player.speed = Float(rate) } } }
            Menu("音质") { ForEach(["128k", "192k", "320k", "flac", "flac24bit", "hires", "atmos", "master"], id: \.self) { value in Button(value) { UserDefaults.standard.set(value, forKey: "quality") } } }
            Button("音效与媒体音量") { effects = true }
        } label: { Image(systemName: "ellipsis").font(.title2) }.accessibilityLabel("播放器菜单")
    }
}

struct AirPlayPicker: UIViewRepresentable {
    var dark: Bool
    func makeUIView(context: Context) -> AVRoutePickerView { let view = AVRoutePickerView(); view.prioritizesVideoDevices = false; return view }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) { uiView.tintColor = dark ? .white : .darkGray; uiView.activeTintColor = .systemMint }
}

struct SystemVolume: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { MPVolumeView(frame: .zero) }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

struct EffectsView: View {
    @StateObject private var effects = AudioEffects.shared
    @State private var name = ""
    @State private var savePreset = false
    var body: some View {
        NavigationStack {
            Form {
                Section { Toggle("启用音效", isOn: $effects.config.enabled); Button("恢复原声（保留设置）") { effects.config.enabled = false } }
                Section("十段均衡器") {
                    Menu("快速预设") { ForEach(Array(AudioEffects.presets.enumerated()), id: \.offset) { _, preset in Button(preset.0) { effects.preset(preset.1) } } }
                    ForEach(0..<10, id: \.self) { band in HStack { Text(AudioEffects.frequencies[band]).font(.caption.monospacedDigit()).frame(width: 33); Slider(value: Binding(get: { Double(effects.config.gains[band]) }, set: { effects.config.gains[band] = Float($0) }), in: -12...12, step: 0.5); Text(String(format: "%+.1f", effects.config.gains[band])).font(.caption.monospacedDigit()).frame(width: 40) } }
                }
                Section("环境与音高") {
                    Picker("环境混响", selection: $effects.config.room) { ForEach(["关闭", "小房间", "室内", "影院", "大厅", "教堂", "板式"], id: \.self) { Text($0).tag($0) } }
                    Text("干声：\(Int(effects.config.dry * 100))%"); Slider(value: $effects.config.dry, in: 0...1)
                    Text("混响干湿：\(Int(effects.config.wet * 100))% "); Slider(value: $effects.config.wet, in: 0...1)
                    Text("独立升降调：\(Int(effects.config.pitch)) 半音"); Slider(value: $effects.config.pitch, in: -12...12, step: 1)
                    Text("耳机立体声环绕：\(Int(effects.config.surround * 100))%"); Slider(value: $effects.config.surround, in: 0...1)
                }
                Section("自定义预设") {
                    Button("保存当前设置") { savePreset = true }
                    ForEach(effects.custom.keys.sorted(), id: \.self) { key in Button(key) { if let config = effects.custom[key] { effects.config = config } } }
                }
                Section("媒体音量") { SystemVolume().frame(height: 44) }
                Section { Text("音效对支持音频处理的本机音轨生效；HLS 与外部投送取决于系统输出路径。").font(.caption).foregroundStyle(.secondary) }
            }.navigationTitle("音效").alert("保存预设", isPresented: $savePreset) { TextField("名称", text: $name); Button("保存") { effects.save(name); name = "" }; Button("取消", role: .cancel) {} }
        }
    }
}

struct QueueView: View {
    @EnvironmentObject var player: Player
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack { List {
            ForEach(Array(player.queue.enumerated()), id: \.offset) { position, item in HStack { SongRow(item: item) { player.play(player.queue, at: position) }; if position == player.index { Image(systemName: "waveform").foregroundStyle(Palette.accent) } } }
                .onDelete { indices in for index in indices.sorted(by: >) { player.remove(index) } }
        }.navigationTitle("播放队列（\(player.queue.count)）").toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } } }
    }
}
