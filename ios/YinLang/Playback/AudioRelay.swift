import Foundation
import Network
import Darwin

// A per-session, random URL exposes only the selected audio. Credentials stay in
// the relay's outbound request, never in a receiver's media URL.
final class AudioRelay: @unchecked Sendable {
    private let queue = DispatchQueue(label: "app.yinlang.relay")
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private var source: URL?
    private var headers: [String: String] = [:]
    private let token = UUID().uuidString
    private var tasks: [URLSessionTask] = []
    func start(source: URL, headers: [String: String]) async throws -> URL {
        self.source = source; self.headers = headers
        let listener = try NWListener(using: .tcp, on: .any); self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }; self.connections.append(connection); connection.start(queue: self.queue); self.receive(connection, accumulated: Data())
        }
        guard let address = Self.localAddress() else { listener.cancel(); throw AppFailure.message("找不到局域网 IPv4 地址") }
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: listener.stateUpdateHandler = nil; if let port = listener.port { continuation.resume(returning: port.rawValue) } else { continuation.resume(throwing: AppFailure.message("无法读取转发端口")) }
                case .failed(let error): listener.stateUpdateHandler = nil; continuation.resume(throwing: error)
                case .cancelled: listener.stateUpdateHandler = nil; continuation.resume(throwing: CancellationError())
                default: break
                }
            }
            listener.start(queue: queue)
        }
        return URL(string: "http://\(address):\(port)/\(token)/audio")!
    }
    static func localAddress() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return nil }; defer { freeifaddrs(interfaces) }
        var cursor = interfaces
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET), String(cString: entry.pointee.ifa_name) == "en0" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 { return String(cString: host) }
        }
        return nil
    }
    private func receive(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, ended, error in
            guard let self else { return }; var bytes = accumulated; bytes.append(data ?? Data())
            if bytes.count > 16384 || error != nil { connection.cancel(); return }
            guard let request = String(data: bytes, encoding: .utf8), request.contains("\r\n\r\n") else { if !ended { self.receive(connection, accumulated: bytes) } else { connection.cancel() }; return }
            self.respond(connection, request: request)
        }
    }
    private func respond(_ connection: NWConnection, request: String) {
        let lines = request.components(separatedBy: "\r\n"), first = lines.first?.split(separator: " ") ?? []
        guard first.count >= 2, ["GET", "HEAD"].contains(String(first[0])), first[1] == "/\(token)/audio", let source else { connection.send(content: Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8), completion: .contentProcessed { _ in connection.cancel() }); return }
        let range = lines.first { $0.lowercased().hasPrefix("range:") }.map { String($0.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
        if source.isFileURL {
            do {
                let size = (try source.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                var start = 0, end = max(0, size - 1)
                if let range, range.hasPrefix("bytes=") { let numbers = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false); if numbers.count == 2 { if numbers[0].isEmpty, let suffix = Int(numbers[1]) { start = max(0, size - suffix) } else { start = Int(numbers[0]) ?? 0; end = min(end, Int(numbers[1]) ?? end) } } }
                guard start >= 0, start <= end, end < size else { connection.cancel(); return }
                let signatureFile = try FileHandle(forReadingFrom: source); let signature = try signatureFile.read(upToCount: 16) ?? Data(); try signatureFile.close()
                let mime = signature.starts(with: Data("fLaC".utf8)) ? "audio/flac" : signature.starts(with: Data("RIFF".utf8)) ? "audio/wav" : signature.starts(with: Data("OggS".utf8)) ? "audio/ogg" : signature.count >= 8 && String(decoding: signature[4..<8], as: UTF8.self) == "ftyp" ? "audio/mp4" : "audio/mpeg"
                let extra = range == nil ? "" : "Content-Range: bytes \(start)-\(end)/\(size)\r\n"
                let header = "HTTP/1.1 \(range == nil ? "200 OK" : "206 Partial Content")\r\nContent-Type: \(mime)\r\nContent-Length: \(end - start + 1)\r\nAccept-Ranges: bytes\r\n\(extra)Connection: close\r\n\r\n"
                let file = try FileHandle(forReadingFrom: source); try file.seek(toOffset: UInt64(start))
                connection.send(content: Data(header.utf8), completion: .contentProcessed { [weak self] _ in if first[0] == "HEAD" { try? file.close(); connection.cancel() } else { self?.sendFile(file, remaining: end - start + 1, connection: connection) } })
            } catch { connection.cancel() }
        } else {
            var outbound = URLRequest(url: source); outbound.allHTTPHeaderFields = headers; outbound.httpMethod = String(first[0]); if let range { outbound.setValue(range, forHTTPHeaderField: "Range") }
            let proxy = RelayStream(connection: connection); let session = URLSession(configuration: .ephemeral, delegate: proxy, delegateQueue: nil); proxy.session = session
            let task = session.dataTask(with: outbound); tasks.removeAll { $0.state == .completed }; tasks.append(task); task.resume()
        }
    }
    private func sendFile(_ file: FileHandle, remaining: Int, connection: NWConnection) {
        guard remaining > 0, let data = try? file.read(upToCount: min(65536, remaining)), !data.isEmpty else { try? file.close(); connection.cancel(); return }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in if error != nil { try? file.close(); connection.cancel() } else { self?.sendFile(file, remaining: remaining - data.count, connection: connection) } })
    }
    func stop() { listener?.cancel(); listener = nil; queue.async { self.connections.forEach { $0.cancel() }; self.connections.removeAll(); self.tasks.forEach { $0.cancel() }; self.tasks.removeAll() } }
}

private final class RelayStream: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    let connection: NWConnection
    var session: URLSession?
    private var chunked = false
    init(connection: NWConnection) { self.connection = connection }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response = response as? HTTPURLResponse else { completionHandler(.cancel); connection.cancel(); return }
        let length = response.value(forHTTPHeaderField: "Content-Length"); chunked = length == nil && dataTask.originalRequest?.httpMethod != "HEAD"
        var header = "HTTP/1.1 \(response.statusCode) \(response.statusCode == 206 ? "Partial Content" : "OK")\r\nConnection: close\r\n"
        for key in ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"] { if let value = response.value(forHTTPHeaderField: key) { header += "\(key): \(value)\r\n" } }
        if chunked { header += "Transfer-Encoding: chunked\r\n" }; header += "\r\n"
        connection.send(content: Data(header.utf8), completion: .contentProcessed { error in completionHandler(error == nil ? .allow : .cancel) })
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        dataTask.suspend(); var output = Data()
        if chunked { output.append(Data(String(data.count, radix: 16).utf8)); output.append(Data("\r\n".utf8)) }; output.append(data); if chunked { output.append(Data("\r\n".utf8)) }
        connection.send(content: output, completion: .contentProcessed { error in if error == nil { dataTask.resume() } else { dataTask.cancel() } })
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        connection.send(content: chunked && error == nil ? Data("0\r\n\r\n".utf8) : nil, isComplete: true, completion: .contentProcessed { _ in self.connection.cancel() }); session.finishTasksAndInvalidate(); self.session = nil
    }
}
