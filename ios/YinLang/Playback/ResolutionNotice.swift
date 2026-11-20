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
    var color: Color = Palette.accent
    var body: some View {
        if let notice = player.resolutionNotice {
            VStack(spacing: 3) {
                HStack(spacing: 5) {
                    if notice.phase == .resolving || notice.phase == .validating { ProgressView().controlSize(.mini).tint(color) }
                    Text(notice.title).font(.system(size: 13, weight: .bold)).lineLimit(1)
                }.frame(maxWidth: .infinity)
                if !notice.detail.isEmpty { Text(notice.detail).font(.system(size: 11)).lineLimit(1) }
            }.foregroundStyle(color).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine).accessibilityIdentifier("resolution-notice")
                .allowsHitTesting(false)
        }
    }
}
