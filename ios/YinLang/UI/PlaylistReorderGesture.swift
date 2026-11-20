import SwiftUI
import UIKit

// A local reorder uses a full-size snapshot rather than iOS's scaled drag item.
// UIKit presents the native context menu first. The same uninterrupted touch
// reaches this recognizer at three seconds, dismisses the menu, then reorders.
struct PlaylistReorderGesture: UIViewRepresentable {
    var keys: [String]
    var frames: [String: CGRect]
    var grid = false
    var titles: [String: String] = [:]
    var edit: (String) -> Void
    var delete: (String) -> Void
    var menuChanged: (Bool) -> Void
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
    final class Coordinator: NSObject, UIGestureRecognizerDelegate, UIContextMenuInteractionDelegate {
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
        private var touchOffsetX: CGFloat = 0
        private var fixedX: CGFloat = 0
        private var timestamp: CFTimeInterval = 0
        private var settling = false
        private var contextMenu: UIContextMenuInteraction?
        private var menuVisible = false
        private var menuPreview: UIView?
        private var pressedKey: String?
        private var pendingDragKey: String?
        private var pendingAction: (key: String, delete: Bool)?
#if DEBUG
        private var originalSize = CGSize.zero
        private var maximumSizeError: CGFloat = 0
        private var moveCount = 0
        private var scrollDistance: CGFloat = 0
        private var timerTicks = 0
        private var edgeTicks = 0
        private var lastViewport = CGRect.zero
        private var lastScrollMaximum: CGFloat = 0
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
            recognizer.minimumPressDuration = 3; recognizer.allowableMovement = 9
            recognizer.cancelsTouchesInView = true; recognizer.delegate = self
            window.addGestureRecognizer(recognizer); gesture = recognizer
            let menu = UIContextMenuInteraction(delegate: self)
            found.addInteraction(menu); contextMenu = menu
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
            pendingDragKey = nil; pendingAction = nil; pressedKey = nil
            let oldMenu = contextMenu; contextMenu = nil
            oldMenu?.dismissMenu()
            if let oldMenu { scroll?.removeInteraction(oldMenu) }
            if menuVisible {
                let changed = parent.menuChanged
                DispatchQueue.main.async { changed(false) }
            }
            menuPreview?.removeFromSuperview(); menuPreview = nil; menuVisible = false
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
            // A fresh touch on the system menu must not start another timer.
            guard !menuVisible else { return false }
            pressedKey = accepts ? parent.keys.first { parent.frames[$0]?.contains(point) == true } : nil
#if DEBUG
            if UITestFixtures.active, accepts {
                DispatchQueue.main.async { [weak self] in self?.parent.report("touch=\(Int(point.x))/\(Int(point.y));frames=\(self?.parent.frames.count ?? 0)") }
            }
#endif
            return accepts
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            // Let UIKit's context-menu long press coexist with the three-second
            // hold. Scroll panning still loses once the stationary hold wins.
            otherGestureRecognizer !== scroll?.panGestureRecognizer
        }
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction, configurationForMenuAtLocation location: CGPoint) -> UIContextMenuConfiguration? {
            guard !settling, key == nil, pendingDragKey == nil, let anchor, let scroll,
                  scroll.bounds.contains(location) else { return nil }
            let point = scroll.convert(location, to: anchor)
            guard let selected = parent.keys.first(where: { parent.frames[$0]?.contains(point) == true }) else { return nil }
            return UIContextMenuConfiguration(identifier: selected as NSString, previewProvider: nil) { [weak self] _ in
                guard let self else { return UIMenu(children: []) }
                let edit = UIAction(title: "编辑歌单", image: UIImage(systemName: "pencil")) { [weak self] _ in self?.selectMenuAction(selected, delete: false) }
                let cover = UIAction(title: "更换歌单封面", image: UIImage(systemName: "photo")) { [weak self] _ in self?.selectMenuAction(selected, delete: false) }
                let delete = UIAction(title: "删除歌单", image: UIImage(systemName: "trash"), attributes: .destructive) { [weak self] _ in self?.selectMenuAction(selected, delete: true) }
                return UIMenu(title: self.parent.titles[selected] ?? "歌单", children: [edit, cover, delete])
            }
        }
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willDisplayMenuFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
            menuVisible = true; parent.menuChanged(true)
        }
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction, previewForHighlightingMenuWithConfiguration configuration: UIContextMenuConfiguration) -> UITargetedPreview? {
            guard let selected = configuration.identifier as? String, let anchor, let scroll,
                  let rect = parent.frames[selected],
                  let snapshot = scroll.resizableSnapshotView(from: anchor.convert(rect, to: scroll), afterScreenUpdates: false, withCapInsets: .zero) else { return nil }
            menuPreview?.removeFromSuperview()
            snapshot.frame = anchor.convert(rect, to: scroll); snapshot.isUserInteractionEnabled = false
            snapshot.layer.cornerRadius = 17; snapshot.clipsToBounds = true
            scroll.addSubview(snapshot); menuPreview = snapshot
            let parameters = UIPreviewParameters(); parameters.backgroundColor = .clear
            parameters.visiblePath = UIBezierPath(roundedRect: snapshot.bounds, cornerRadius: 17)
            return UITargetedPreview(view: snapshot, parameters: parameters)
        }
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction, previewForDismissingMenuWithConfiguration configuration: UIContextMenuConfiguration) -> UITargetedPreview? {
            guard let menuPreview, menuPreview.window != nil else { return nil }
            let parameters = UIPreviewParameters(); parameters.backgroundColor = .clear
            parameters.visiblePath = UIBezierPath(roundedRect: menuPreview.bounds, cornerRadius: 17)
            return UITargetedPreview(view: menuPreview, parameters: parameters)
        }
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willEndFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
            let complete: () -> Void = { [weak self] in
                guard let self, self.contextMenu === interaction else { return }
                self.menuPreview?.removeFromSuperview(); self.menuPreview = nil
                self.menuVisible = false; self.parent.menuChanged(false)
                if let action = self.pendingAction {
                    self.pendingAction = nil; self.pendingDragKey = nil
                    if action.delete { self.parent.delete(action.key) } else { self.parent.edit(action.key) }
                } else if let selected = self.pendingDragKey, let gesture = self.gesture,
                          gesture.state == .began || gesture.state == .changed {
                    self.pendingDragKey = nil
                    self.beginDragging(selected, recognizer: gesture)
                } else { self.pendingDragKey = nil }
            }
            if let animator { animator.addCompletion(complete) } else { complete() }
        }
        private func selectMenuAction(_ key: String, delete: Bool) {
            pendingDragKey = nil
            if menuVisible {
                pendingAction = (key, delete); contextMenu?.dismissMenu()
            } else if delete { parent.delete(key) } else { parent.edit(key) }
        }
        @objc private func handle(_ recognizer: UILongPressGestureRecognizer) {
            guard let window = anchor?.window else { return }
            finger = recognizer.location(in: window)
            switch recognizer.state {
            case .began:
#if DEBUG
                if UITestFixtures.active { parent.report("began") }
#endif
                guard let selected = pressedKey else { return }
                if menuVisible {
                    pendingDragKey = selected; contextMenu?.dismissMenu()
                } else { beginDragging(selected, recognizer: recognizer) }
            case .changed:
                guard key != nil else { return }
                updatePosition()
                scrollAtEdge(seconds: 1.0 / 60)
            case .ended:
                pendingDragKey = nil; pressedKey = nil; finish(dropped: true)
            case .cancelled, .failed:
                pendingDragKey = nil; pressedKey = nil; finish(dropped: false)
            default: break
            }
        }
        private func beginDragging(_ selected: String, recognizer: UILongPressGestureRecognizer) {
            guard key == nil, let anchor, let window = anchor.window, let scroll,
                  let rect = parent.frames[selected],
                  let snapshot = scroll.resizableSnapshotView(from: anchor.convert(rect, to: scroll), afterScreenUpdates: false, withCapInsets: .zero) else { return }
            finger = recognizer.location(in: window)
            key = selected; slots = parent.keys.compactMap { parent.frames[$0] }
            target = parent.keys.firstIndex(of: selected) ?? 0
            snapshot.frame = anchor.convert(rect, to: window)
            touchOffset = finger.y - snapshot.center.y; touchOffsetX = finger.x - snapshot.center.x; fixedX = snapshot.center.x
            snapshot.isUserInteractionEnabled = false; snapshot.isAccessibilityElement = true
            snapshot.accessibilityIdentifier = "library-playlist-drag-preview"
            snapshot.accessibilityLabel = "正在移动歌单"
            snapshot.accessibilityValue = "\(Int(rect.width.rounded()))×\(Int(rect.height.rounded()))"
            snapshot.layer.shadowColor = UIColor.black.cgColor; snapshot.layer.shadowOpacity = 0.18
            snapshot.layer.shadowRadius = 10; snapshot.layer.shadowOffset = CGSize(width: 0, height: 4)
            snapshot.layer.cornerRadius = 16
            window.addSubview(snapshot); lifted = snapshot
#if DEBUG
            originalSize = rect.size; maximumSizeError = 0; moveCount = 0; scrollDistance = 0; timerTicks = 0; edgeTicks = 0
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
        }
        private func updatePosition() {
            guard let anchor, let window = anchor.window, let lifted, let key, let scroll, !slots.isEmpty else { return }
            let viewport = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window).intersection(window.bounds)
            let half = lifted.bounds.height / 2
            let y = min(viewport.maxY - half, max(viewport.minY + half, finger.y - touchOffset))
            // No transform or implicit animation: the lifted row follows the
            // finger at its original width and height on both phones and tablets.
            let halfWidth = lifted.bounds.width / 2
            let x = parent.grid ? min(viewport.maxX - halfWidth, max(viewport.minX + halfWidth, finger.x - touchOffsetX)) : fixedX
            lifted.center = CGPoint(x: x, y: y)
#if DEBUG
            maximumSizeError = max(maximumSizeError, max(abs(lifted.frame.width - originalSize.width), abs(lifted.frame.height - originalSize.height)))
#endif
            let local = anchor.convert(lifted.center, from: window)
            func distance(_ rect: CGRect) -> CGFloat {
                let dx = parent.grid ? rect.midX - local.x : 0
                let dy = rect.midY - local.y
                return dx * dx + dy * dy
            }
            guard let index = slots.indices.min(by: { distance(slots[$0]) < distance(slots[$1]) }), index != target else { return }
            target = index; parent.move(key, index)
#if DEBUG
            moveCount += 1
#endif
        }
        private func tick() {
            let now = ProcessInfo.processInfo.systemUptime
            let dt = min(0.1, max(0.016, now - timestamp)); timestamp = now
            if let window = anchor?.window, let gesture { finger = gesture.location(in: window) }
#if DEBUG
            timerTicks += 1
#endif
            scrollAtEdge(seconds: dt)
        }
        private func scrollAtEdge(seconds: CFTimeInterval) {
            guard let scroll, let anchor, let window = anchor.window, key != nil else { return }
            let viewport = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window).intersection(window.bounds)
            let edge = min(max(CGFloat(64), (lifted?.bounds.height ?? 0) * 1.25), max(24, viewport.height * 0.25))
            let up = max(0, min(1, (viewport.minY + edge - finger.y) / edge))
            let down = max(0, min(1, (finger.y - viewport.maxY + edge) / edge))
            let minimum = -scroll.adjustedContentInset.top
            let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
            // Android advances by a fixed step once inside the edge zone.
            // Very slow movement near its boundary must still keep scrolling.
            let direction: CGFloat = down > 0 ? 1 : up > 0 ? -1 : 0
            let y = min(maximum, max(minimum, scroll.contentOffset.y + direction * 400 * seconds))
#if DEBUG
            if direction != 0 { edgeTicks += 1 }; lastViewport = viewport; lastScrollMaximum = maximum
#endif
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
                parent.report("\(originalSize.width),\(originalSize.height),\(maximumSizeError),\(moveCount),\(scrollDistance),ticks=\(timerTicks);edge=\(edgeTicks);finger=\(finger.y);viewport=\(lastViewport);maximum=\(lastScrollMaximum);offset=\(scroll?.contentOffset.y ?? 0)")
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
