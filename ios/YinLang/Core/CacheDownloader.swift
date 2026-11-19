import Foundation

enum CacheDownloadSettings {
    static let key = "music-cache-threads"
    static func threads(defaults: UserDefaults = .standard) -> Int {
        min(5, max(1, defaults.object(forKey: key) as? Int ?? 3))
    }
}

// All current-track and next-track cache transfers share the configured limit.
// AVPlayer's own streaming requests are independent of offline cache workers.
private actor CacheTransferGate {
    static let shared = CacheTransferGate()
    private var active: Set<UUID> = []
    private var waiting: [(UUID, CheckedContinuation<Void, Error>)] = []
    func acquire(_ id: UUID) async throws {
        try Task.checkCancellation()
        if active.count < CacheDownloadSettings.threads() { active.insert(id); return }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { waiting.append((id, $0)); advance() }
        }, onCancel: { Task { await self.cancel(id) } })
    }
    func release(_ id: UUID) { active.remove(id); advance() }
    private func cancel(_ id: UUID) {
        if let index = waiting.firstIndex(where: { $0.0 == id }) { waiting.remove(at: index).1.resume(throwing: CancellationError()) }
        else { active.remove(id) }
        advance()
    }
    private func advance() {
        while active.count < CacheDownloadSettings.threads(), !waiting.isEmpty {
            let (id, continuation) = waiting.removeFirst(); active.insert(id); continuation.resume()
        }
    }
}

enum CacheDownloader {
    private struct RangeInfo {
        let start: Int64, end: Int64, total: Int64
        init?(_ response: HTTPURLResponse) {
            guard let text = response.value(forHTTPHeaderField: "Content-Range"), text.hasPrefix("bytes ") else { return nil }
            let fields = text.dropFirst(6).split(whereSeparator: { $0 == "-" || $0 == "/" })
            guard fields.count == 3, let start = Int64(fields[0]), let end = Int64(fields[1]), let total = Int64(fields[2]),
                  start >= 0, end >= start, total > end else { return nil }
            self.start = start; self.end = end; self.total = total
        }
    }
    static func download(_ url: URL, headers: [String: String], maximumBytes: Int64) async throws -> (URL, HTTPURLResponse) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 90; config.timeoutIntervalForResource = 300
        config.httpMaximumConnectionsPerHost = 5
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        var base = URLRequest(url: url); base.allHTTPHeaderFields = headers
        base.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let threads = CacheDownloadSettings.threads()
        if threads == 1 { return try await transfer(base, session: session) }
        var probe = base; probe.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        let (sample, response) = try await transfer(probe, session: session)
        // A server that ignores Range already returned the complete file.
        if response.statusCode == 200 { return (sample, response) }
        defer { try? FileManager.default.removeItem(at: sample) }
        guard response.statusCode == 206, let size = RangeInfo(response), size.start == 0, size.end == 0 else {
            return try await transfer(base, session: session)
        }
        guard size.total <= maximumBytes else { throw AppFailure.message("音频超过缓存上限") }
        let mime = response.mimeType ?? ""
        guard !mime.contains("text"), !mime.contains("json"), !mime.contains("mpegurl") else { throw AppFailure.message("缓存地址没有返回音频") }
        if size.total < 262144 { return try await transfer(base, session: session) }
        let result = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".cache-download")
        FileManager.default.createFile(atPath: result.path, contents: nil)
        do {
            let output = try FileHandle(forWritingTo: result)
            defer { try? output.close() }
            try output.truncate(atOffset: UInt64(size.total))
            let etag = response.value(forHTTPHeaderField: "ETag")
            let validator = etag.flatMap { $0.hasPrefix("W/") ? nil : $0 } ?? response.value(forHTTPHeaderField: "Last-Modified")
            try await withThrowingTaskGroup(of: Void.self) { group in
                for index in 0..<threads {
                    let start = size.total * Int64(index) / Int64(threads)
                    let end = size.total * Int64(index + 1) / Int64(threads) - 1
                    group.addTask {
                        var request = base; request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
                        if let validator { request.setValue(validator, forHTTPHeaderField: "If-Range") }
                        let (part, reply) = try await transfer(request, session: session)
                        defer { try? FileManager.default.removeItem(at: part) }
                        guard reply.statusCode == 206, let range = RangeInfo(reply), range.start == start, range.end == end, range.total == size.total,
                              (try part.resourceValues(forKeys: [.fileSizeKey])).fileSize == Int(end - start + 1),
                              etag == nil || reply.value(forHTTPHeaderField: "ETag") == etag else { throw AppFailure.message("服务器分段响应不完整") }
                        // Each worker writes to its own handle at a disjoint offset.
                        let input = try FileHandle(forReadingFrom: part), writer = try FileHandle(forWritingTo: result)
                        defer { try? input.close(); try? writer.close() }
                        try writer.seek(toOffset: UInt64(start))
                        while let data = try input.read(upToCount: 131072), !data.isEmpty {
                            try Task.checkCancellation(); try writer.write(contentsOf: data)
                        }
                    }
                }
                try await group.waitForAll()
            }
            try Task.checkCancellation()
            var fields: [String: String] = ["Content-Length": String(size.total), "Content-Type": mime]
            if let etag { fields["ETag"] = etag }
            let complete = HTTPURLResponse(url: response.url ?? url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: fields)!
            return (result, complete)
        } catch {
            try? FileManager.default.removeItem(at: result)
            try Task.checkCancellation()
            // A changed file or a server with broken range support is retried
            // as one complete response, never assembled from inconsistent parts.
            return try await transfer(base, session: session)
        }
    }
    private static func transfer(_ request: URLRequest, session: URLSession) async throws -> (URL, HTTPURLResponse) {
        let id = UUID()
        try await CacheTransferGate.shared.acquire(id)
        do {
            try Task.checkCancellation()
            let (file, response) = try await session.download(for: request)
            await CacheTransferGate.shared.release(id)
            guard let http = response as? HTTPURLResponse else { try? FileManager.default.removeItem(at: file); throw AppFailure.message("无效缓存响应") }
            return (file, http)
        } catch { await CacheTransferGate.shared.release(id); throw error }
    }
}
