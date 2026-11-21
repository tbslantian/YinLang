import SwiftUI
import Combine
import UIKit

struct NativePlayerLyrics: UIViewRepresentable {
    let player: Player
    let compact: Bool
    let ink: UIColor
    let visibleHeight: CGFloat
    let active: Bool
    let landscape: Bool
    let emptyText: String
    let toggle: () -> Void
    func makeUIView(context: Context) -> PlayerLyricsCanvas {
        let view = PlayerLyricsCanvas(frame: .zero); view.bind(player); return view
    }
    func updateUIView(_ view: PlayerLyricsCanvas, context: Context) {
        view.configure(compact: compact, ink: ink, visibleHeight: visibleHeight, active: active,
                       landscape: landscape, emptyText: emptyText, toggle: toggle)
    }
    static func dismantleUIView(_ view: PlayerLyricsCanvas, coordinator: ()) { view.stop() }
}

// One retained UIScrollView in both orientations. Text frames are measured only
// for new lyrics, font mode or width; a timestamp updates at most two labels.
final class PlayerLyricsCanvas: UIView, UIScrollViewDelegate {
    private let viewport = UIView()
    private let scroll = UIScrollView()
    private var labels: [PlayerLyricLabel] = []
    private let fallback = UILabel()
    private var subscriptions = Set<AnyCancellable>()
    private weak var player: Player?
    private var lines: [LyricLine] = []
    private var plainText = ""
    private var emptyText = "暂无歌词"
    private var position = 0.0
    private var highlighted = -1
    private var compact = false
    private var ink = UIColor.white
    private var active = false
    private var landscape = false
    private var visibleHeight: CGFloat = 0
    private var measuredSize = CGSize.zero
    private var needsTextLayout = true
    private var motion: UIViewPropertyAnimator?
    private var resumeFollowing: DispatchWorkItem?
    private var manualUntil = 0.0
    private var toggle: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear; viewport.backgroundColor = .clear; viewport.clipsToBounds = true
        addSubview(viewport); viewport.addSubview(scroll)
        scroll.backgroundColor = .clear; scroll.delegate = self
        scroll.showsVerticalScrollIndicator = false; scroll.contentInsetAdjustmentBehavior = .never
        scroll.alwaysBounceVertical = false
        fallback.numberOfLines = 0; fallback.textAlignment = .center; scroll.addSubview(fallback)
        scroll.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
        accessibilityCustomActions = [UIAccessibilityCustomAction(name: "切换全屏歌词", target: self, selector: #selector(toggleAccessibility))]
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func bind(_ player: Player) {
        self.player = player; position = player.elapsed
        player.$lyrics.removeDuplicates().sink { [weak self] in self?.setLines($0) }.store(in: &subscriptions)
        player.$lyricText.removeDuplicates().sink { [weak self] value in
            guard let self else { return }; plainText = value
            if lines.isEmpty { needsTextLayout = true; setNeedsLayout() }
        }.store(in: &subscriptions)
        player.progress.$elapsed.removeDuplicates().sink { [weak self] in self?.updatePosition($0) }.store(in: &subscriptions)
    }
    func configure(compact: Bool, ink: UIColor, visibleHeight: CGFloat, active: Bool,
                   landscape: Bool, emptyText: String, toggle: @escaping () -> Void) {
        let changedStyle = self.compact != compact || !self.ink.isEqual(ink)
        let changedHeight = abs(self.visibleHeight - visibleHeight) > 0.5
        let becameActive = active && !self.active
        self.compact = compact; self.ink = ink; self.active = active
        self.landscape = landscape; self.toggle = toggle
        if self.emptyText != emptyText { self.emptyText = emptyText; needsTextLayout = true }
        self.visibleHeight = max(0, visibleHeight)
        if changedStyle { needsTextLayout = true }
        if !active { stopMotion(); resumeFollowing?.cancel() }
        if needsTextLayout || measuredSize != bounds.size { setNeedsLayout() }
        else if active && (changedHeight || becameActive) {
            manualUntil = 0; moveToCurrent(animated: !becameActive)
        }
    }
    private func setLines(_ value: [LyricLine]) {
        guard lines != value else { return }
        stopMotion(); lines = value; highlighted = -1; manualUntil = 0
        labels.forEach { $0.removeFromSuperview() }
        labels = value.enumerated().map { index, line in
            let label = PlayerLyricLabel(); label.text = line.text.isEmpty ? "♪" : line.text
            label.numberOfLines = 0; label.textAlignment = .center; label.isAccessibilityElement = true
            label.activate = { [weak self] in
                guard let self else { return }
                if landscape { toggle?() } else if lines.indices.contains(index) { player?.seek(lines[index].time) }
            }
            label.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "切换全屏歌词", actionHandler: { [weak self] _ in
                self?.toggle?(); return true
            })]
            scroll.addSubview(label); return label
        }
        needsTextLayout = true; setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, bounds.height > 0 else { return }
        let resized = measuredSize != bounds.size
        guard resized || needsTextLayout else { return }
        stopMotion(); measuredSize = bounds.size; needsTextLayout = false
        UIView.performWithoutAnimation {
            scroll.frame = CGRect(origin: .zero, size: bounds.size)
            viewport.frame = CGRect(x: 0, y: 0, width: bounds.width, height: min(bounds.height, visibleHeight))
            let inset: CGFloat = compact ? 8 : 24
            let width = max(1, bounds.width - inset * 2)
            var y = bounds.height / 2
            highlighted = currentIndex()
            for (index, label) in labels.enumerated() {
                label.font = font(active: false)
                let normalHeight = label.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
                label.font = font(active: true)
                let selectedHeight = label.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
                // Reserving the larger height keeps a highlight from reflowing
                // all later rows or competing with the scrolling animation.
                let height = ceil(max(normalHeight, selectedHeight))
                label.frame = CGRect(x: inset, y: y, width: width, height: height)
                style(index, active: index == highlighted)
                y += height + (compact ? 16 : 24)
            }
            fallback.isHidden = !lines.isEmpty
            let paragraph = NSMutableParagraphStyle(); paragraph.alignment = .center; paragraph.lineSpacing = compact ? 8 : 14
            fallback.attributedText = NSAttributedString(string: plainText.isEmpty ? emptyText : plainText, attributes: [
                .font: UIFont.systemFont(ofSize: compact ? 16 : 20), .foregroundColor: ink, .paragraphStyle: paragraph
            ])
            let fallbackHeight = fallback.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
            fallback.frame = CGRect(x: inset, y: compact ? 24 : 100, width: width, height: fallbackHeight)
            scroll.contentSize = CGSize(width: bounds.width, height: lines.isEmpty ? max(bounds.height, fallback.frame.maxY + 24) : y + bounds.height)
        }
        if active { moveToCurrent(animated: false) }
    }
    private func font(active: Bool) -> UIFont {
        .systemFont(ofSize: active ? (compact ? 19 : 25) : (compact ? 16 : 21), weight: active ? .bold : .medium)
    }
    private func style(_ index: Int, active: Bool) {
        guard labels.indices.contains(index) else { return }
        labels[index].font = font(active: active); labels[index].textColor = ink
        labels[index].alpha = active ? 1 : 0.35
        labels[index].accessibilityTraits = active ? [.button, .selected] : .button
    }
    private func currentIndex() -> Int {
        var low = 0, high = lines.count
        while low < high {
            let middle = (low + high) / 2
            if lines[middle].time <= position { low = middle + 1 } else { high = middle }
        }
        return low - 1
    }
    private func updatePosition(_ value: Double) {
        position = value
        guard active, !needsTextLayout else { return }
        let next = currentIndex()
        guard next != highlighted else { return }
        style(highlighted, active: false); highlighted = next; style(next, active: true)
        if !scroll.isDragging && !scroll.isDecelerating && CACurrentMediaTime() >= manualUntil { moveToCurrent(animated: true) }
    }
    private func moveToCurrent(animated: Bool) {
        guard active, measuredSize == bounds.size, !needsTextLayout else { return }
        let height = min(bounds.height, visibleHeight)
        let index = highlighted >= 0 ? highlighted : (labels.isEmpty ? -1 : 0)
        let currentY = labels.indices.contains(index) ? labels[index].frame.midY : height / 2
        let target = CGPoint(x: 0, y: max(0, min(scroll.contentSize.height - bounds.height, currentY - height / 2)))
        stopMotion()
        let changes = {
            self.viewport.frame = CGRect(x: 0, y: 0, width: self.bounds.width, height: height)
            self.scroll.contentOffset = target
        }
        if !animated || UIAccessibility.isReduceMotionEnabled { UIView.performWithoutAnimation(changes); return }
        let animator = UIViewPropertyAnimator(duration: 0.22, curve: .easeInOut, animations: changes)
        animator.addCompletion { [weak self, weak animator] _ in
            guard let self, let animator, self.motion === animator else { return }; self.motion = nil
        }
        motion = animator; animator.startAnimation()
    }
    private func stopMotion() {
        guard let motion else { return }
        let offset = scroll.layer.presentation()?.bounds.origin ?? scroll.contentOffset
        let frame = viewport.layer.presentation()?.frame ?? viewport.frame
        if motion.state == .active { motion.stopAnimation(true) }; self.motion = nil
        UIView.performWithoutAnimation { scroll.contentOffset = offset; viewport.frame = frame }
    }
    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        guard active else { return }
        let point = recognizer.location(in: scroll)
        if !landscape, let index = labels.firstIndex(where: { $0.frame.contains(point) }), lines.indices.contains(index) {
            player?.seek(lines[index].time)
        } else { manualUntil = 0; toggle?() }
    }
    @objc private func toggleAccessibility() -> Bool { toggle?(); return true }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { stopMotion(); resumeFollowing?.cancel(); manualUntil = .greatestFiniteMagnitude }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { scheduleFollowing() } }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { scheduleFollowing() }
    private func scheduleFollowing() {
        manualUntil = CACurrentMediaTime() + 4.5; resumeFollowing?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.moveToCurrent(animated: true) }
        resumeFollowing = work; DispatchQueue.main.asyncAfter(deadline: .now() + 4.5, execute: work)
    }
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard active, point.y <= visibleHeight else { return nil }
        return super.hitTest(point, with: event)
    }
    func stop() { stopMotion(); resumeFollowing?.cancel(); subscriptions.removeAll() }
}

private final class PlayerLyricLabel: UILabel {
    var activate: (() -> Void)?
    override func accessibilityActivate() -> Bool { activate?(); return true }
}
