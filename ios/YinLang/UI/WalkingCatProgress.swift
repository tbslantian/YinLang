import SwiftUI
import UIKit

// The walk cycle runs on its own layer; it never drives a SwiftUI timeline or
// publishes animation ticks through Player to the surrounding screen.
struct WalkingCatProgress: View {
    let value: Double
    let duration: Double
    let buffered: Double
    let playing: Bool
    let color: Color
    var compact = false
    var interactive = true
    var change: (Double) -> Void = { _ in }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var preview: Double?
    @State private var visible = false

    private var span: Double { duration.isFinite ? max(0, duration) : 0 }
    private func fraction(_ seconds: Double) -> CGFloat {
        guard span > 0, seconds.isFinite else { return 0 }
        return CGFloat(min(1, max(0, seconds / span)))
    }
    var body: some View {
        GeometryReader { proxy in
            let edge: CGFloat = compact ? 24 : 42
            let inset = min(edge / 2, proxy.size.width / 2)
            let travel = max(0, proxy.size.width - inset * 2)
            let position = fraction(preview ?? value)
            let trackHeight: CGFloat = compact ? 3 : 6
            let baseline = proxy.size.height - trackHeight / 2
            ZStack(alignment: .topLeading) {
                Capsule().fill(color.opacity(0.15)).frame(width: travel, height: trackHeight)
                    .position(x: proxy.size.width / 2, y: baseline)
                Capsule().fill(color.opacity(0.28)).frame(width: travel * fraction(buffered), height: trackHeight)
                    .position(x: inset + travel * fraction(buffered) / 2, y: baseline)
                Capsule().fill(color).frame(width: travel * position, height: trackHeight)
                    .position(x: inset + travel * position / 2, y: baseline)
                WalkingCatSprite(walking: playing && span > 0 && preview == nil && visible && scenePhase == .active && !reduceMotion)
                    .frame(width: edge, height: edge)
                    .position(x: inset + travel * position, y: baseline - edge / 2 + 2)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }.frame(width: proxy.size.width, height: proxy.size.height)
                .contentShape(Rectangle())
                .animation(preview == nil && playing && !reduceMotion ? .linear(duration: 0.5) : nil, value: value)
                .gesture(DragGesture(minimumDistance: 0).onChanged { gesture in
                    guard interactive, span > 0 else { return }
                    preview = Double(min(1, max(0, (gesture.location.x - inset) / max(1, travel)))) * span
                }.onEnded { _ in
                    if interactive, let preview { change(preview) }
                    preview = nil
                }, including: interactive ? .all : .none)
        }
        .onAppear { visible = true }.onDisappear { visible = false }
        .accessibilityElement(children: .ignore).accessibilityLabel("播放进度")
        .accessibilityValue(clockText(preview ?? value) + " / " + clockText(span))
        .accessibilityAdjustableAction { direction in
            guard interactive, span > 0 else { return }
            change(max(0, min(span, value + (direction == .increment ? 5 : -5))))
        }
    }
}

private struct WalkingCatSprite: UIViewRepresentable {
    let walking: Bool
    func makeUIView(context: Context) -> CatSpriteView { CatSpriteView() }
    func updateUIView(_ view: CatSpriteView, context: Context) { view.setWalking(walking) }
    static func dismantleUIView(_ view: CatSpriteView, coordinator: ()) { view.setWalking(false) }
}

private final class CatSpriteView: UIView {
    private static let frames: [CGImage] = (0..<8).compactMap { UIImage(named: "progress-cat-\($0)")?.cgImage }
    private let sprite = CALayer()
    private var wantsWalking = false
    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        sprite.contentsGravity = .resizeAspect
        sprite.contents = Self.frames.first
        layer.addSublayer(sprite)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        sprite.frame = bounds
        CATransaction.commit()
    }
    override func didMoveToWindow() { super.didMoveToWindow(); updateAnimation() }
    func setWalking(_ walking: Bool) {
        wantsWalking = walking
        updateAnimation()
    }
    private func updateAnimation() {
        let animate = wantsWalking && window != nil && Self.frames.count == 8
        if animate {
            guard sprite.animation(forKey: "walk") == nil else { return }
            let walk = CAKeyframeAnimation(keyPath: "contents")
            walk.values = Self.frames
            walk.calculationMode = .discrete
            walk.duration = 0.8
            walk.repeatCount = .infinity
            sprite.add(walk, forKey: "walk")
        } else if sprite.animation(forKey: "walk") != nil {
            // Keep the current paw pose when paused instead of snapping back.
            let still = sprite.presentation()?.contents ?? Self.frames.first
            CATransaction.begin(); CATransaction.setDisableActions(true)
            sprite.contents = still
            sprite.removeAnimation(forKey: "walk")
            CATransaction.commit()
        }
    }
}
