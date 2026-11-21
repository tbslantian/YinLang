import Foundation

final class AudioProbe: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private var continuation: CheckedContinuation<Void, Error>?
    private var session: URLSession?
    private var prefix = Data()
    private var response: HTTPURLResponse?
    private let stateLock = NSLock()
    private var finished = false
    private var length: Int64?
    var totalBytes: Int64? { stateLock.withLock { length } }
    private let quality: String
    private let downloadOnly: Bool
    init(quality: String, downloadOnly: Bool = false) { self.quality = quality; self.downloadOnly = downloadOnly }
    func verify(_ url: URL, headers: [String: String]) async throws {
        try await withTaskCancellationHandler(operation: {
          try Task.checkCancellation()
          try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 25
            let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
            let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
            var request = URLRequest(url: url); request.allHTTPHeaderFields = headers; request.setValue("bytes=0-16383", forHTTPHeaderField: "Range")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            let started = stateLock.withLock {
                guard !finished else { return false }
                self.continuation = continuation; self.session = session
                return true
            }
            if started { session.dataTask(with: request).resume() }
            else { session.invalidateAndCancel(); continuation.resume(throwing: CancellationError()) }
          }
          try Task.checkCancellation()
        }, onCancel: { self.finish(.failure(CancellationError())) })
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, [200,206].contains(http.statusCode) else { completionHandler(.cancel); finish(.failure(AppFailure.message("音频地址 HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)"))); return }
        self.response = http; completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) { prefix.append(data.prefix(max(0, 16384 - prefix.count))); if prefix.count >= 16384 { inspect() } }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { if stateLock.withLock({ finished }) { return }; if let error { finish(.failure(error)) } else { inspect() } }
    private func inspect() {
        guard !prefix.isEmpty else { finish(.failure(AppFailure.message("音源返回空文件"))); return }
        let text = String(decoding: prefix.prefix(256), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let mime = response?.mimeType ?? ""
        if downloadOnly && (text.hasPrefix("#extm3u") || mime.lowercased().contains("mpegurl")) {
            finish(.failure(AppFailure.message("此音质返回的是流媒体，暂不能推送文件下载"))); return
        }
        if text.hasPrefix("<") || text.hasPrefix("{") || text.hasPrefix("[") || mime.contains("json") || mime.contains("html") {
            finish(.failure(AppFailure.message("音源返回了网页或错误信息"))); return
        }
        let lossless = prefix.starts(with: Data("fLaC".utf8)) || prefix.starts(with: Data("RIFF".utf8))
        let lossy = prefix.starts(with: Data("ID3".utf8)) || (prefix.count > 1 && prefix[0] == 255 && prefix[1] & 224 == 224) || prefix.starts(with: Data("OggS".utf8))
        if ["flac", "flac24bit", "hires", "master"].contains(quality) && lossy { finish(.failure(AppFailure.message("音源返回了有损音频，与所选音质不符"))); return }
        if ["128k", "192k", "320k"].contains(quality) && lossless { finish(.failure(AppFailure.message("音源返回档位与请求不符"))); return }
        let bytes: Int64?
        if response?.statusCode == 206 {
            // Content-Length on a range response is only the probe fragment.
            bytes = response?.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last.flatMap { Int64($0) }
        } else { bytes = response.flatMap { $0.expectedContentLength > 0 ? $0.expectedContentLength : nil } }
        stateLock.withLock { length = bytes.flatMap { $0 > 0 ? $0 : nil } }
        finish(.success(()))
    }
    private func finish(_ result: Result<Void, Error>) {
        let pending = stateLock.withLock { () -> (CheckedContinuation<Void, Error>?, URLSession?) in
            guard !finished else { return (nil, nil) }
            finished = true
            let pending = (continuation, session); continuation = nil; session = nil
            return pending
        }
        pending.1?.invalidateAndCancel(); pending.0?.resume(with: result)
    }
}
