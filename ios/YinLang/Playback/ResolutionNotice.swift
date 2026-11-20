import SwiftUI

struct ResolutionNotice: Identifiable {
    enum Phase { case resolving, validating, success, cached, failure }
    let id = UUID()
    let phase: Phase
    let detail: String
    var title: String {
        switch phase {
        case .resolving: return "正在解析"
        case .validating: return "正在验证音源"
        case .success: return "解析成功"
        case .cached: return "缓存离线成功"
        case .failure: return "解析失败"
        }
    }
}

struct ResolutionNoticeView: View {
    @EnvironmentObject private var player: Player
    var dark = false
    var compact = false
    var includesSuccess = true
    private var textOnly: Bool { player.resolutionNotice?.phase == .success || player.resolutionNotice?.phase == .cached }
    var body: some View {
        if let notice = player.resolutionNotice, includesSuccess || !textOnly {
            HStack(spacing: 10) {
                if !textOnly {
                    if notice.phase == .resolving || notice.phase == .validating { ProgressView().tint(ink) }
                    else { Image(systemName: "exclamationmark.circle").font(.system(size: 20)) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(notice.title).font(.system(size: 13, weight: .bold))
                    if !notice.detail.isEmpty { Text(notice.detail).font(.system(size: 12, weight: .medium)).lineLimit(2) }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.foregroundStyle(ink).padding(.horizontal, compact ? 4 : 15).padding(.vertical, compact ? 4 : 9)
                .frame(maxWidth: 560)
                .background(textOnly ? Color.clear : dark ? Color(rgb: 0x352a34).opacity(0.97) : Palette.tint.opacity(0.97), in: RoundedRectangle(cornerRadius: 16))
                .shadow(color: .black.opacity(textOnly ? 0 : 0.14), radius: 8, y: 3)
                .padding(.horizontal, compact ? 0 : 16).padding(.bottom, compact ? 0 : 8)
                .accessibilityElement(children: .combine).accessibilityIdentifier("resolution-notice")
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .allowsHitTesting(false)
        }
    }
    private var ink: Color { dark ? Color(rgb: 0xffcedd) : Palette.accent }
}
