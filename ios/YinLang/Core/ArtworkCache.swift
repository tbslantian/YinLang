import Foundation
import UIKit
import ImageIO

// Covers are independent of the audio cache. A server's short-lived auth salt
// must not turn an unchanged album/playlist into a new image after every launch.
@MainActor
final class ArtworkCache {
    static let shared = ArtworkCache()
    private let memory = NSCache<NSString, UIImage>()
    private let keys = NSCache<NSURL, NSString>()
    private var pending: [String: Task<UIImage?, Never>] = [:]
    private let disk: ArtworkDisk
    private let session: URLSession
    init(directory: URL = Disk.cache.appendingPathComponent("artwork"), session: URLSession = .shared) {
        disk = ArtworkDisk(directory: directory); self.session = session
        memory.totalCostLimit = 64 * 1_048_576
        keys.countLimit = 2048
    }
    static func key(_ url: URL) -> String {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return Disk.hash(url.absoluteString) }
        if parts.path.lowercased().hasSuffix("/getcoverart.view") {
            parts.queryItems = parts.queryItems?.filter { !["t", "s"].contains($0.name) }.sorted { ($0.name, $0.value ?? "") < ($1.name, $1.value ?? "") }
        }
        parts.fragment = nil
        return Disk.hash(parts.string ?? url.absoluteString)
    }
    private func cachedKey(_ url: URL) -> String {
        if let key = keys.object(forKey: url as NSURL) { return key as String }
        let key = Self.key(url); keys.setObject(key as NSString, forKey: url as NSURL); return key
    }
    func memoryImage(_ url: URL) -> UIImage? { memory.object(forKey: cachedKey(url) as NSString) }
    func image(_ url: URL) async -> UIImage? {
        let key = cachedKey(url)
        if let image = memory.object(forKey: key as NSString) { return image }
        if let task = pending[key] { return await task.value }
        let disk = disk, session = session
        let task = Task<UIImage?, Never> {
            let saved = await disk.read(key)
            if let saved, saved.fresh { return saved.image }
            var request = URLRequest(url: url); request.timeoutInterval = 20
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      data.count <= 10 * 1_048_576, let image = await disk.decode(data) else {
                    return saved?.image
                }
                await disk.write(data, key: key)
                return image
            } catch { return saved?.image }
        }
        pending[key] = task
        let image = await task.value; pending.removeValue(forKey: key)
        if let image { memory.setObject(image, forKey: key as NSString, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4)) }
        return image
    }
}

private actor ArtworkDisk {
    let directory: URL
    private var lastTrim = Date.distantPast
    private var bytesSinceTrim = 0
    init(directory: URL) { self.directory = Disk.directory(directory) }
    // ImageIO downsamples and eagerly decodes off the main actor. The longest
    // edge covers the largest player artwork at Retina resolution, while the
    // original bytes remain on disk for future rendering needs.
    func decode(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: 1280,
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: bitmap)
    }
    func read(_ key: String) -> (image: UIImage, fresh: Bool)? {
        let file = directory.appendingPathComponent(key)
        guard let data = try? Data(contentsOf: file), let image = decode(data) else { return nil }
        let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
        return (image, Date().timeIntervalSince(date) < 7 * 86400)
    }
    func write(_ data: Data, key: String) {
        try? data.write(to: directory.appendingPathComponent(key), options: .atomic)
        bytesSinceTrim += data.count
        // Avoid scanning/sorting the entire cache after each card image. Bound
        // temporary growth by both time and bytes before enforcing the 128 MB cap.
        guard bytesSinceTrim >= 8 * 1_048_576 || Date().timeIntervalSince(lastTrim) >= 30 else { return }
        bytesSinceTrim = 0; lastTrim = Date()
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
        let entries = files.compactMap { file -> (URL, Int, Date)? in
            guard let info = try? file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }
            return (file, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast)
        }.sorted { $0.2 < $1.2 }
        var size = entries.reduce(0) { $0 + $1.1 }
        for (file, bytes, _) in entries where size > 128 * 1_048_576 { try? FileManager.default.removeItem(at: file); size -= bytes }
    }
}
