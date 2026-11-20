import SwiftUI
import UIKit

// A local reorder uses a full-size snapshot rather than iOS's scaled drag item.
// Only a stationary long press captures the scroll gesture; normal row taps and
// vertical flicks continue to belong to NavigationLink and the parent ScrollView.
struct PlaylistReorderGesture: UIViewRepresentable {
    var keys: [String]
    var frames: [String: CGRect]
    var begin: (String) -> Void
    var move: (String, Int) -> Void
    var end: (Bool) -> Void
    var report: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> Anchor {
        let view = Anchor(); view.isUserInteractionEnabled = false
        view.attach = { [weak coordinator = context.coordinator] view in coordinator?.attach(view) }
        return view
    }
    func updateUIView(_ view: Anchor, context: Context) {
        context.coordinator.parent = self
        context.coordinator.attach(view)
    }
    static func dismantleUIView(_ view: Anchor, coordinator: Coordinator) { coordinator.detach() }

    final class Anchor: UIView {
        var attach: ((Anchor) -> Void)?
        override func didMoveToWindow() { super.didMoveToWindow(); scheduleAttach() }
        override func didMoveToSuperview() { super.didMoveToSuperview(); scheduleAttach() }
        private func scheduleAttach() { DispatchQueue.main.async { [weak self] in if let self, self.window != nil { self.attach?(self) } } }
    }
    private final class LocalLongPress: UILongPressGestureRecognizer {
        // iPad's SwiftUI navigation interaction can recognize while the finger
        // is still stationary. It must not discard our pending long press.
        // Moving before the hold threshold still fails via allowableMovement.
        override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    }
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: PlaylistReorderGesture
        private weak var anchor: Anchor?
        private weak var scroll: UIScrollView?
        private var gesture: UILongPressGestureRecognizer?
        private var lifted: UIView?
        private var scrollTimer: Timer?
        private var slots: [CGRect] = []
        private var key: String?
        private var target = 0
        private var finger = CGPoint.zero
        private var touchOffset: CGFloat = 0
        private var fixedX: CGFloat = 0
        private var timestamp: CFTimeInterval = 0
        private var settling = false
#if DEBUG
        private var originalSize = CGSize.zero
        private var maximumSizeError: CGFloat = 0
        private var moveCount = 0
        private var scrollDistance: CGFloat = 0
#endif
        init(_ parent: PlaylistReorderGesture) { self.parent = parent }
        func attach(_ view: Anchor) {
            anchor = view
            var ancestor = view.superview
            while let current = ancestor, !(current is UIScrollView) { ancestor = current.superview }
            guard let window = view.window else { return }
            // SwiftUI can host a background representable beside its scroll
            // content rather than below UIScrollView in the UIKit hierarchy.
            // In that layout locate the visible scroll viewport covering it.
            let found = (ancestor as? UIScrollView) ?? enclosingScroll(in: window, anchor: view)
            guard let found, found !== scroll else { return }
            detach(); anchor = view; scroll = found
            let recognizer = LocalLongPress(target: self, action: #selector(handle(_:)))
            recognizer.minimumPressDuration = 0.32; recognizer.allowableMovement = 9
            recognizer.cancelsTouchesInView = true; recognizer.delegate = self
            window.addGestureRecognizer(recognizer); gesture = recognizer
#if DEBUG
            if UITestFixtures.active { DispatchQueue.main.async { [weak self] in self?.parent.report("attached") } }
#endif
        }
        private func enclosingScroll(in window: UIWindow, anchor: UIView) -> UIScrollView? {
            var pending = window.subviews, matches: [(UIScrollView, CGFloat)] = []
            let area = anchor.convert(anchor.bounds, to: window)
            while let view = pending.popLast() {
                guard !view.isHidden, view.alpha > 0.01 else { continue }
                pending.append(contentsOf: view.subviews)
                guard let candidate = view as? UIScrollView, candidate.isScrollEnabled else { continue }
                let intersection = candidate.convert(candidate.bounds, to: window).intersection(area)
                if !intersection.isNull { matches.append((candidate, intersection.width * intersection.height)) }
            }
            return matches.max { $0.1 < $1.1 }?.0
        }
        func detach() {
            scrollTimer?.invalidate(); scrollTimer = nil
            if let gesture { gesture.view?.removeGestureRecognizer(gesture) }
            lifted?.layer.removeAllAnimations(); lifted?.removeFromSuperview(); lifted = nil
            gesture = nil; scroll = nil; key = nil; settling = false
        }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard !settling, let anchor, parent.keys.count > 1 else { return false }
            let point = gestureRecognizer.location(in: anchor)
            let insideScroll = scroll.map { $0.bounds.contains(gestureRecognizer.location(in: $0)) } ?? false
            let begins = insideScroll && parent.frames.values.contains { $0.contains(point) }
#if DEBUG
            if UITestFixtures.active { parent.report("begin=\(begins);point=\(Int(point.x))/\(Int(point.y));frames=\(parent.frames.count);anchor=\(Int(anchor.bounds.width))/\(Int(anchor.bounds.height))") }
#endif
            return begins
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard !settling, let anchor, let scroll else { return false }
            let point = touch.location(in: anchor)
            let accepts = scroll.bounds.contains(touch.location(in: scroll)) && parent.frames.values.contains { $0.contains(point) }
#if DEBUG
            if UITestFixtures.active, accepts {
                DispatchQueue.main.async { [weak self] in self?.parent.report("touch=\(Int(point.x))/\(Int(point.y));frames=\(self?.parent.frames.count ?? 0)") }
            }
#endif
            return accepts
        }
        @objc private func handle(_ recognizer: UILongPressGestureRecognizer) {
            guard let anchor, let window = anchor.window, let scroll else { return }
            finger = recognizer.location(in: window)
            switch recognizer.state {
            case .began:
#if DEBUG
                if UITestFixtures.active { parent.report("began") }
#endif
                let point = recognizer.location(in: anchor)
                guard let selected = parent.keys.first(where: { parent.frames[$0]?.contains(point) == true }),
                      let rect = parent.frames[selected],
                      let snapshot = scroll.resizableSnapshotView(from: anchor.convert(rect, to: scroll), afterScreenUpdates: false, withCapInsets: .zero) else { return }
                key = selected; slots = parent.frames.values.sorted { $0.minY < $1.minY }
                target = parent.keys.firstIndex(of: selected) ?? 0
                snapshot.frame = anchor.convert(rect, to: window)
                touchOffset = finger.y - snapshot.center.y; fixedX = snapshot.center.x
                snapshot.isUserInteractionEnabled = false; snapshot.isAccessibilityElement = true
                snapshot.accessibilityIdentifier = "library-playlist-drag-preview"
                snapshot.accessibilityLabel = "正在移动歌单"
                snapshot.accessibilityValue = "\(Int(rect.width.rounded()))×\(Int(rect.height.rounded()))"
                snapshot.layer.shadowColor = UIColor.black.cgColor; snapshot.layer.shadowOpacity = 0.18
                snapshot.layer.shadowRadius = 10; snapshot.layer.shadowOffset = CGSize(width: 0, height: 4)
                snapshot.layer.cornerRadius = 16
                window.addSubview(snapshot); lifted = snapshot
#if DEBUG
                originalSize = rect.size; maximumSizeError = 0; moveCount = 0; scrollDistance = 0
#endif
                // Once the stationary long press wins, normal gesture
                // arbitration keeps the pan from starting. Disabling the
                // scroll view's recognizer here can cancel this touch sequence.
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                parent.begin(selected)
                // Match Android's 32 ms edge-scroll loop. Display refresh can
                // pause while UIKit tracks a long press on some iPad layouts.
                timestamp = ProcessInfo.processInfo.systemUptime
                let timer = Timer(timeInterval: 0.032, repeats: true) { [weak self] _ in self?.tick() }
                RunLoop.main.add(timer, forMode: .common); scrollTimer = timer
            case .changed:
                updatePosition()
                // Touch events still arrive on a busy display. Advance here
                // too so edge scrolling does not depend on rendering cadence.
                scrollAtEdge(seconds: 1.0 / 60)
            case .ended: finish(dropped: true)
            case .cancelled, .failed: finish(dropped: false)
            default: break
            }
        }
        private func updatePosition() {
            guard let anchor, let window = anchor.window, let lifted, let key, let scroll, !slots.isEmpty else { return }
            let viewport = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window).intersection(window.bounds)
            let half = lifted.bounds.height / 2
            let y = min(viewport.maxY - half, max(viewport.minY + half, finger.y - touchOffset))
            // No transform or implicit animation: the lifted row follows the
            // finger at its original width and height on both phones and tablets.
            lifted.center = CGPoint(x: fixedX, y: y)
#if DEBUG
            maximumSizeError = max(maximumSizeError, max(abs(lifted.frame.width - originalSize.width), abs(lifted.frame.height - originalSize.height)))
#endif
            let local = anchor.convert(lifted.center, from: window)
            guard let index = slots.indices.min(by: { abs(slots[$0].midY - local.y) < abs(slots[$1].midY - local.y) }), index != target else { return }
            target = index; parent.move(key, index)
#if DEBUG
            moveCount += 1
#endif
        }
        private func tick() {
            let now = ProcessInfo.processInfo.systemUptime
            let dt = min(0.1, max(0.016, now - timestamp)); timestamp = now
            scrollAtEdge(seconds: dt)
        }
        private func scrollAtEdge(seconds: CFTimeInterval) {
            guard let scroll, let anchor, let window = anchor.window, key != nil else { return }
            let viewport = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window).intersection(window.bounds)
            let edge = max(CGFloat(64), (lifted?.bounds.height ?? 0) * 1.25)
            let up = max(0, min(1, (viewport.minY + edge - finger.y) / edge))
            let down = max(0, min(1, (finger.y - viewport.maxY + edge) / edge))
            let minimum = -scroll.adjustedContentInset.top
            let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
            let y = min(maximum, max(minimum, scroll.contentOffset.y + (down - up) * 620 * seconds))
            if abs(y - scroll.contentOffset.y) > 0.1 {
#if DEBUG
                scrollDistance += abs(y - scroll.contentOffset.y)
#endif
                scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: y), animated: false)
            }
            updatePosition()
        }
        private func finish(dropped: Bool) {
            guard key != nil, let anchor, let window = anchor.window, let lifted else { return }
            scrollTimer?.invalidate(); scrollTimer = nil; settling = true
            key = nil
#if DEBUG
            if UITestFixtures.active {
                parent.report("\(originalSize.width),\(originalSize.height),\(maximumSizeError),\(moveCount),\(scrollDistance)")
            }
#endif
            let destination = slots.indices.contains(target) ? anchor.convert(slots[target], to: window) : lifted.frame
            UIView.animate(withDuration: 0.16, delay: 0, options: [.beginFromCurrentState, .curveEaseOut]) {
                lifted.frame = destination; lifted.layer.shadowOpacity = 0
            } completion: { [weak self] _ in
                guard let self else { lifted.removeFromSuperview(); return }
                self.parent.end(dropped); lifted.removeFromSuperview(); self.lifted = nil; self.settling = false
            }
        }
    }
}
