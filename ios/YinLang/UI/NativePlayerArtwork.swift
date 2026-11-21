import SwiftUI
import UIKit
import QuartzCore
import CoreImage

struct PlayerArtworkSurface: UIViewRepresentable {
    let url: URL?
    let book: Bool
    let vinyl: Bool
    let playing: Bool
    let active: Bool
    func makeUIView(context: Context) -> PlayerArtworkCanvas { PlayerArtworkCanvas(frame: .zero) }
    func updateUIView(_ view: PlayerArtworkCanvas, context: Context) {
        view.configure(url: url, book: book, vinyl: vinyl, playing: playing, active: active)
    }
    static func dismantleUIView(_ view: PlayerArtworkCanvas, coordinator: ()) { view.stop() }
}

// Cover and record layers share one decoded bitmap and stay mounted while the
// user changes style. No UIHostingController or SwiftUI ring tree is created.
final class PlayerArtworkCanvas: UIView {
    private let shadow = UIView()
    private let cover = UIImageView()
    private let disc = UIView()
    private let label = UIImageView()
    private let hole = CALayer()
    private var grooves: [CAShapeLayer] = []
    private var load: Task<Void, Never>?
    private var imageKey = ""
    private var book = false
    private var vinyl = false
    private var active = false
    private var wantsPlaying = false
    private var measuredSize = CGSize.zero
    private var rotating = false
    private var angle = 0.0
    private var startedAt = 0.0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear; isUserInteractionEnabled = false
        cover.contentMode = .scaleAspectFill; cover.clipsToBounds = true; cover.layer.cornerRadius = 19
        disc.backgroundColor = UIColor(white: 0.06, alpha: 1); disc.isUserInteractionEnabled = false
        shadow.backgroundColor = .black; shadow.layer.cornerRadius = 19
        shadow.layer.shadowColor = UIColor.black.cgColor; shadow.layer.shadowOpacity = 0.25
        shadow.layer.shadowRadius = 8; shadow.layer.shadowOffset = CGSize(width: 0, height: 5)
        addSubview(shadow); addSubview(cover); addSubview(disc)
        for index in 0..<22 {
            let ring = CAShapeLayer(); ring.fillColor = UIColor.clear.cgColor
            ring.strokeColor = UIColor.white.withAlphaComponent(index % 3 == 0 ? 0.12 : 0.05).cgColor
            ring.lineWidth = 1; disc.layer.addSublayer(ring); grooves.append(ring)
        }
        label.contentMode = .scaleAspectFill; label.clipsToBounds = true; disc.addSubview(label)
        hole.backgroundColor = UIColor.black.cgColor; disc.layer.addSublayer(hole)
        disc.alpha = 0
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func configure(url: URL?, book: Bool, vinyl: Bool, playing: Bool, active: Bool) {
        let changedBook = self.book != book
        let changedStyle = self.vinyl != (vinyl && !book)
        let firstAppearance = !self.active && active
        self.book = book; self.vinyl = vinyl && !book; self.active = active; wantsPlaying = playing
        let key = url.map(ArtworkCache.key) ?? (book ? "placeholder-book" : "placeholder-music")
        if key != imageKey {
            imageKey = key; load?.cancel()
            let placeholder = UIImage(named: book ? "android-hero_books" : "yinlang_neon")
#if DEBUG
            if let url, url.scheme == "fixture" {
                setBitmap(UIImage(named: String(url.absoluteString.dropFirst("fixture:".count))))
            } else {
                setBitmap(url.flatMap { ArtworkCache.shared.memoryImage($0) } ?? placeholder)
                loadBitmap(url, key: key)
            }
#else
            setBitmap(url.flatMap { ArtworkCache.shared.memoryImage($0) } ?? placeholder)
            loadBitmap(url, key: key)
#endif
        }
        if changedBook { measuredSize = .zero; setNeedsLayout() }
        if changedStyle || firstAppearance {
            cover.layer.removeAllAnimations(); disc.layer.removeAnimation(forKey: "opacity")
            let changes = {
                self.cover.alpha = self.vinyl ? 0 : 1; self.shadow.alpha = self.vinyl ? 0 : 1
                self.disc.alpha = self.vinyl ? 1 : 0
            }
            if active && !firstAppearance && !UIAccessibility.isReduceMotionEnabled {
                UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: changes)
            } else { UIView.performWithoutAnimation(changes) }
        }
        updateRotation()
    }
    private func loadBitmap(_ url: URL?, key: String) {
        guard let url else { return }
        load = Task { [weak self] in
            let bitmap = await ArtworkCache.shared.image(url)
            guard !Task.isCancelled, let self, imageKey == key, let bitmap else { return }
            setBitmap(bitmap)
        }
    }
    private func setBitmap(_ bitmap: UIImage?) { cover.image = bitmap; label.image = bitmap }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard measuredSize != bounds.size else { return }; measuredSize = bounds.size
        let edge = min(bounds.width, bounds.height)
        CATransaction.begin(); CATransaction.setDisableActions(true)
        let coverEdge = edge * (book ? 0.77 : 1)
        cover.frame = CGRect(x: (bounds.width - coverEdge) / 2, y: (bounds.height - coverEdge) / 2, width: coverEdge, height: coverEdge)
        shadow.frame = cover.frame
        shadow.layer.shadowPath = UIBezierPath(roundedRect: shadow.bounds, cornerRadius: 19).cgPath
        disc.bounds = CGRect(x: 0, y: 0, width: edge, height: edge)
        disc.center = CGPoint(x: bounds.midX, y: bounds.midY); disc.layer.cornerRadius = edge / 2
        for (index, ring) in grooves.enumerated() {
            ring.frame = disc.bounds
            let inset = CGFloat(10 + index * 4)
            ring.path = inset < edge / 2 ? UIBezierPath(ovalIn: disc.bounds.insetBy(dx: inset, dy: inset)).cgPath : nil
        }
        let labelEdge = edge * 0.43
        label.frame = CGRect(x: (edge - labelEdge) / 2, y: (edge - labelEdge) / 2, width: labelEdge, height: labelEdge)
        label.layer.cornerRadius = labelEdge / 2
        hole.frame = CGRect(x: (edge - 13) / 2, y: (edge - 13) / 2, width: 13, height: 13); hole.cornerRadius = 6.5
        CATransaction.commit()
    }
    override func didMoveToWindow() { super.didMoveToWindow(); updateRotation() }
    private func updateRotation() {
        let shouldRotate = active && vinyl && wantsPlaying && window != nil
        guard shouldRotate != rotating || (rotating && disc.layer.animation(forKey: "vinyl-rotation") == nil) else { return }
        let now = CACurrentMediaTime()
        if rotating {
            if let visible = disc.layer.presentation() { angle = atan2(Double(visible.transform.m12), Double(visible.transform.m11)) }
            else { angle = (angle + (now - startedAt) * 2 * .pi / 24).truncatingRemainder(dividingBy: 2 * .pi) }
            CATransaction.begin(); CATransaction.setDisableActions(true)
            disc.layer.setValue(angle, forKeyPath: "transform.rotation.z"); disc.layer.removeAnimation(forKey: "vinyl-rotation")
            CATransaction.commit(); rotating = false
        }
        guard shouldRotate else { return }
        let motion = CABasicAnimation(keyPath: "transform.rotation.z")
        motion.fromValue = angle; motion.toValue = angle + 2 * .pi; motion.duration = 24
        motion.timingFunction = CAMediaTimingFunction(name: .linear); motion.repeatCount = .infinity
        disc.layer.add(motion, forKey: "vinyl-rotation"); startedAt = now; rotating = true
    }
    func stop() { active = false; updateRotation(); load?.cancel() }
}

struct PlayerBackdrop: View, Equatable {
    let url: URL?
    let size: CGSize
    let dark: Bool
    @State private var bitmap: UIImage?
    private var bucket: Int { Int((size.width / max(1, size.height) * 20).rounded()) }
    private var request: String { (url.map(ArtworkCache.key) ?? "none") + ":\(bucket):\(Int((75 * 160 / max(1, size.width) * 2).rounded()))" }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.url == rhs.url && lhs.size == rhs.size && lhs.dark == rhs.dark }
    var body: some View {
        Group {
            if let bitmap { Image(uiImage: bitmap).resizable().scaledToFill().frame(width: size.width, height: size.height).clipped().opacity(dark ? 0.35 : 0.22) }
            else { Color.clear }
        }.task(id: request) {
            guard let url else { bitmap = nil; return }
            let source: UIImage?
#if DEBUG
            if url.scheme == "fixture" { source = UIImage(named: String(url.absoluteString.dropFirst("fixture:".count))) }
            else { source = await ArtworkCache.shared.image(url) }
#else
            source = await ArtworkCache.shared.image(url)
#endif
            guard !Task.isCancelled else { return }
            guard let cgImage = source?.cgImage else { bitmap = nil; return }
            let rendered = await PlayerBackdropRenderer.shared.render(cgImage, key: request, aspect: CGFloat(max(1, bucket)) / 20,
                                                                      radius: 75 * 160 / max(1, size.width))
            guard !Task.isCancelled else { return }; bitmap = rendered
        }
    }
}

// The reusable CIContext and cached, small bitmap live off the main actor. No
// full-screen blur filter participates in player layout or transition frames.
private actor PlayerBackdropRenderer {
    static let shared = PlayerBackdropRenderer()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let cache = NSCache<NSString, UIImage>()
    init() { cache.totalCostLimit = 4 * 1_048_576 }
    func render(_ image: CGImage, key: String, aspect: CGFloat, radius: CGFloat) -> UIImage? {
        if let cached = cache.object(forKey: key as NSString) { return cached }
        let width: CGFloat = 160, height = max(48, min(360, (160 / max(0.2, aspect)).rounded()))
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        let source = CIImage(cgImage: image)
        let scale = max(width / source.extent.width, height / source.extent.height)
        let scaled = source.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let centered = scaled.transformed(by: CGAffineTransform(translationX: (width - scaled.extent.width) / 2,
                                                                y: (height - scaled.extent.height) / 2)).cropped(to: rect)
        let result = centered.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius]).cropped(to: rect)
        guard let output = context.createCGImage(result, from: rect) else { return nil }
        let bitmap = UIImage(cgImage: output)
        cache.setObject(bitmap, forKey: key as NSString, cost: Int(width * height * 4)); return bitmap
    }
}
