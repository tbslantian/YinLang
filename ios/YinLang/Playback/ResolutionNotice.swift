import SwiftUI

struct ResolutionNotice: Identifiable {
    enum Phase { case resolving, validating, success, failure }
    let id = UUID()
    let phase: Phase
    let detail: String
    var title: String {
        switch phase {
        case .resolving: return "正在解析"
        case .validating: return "正在验证音源"
        case .success: return "解析成功"
        case .failure: return "解析失败"
        }
    }
}

struct ResolutionNoticeView: View {
    @EnvironmentObject private var player: Player
    var dark = false
    var body: some View {
        if let notice = player.resolutionNotice {
            HStack(spacing: 10) {
                if notice.phase == .resolving || notice.phase == .validating { ProgressView().tint(ink) }
                else { Image(systemName: notice.phase == .success ? "checkmark.circle" : "exclamationmark.circle").font(.system(size: 20)) }
                VStack(alignment: .leading, spacing: 3) {
                    Text(notice.title).font(.system(size: 13, weight: .bold))
                    if !notice.detail.isEmpty { Text(notice.detail).font(.system(size: 12, weight: .medium)).lineLimit(2) }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.foregroundStyle(ink).padding(.horizontal, 15).padding(.vertical, 9)
                .frame(maxWidth: 560)
                .background(dark ? Color(rgb: 0x352a34).opacity(0.97) : Palette.tint.opacity(0.97), in: RoundedRectangle(cornerRadius: 16))
                .shadow(color: .black.opacity(0.14), radius: 8, y: 3)
                .padding(.horizontal, 16).padding(.bottom, 8)
                .accessibilityElement(children: .combine).accessibilityIdentifier("resolution-notice")
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .allowsHitTesting(false)
        }
    }
    private var ink: Color { dark ? Color(rgb: 0xffcedd) : Palette.accent }
}
