import SwiftUI
import UIKit

// This is an app-rendered picture for Control Center's Screen Mirroring.
// Audio AirPlay metadata cannot replace Apple TV's system receiver interface.
struct TelevisionView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("player-dark") private var dark = true
    @AppStorage("player-vinyl") private var vinyl = false
    @AppStorage("television-lyrics") private var showLyrics = true
    @State private var controlsVisible = true
    @State private var controlsRevision = 0
    @State private var previousIdleTimer = false
    private var ink: Color { Color(rgb: dark ? 0xfffbff : 0x291e24) }
    private var accent: Color { Color(rgb: dark ? 0xffcedd : 0x9a3e54) }
    private var activeLineID: String? { player.lyrics.last { $0.time <= player.elapsed }?.id }

    var body: some View {
        GeometryReader { proxy in
            let wide = proxy.size.width > proxy.size.height
            let inset = min(44, max(18, proxy.size.width * 0.035))
            let font = min(36, max(20, proxy.size.height * (wide ? 0.055 : 0.03)))
            ZStack {
                Color(rgb: dark ? 0x211820 : 0xf0e6e1).ignoresSafeArea()
                PlayerBackdrop(url: player.current.flatMap(store.artwork), size: proxy.size, dark: dark).equatable().ignoresSafeArea().allowsHitTesting(false)
                VStack(spacing: 12) {
                    Color.clear.frame(height: 44)
                    if wide && showLyrics {
                        HStack(spacing: inset) {
                            cover(edge: max(0, min((proxy.size.width - inset * 3) * 0.42, proxy.size.height - 220)))
                                .frame(width: max(0, (proxy.size.width - inset * 3) * 0.44))
                            lyricStage(font: font).frame(maxWidth: .infinity, maxHeight: .infinity)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if showLyrics {
                        VStack(spacing: 18) {
                            cover(edge: min(proxy.size.width * 0.52, max(0, proxy.size.height * 0.31)))
                            lyricStage(font: font).frame(maxWidth: .infinity, maxHeight: .infinity)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        cover(edge: max(0, min(proxy.size.width - inset * 2, proxy.size.height - 220)))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    playbackProgress
                }.padding(.horizontal, inset).padding(.bottom, inset)
                VStack {
                    if controlsVisible { controls.padding(.horizontal, inset).transition(.opacity) }
                    Spacer(minLength: 0)
                }
            }.foregroundStyle(ink).contentShape(Rectangle())
                .simultaneousGesture(TapGesture().onEnded { revealControls() })
        }
        .preferredColorScheme(dark ? .dark : .light)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            previousIdleTimer = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
            player.beginTelevisionPresentation()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = previousIdleTimer
            player.endTelevisionPresentation()
        }
        .task(id: controlsRevision) {
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            withAnimation(.easeOut(duration: 0.2)) { controlsVisible = false }
        }
    }

    private func cover(edge: CGFloat) -> some View {
        VStack(spacing: 12) {
            if let item = player.current {
                if vinyl && item.kind != "episode" { PlayerVinyl(item: item, edge: edge, playing: player.playing) }
                else { Artwork(item: item, size: edge).shadow(color: .black.opacity(0.25), radius: 8, y: 5) }
                Text(item.title).font(.system(size: 23, weight: .bold)).lineLimit(2).multilineTextAlignment(.center)
                Text(item.subtitle).font(.system(size: 14)).foregroundStyle(ink.opacity(0.65)).lineLimit(1)
            } else { Text("选择一首歌曲，开始播放").font(.title3) }
        }.frame(maxWidth: .infinity)
    }

    private func lyricStage(font: CGFloat) -> some View {
        let highlightedID = activeLineID
        return ScrollViewReader { reader in
            GeometryReader { proxy in
                ScrollView {
                    VStack(spacing: font * 0.75) {
                        if player.lyrics.isEmpty {
                            Text(player.lyricText.isEmpty ? (player.current?.kind == "episode" ? "正在播放有声书" : "暂无歌词") : player.lyricText)
                                .font(.system(size: font)).lineSpacing(12).multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                        } else {
                            ForEach(player.lyrics) { line in
                                Text(line.text.isEmpty ? "♪" : line.text)
                                    .font(.system(size: font, weight: line.id == highlightedID ? .bold : .medium))
                                    .foregroundStyle(line.id == highlightedID ? accent : ink.opacity(0.35))
                                    .multilineTextAlignment(.center).frame(maxWidth: .infinity).id(line.id)
                            }
                        }
                    }.padding(.vertical, player.lyrics.isEmpty ? 0 : proxy.size.height / 2)
                }.scrollIndicators(.hidden).scrollDisabled(true)
                    .onChange(of: highlightedID, initial: true) { _, id in
                        if let id { withAnimation(.easeInOut(duration: 0.3)) { reader.scrollTo(id, anchor: .center) } }
                    }
            }
        }.clipped().allowsHitTesting(false)
    }

    private var playbackProgress: some View {
        VStack(spacing: 7) {
            GeometryReader { proxy in
                Capsule().fill(ink.opacity(0.15)).overlay(alignment: .leading) {
                    Capsule().fill(accent).frame(width: proxy.size.width * min(1, max(0, player.duration > 0 ? player.elapsed / player.duration : 0)))
                }
            }.frame(height: 3)
            HStack {
                Text(clockText(player.elapsed))
                Spacer()
                Text(player.playing ? "" : "已暂停")
                Spacer()
                Text(clockText(player.duration))
            }.font(.system(size: 12).monospacedDigit()).foregroundStyle(ink.opacity(0.65))
        }
    }

    private var controls: some View {
        HStack(spacing: 6) {
            control("xmark", "关闭电视画面") { dismiss() }
            Spacer(minLength: 0)
            if player.current?.kind != "episode" {
                control(vinyl ? "photo" : "opticaldisc", vinyl ? "显示封面" : "显示旋转唱片") { vinyl.toggle() }
                control(showLyrics ? "quote.bubble.fill" : "quote.bubble", showLyrics ? "隐藏歌词" : "显示歌词") { showLyrics.toggle() }
            }
            control("backward.end.fill", "上一首") { player.previous() }
            control(player.playing ? "pause.fill" : "play.fill", player.playing ? "暂停" : "播放") { player.toggle() }
            control("forward.end.fill", "下一首") { player.next() }
        }.padding(.top, 6).buttonStyle(.plain)
    }

    private func control(_ symbol: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button { action(); revealControls() } label: {
            YLIcon(systemName: symbol, size: 20).frame(width: 44, height: 44)
                .background(dark ? Color.black.opacity(0.2) : Color.white.opacity(0.4), in: Circle())
        }.accessibilityLabel(title)
    }
    private func revealControls() {
        withAnimation(.easeOut(duration: 0.15)) { controlsVisible = true }
        controlsRevision += 1
    }
}
