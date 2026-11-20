import Foundation

final class ArtworkFixtureProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var bytes = Data()
    private static var count = 0
    static var calls: Int { lock.lock(); defer { lock.unlock() }; return count }
    static func reset(_ image: Data) { lock.lock(); defer { lock.unlock() }; bytes = image; count = 0 }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "images.example.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.count += 1; let data = Self.bytes; Self.lock.unlock()
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"image/png", "Cache-Control":"no-store"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
