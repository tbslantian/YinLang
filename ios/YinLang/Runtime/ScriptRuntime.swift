import Foundation
import WebKit

@MainActor
final class ScriptRuntime: NSObject, WKUIDelegate, WKNavigationDelegate, WKScriptMessageHandler {
    private var web: WKWebView!
    private var ready: CheckedContinuation<Void, Error>?
    private var calls: [Int: CheckedContinuation<Any, Error>] = [:]
    private var deadlines: [Int: Task<Void, Never>] = [:]
    private var nextID = 0
    private var scripts = ""
    private let scope: String
    private var actualAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile"
    private let noRedirect = URLSession(configuration: .default, delegate: RedirectPolicy(), delegateQueue: nil)
    private var networkTasks: [URLSessionDataTask] = []
    private var probes: [WebProbe] = []
    var update: ((String, String) -> Void)?
    // Results arrive through Native.ready/result, never evaluateJavaScript's
    // return-value conversion (which cannot bridge Promises or functions).
    static func nativeCommand(_ script: String) -> String {
        "(() => {\n" + script + "\n; return null; })()"
    }
    init(scope: String) {
        self.scope = scope; super.init()
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        // A weak handler prevents WKUserContentController retaining the runtime forever.
        config.userContentController.add(WeakScriptHandler(self), name: "native")
        web = WKWebView(frame: .zero, configuration: config); web.uiDelegate = self; web.navigationDelegate = self
    }
    func start(_ scripts: String) async throws {
        self.scripts = scripts
        try await withCheckedThrowingContinuation { continuation in
            ready = continuation
            web.loadHTMLString("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; base-uri 'none'\"><title>YinLang Runtime</title>", baseURL: URL(string: "https://runtime.yinlang.invalid/"))
        }
    }
    func call(_ expression: String, timeout: UInt64 = 120) async throws -> Any {
        nextID += 1; let id = nextID
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                calls[id] = continuation
                deadlines[id] = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: timeout * 1_000_000_000)
                    guard !Task.isCancelled else { return }; self?.finish(id, .failure(AppFailure.message("音源响应超时")))
                }
                web.evaluateJavaScript(Self.nativeCommand("SoundShelfRuntime.execute(\(id),\(quote(expression)))")) { [weak self] _, error in
                    if let error { self?.finish(id, .failure(error)) }
                }
            }
        }, onCancel: { Task { @MainActor [weak self] in self?.finish(id, .failure(CancellationError())) } })
    }
    func close() {
        web.stopLoading(); web.configuration.userContentController.removeScriptMessageHandler(forName: "native")
        networkTasks.forEach { $0.cancel() }; networkTasks.removeAll(); probes.forEach { $0.close() }; probes.removeAll()
        noRedirect.invalidateAndCancel()
        ready?.resume(throwing: CancellationError()); ready = nil
        for id in Array(calls.keys) { finish(id, .failure(CancellationError())) }
    }
    private func finish(_ id: Int, _ result: Result<Any, Error>) {
        deadlines.removeValue(forKey: id)?.cancel(); calls.removeValue(forKey: id)?.resume(with: result)
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let hostURL = Bundle.main.url(forResource: "plugin-host", withExtension: "js", subdirectory: "Runtime"),
              let host = try? String(contentsOf: hostURL, encoding: .utf8) else { ready?.resume(throwing: AppFailure.message("缺少插件运行资源，请执行 prepare-resources")); ready = nil; return }
        let bridge = """
        globalThis.Native = new Proxy({}, {get: (_, method) => (...args) => {
          if (['ready','result','networkAsync','updateAlert','notice'].includes(method)) {
            webkit.messageHandlers.native.postMessage({method,args}); return;
          }
          return prompt('__yinlang_native__', JSON.stringify({method,args}));
        }});
        """
        webView.evaluateJavaScript("navigator.userAgent") { [weak self] value, _ in
            guard let self else { return }; self.actualAgent = value as? String ?? self.actualAgent
            webView.evaluateJavaScript(Self.nativeCommand(bridge + "\n" + host + "\nSoundShelfRuntime.initialize(" + quote(self.scripts) + ");")) { [weak self] _, error in
                if let error { self?.ready?.resume(throwing: error); self?.ready = nil }
            }
        }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { close() }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(navigationAction.request.url?.host == "runtime.yinlang.invalid" || navigationAction.request.url?.scheme == "about" ? .allow : .cancel)
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? JSONObject, let args = body["args"] as? [Any] else { return }
        switch body.string("method") {
        case "ready":
            let error = args.first as? String ?? ""
            if error.isEmpty { ready?.resume() } else { ready?.resume(throwing: AppFailure.message(error)) }; ready = nil
        case "result":
            guard let raw = (args.first as? String)?.data(using: .utf8), let result = try? JSONSerialization.jsonObject(with: raw) as? JSONObject, let id = result["id"] as? Int else { return }
            if let error = result["error"] as? String { finish(id, .failure(AppFailure.message(error))) } else { finish(id, .success(result["value"] ?? NSNull())) }
        case "networkAsync":
            guard args.count == 3, let id = args[0] as? Int else { return }
            network(args[1] as? String ?? "", args[2] as? String ?? "{}") { [weak self] raw in
                self?.web.evaluateJavaScript(Self.nativeCommand("networkComplete(\(id),\(quote(raw)))"), completionHandler: nil)
            }
        case "updateAlert": if args.count == 2 { update?(args[0] as? String ?? "", args[1] as? String ?? "") }
        default: break
        }
    }
    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        guard prompt == "__yinlang_native__", frame.isMainFrame,
              let bytes = defaultText?.data(using: .utf8), let body = try? JSONSerialization.jsonObject(with: bytes) as? JSONObject,
              let args = body["args"] as? [Any] else { completionHandler(nil); return }
        func str(_ n: Int) -> String { args.indices.contains(n) ? args[n] as? String ?? "" : "" }
        func bool(_ n: Int) -> Bool { args.indices.contains(n) ? args[n] as? Bool ?? false : false }
        let defaults = UserDefaults.standard
        let method = body.string("method")
        if method == "network" { network(str(0), str(1), completion: completionHandler); return }
        if method == "networkBatch" {
            let rows = str(0).data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [JSONObject] } ?? []
            var results = [String](repeating: "", count: rows.count)
            func next(_ index: Int) {
                if index == rows.count { completionHandler(jsonText(["values": results])); return }
                network(rows[index].string("url"), jsonText(rows[index].object("options"))) { value in results[index] = value; next(index + 1) }
            }
            next(0); return
        }
        if method == "probe" {
            let probe = WebProbe(); probes.append(probe)
            let options = str(1).data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSONObject } ?? [:]
            probe.open(str(0), options: options, sniff: bool(2)) { [weak self, weak probe] values in
                completionHandler(jsonText(["values": values])); self?.probes.removeAll { $0 === probe }
            }; return
        }
        do {
            var result = ""
            switch method {
            case "get", "readCache": result = defaults.string(forKey: scope + ":" + str(0)) ?? ""
            case "put", "writeCache": defaults.set(str(1), forKey: scope + ":" + str(0)); defaults.set(Date().timeIntervalSince1970, forKey: scope + ":age:" + str(0))
            case "cacheAge": result = String((Date().timeIntervalSince1970 - defaults.double(forKey: scope + ":age:" + str(0))) * 1000)
            case "uuid": result = UUID().uuidString
            case "agent": result = bool(0) ? "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15" : actualAgent
            case "digest": result = NativeCrypto.digest(str(0), str(1))
            case "decode64": result = Data(base64Encoded: str(0)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
            case "encode64": result = Data(str(0).utf8).base64EncodedString()
            case "random":
                var bytes = [UInt8](repeating: 0, count: min(4096, max(0, args.first as? Int ?? 0)))
                _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes); result = Data(bytes).base64EncodedString()
            case "decrypt":
                let hex = str(1); var key = Data(); var index = hex.startIndex
                while index < hex.endIndex { let end = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex; guard let byte = UInt8(hex[index..<end], radix: 16) else { throw AppFailure.message("AES 密钥无效") }; key.append(byte); index = end }
                result = String(data: try NativeCrypto.aes(Data(base64Encoded: str(0)) ?? Data(), key: key, iv: Data(), mode: "ECB", decrypt: true), encoding: .utf8) ?? ""
            case "encrypt": result = jsonText(["value": try NativeCrypto.aes(Data(base64Encoded: str(1)) ?? Data(), key: Data(base64Encoded: str(2)) ?? Data(), iv: Data(base64Encoded: str(3)) ?? Data(), mode: str(0).uppercased()).base64EncodedString()])
            case "rsa": result = jsonText(["value": try NativeCrypto.rsa(Data(base64Encoded: str(0)) ?? Data(), pem: str(1)).base64EncodedString()])
            case "zip", "inflateCatalog":
                let data = Data(base64Encoded: str(0)) ?? Data()
                let output = try method == "inflateCatalog" ? PluginArchive.decompress(data, partial: bool(1)) : (bool(1) ? PluginArchive.decompress(data) : PluginArchive.compress(data))
                result = jsonText(["value": output.base64EncodedString()])
            case "cookies":
                web.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                    let host = URL(string: str(0))?.host ?? ""
                    completionHandler(cookies.filter { host == $0.domain || host.hasSuffix("." + $0.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))) }.map { $0.name + "=" + $0.value }.joined(separator: "; "))
                }; return
            case "sleep":
                DispatchQueue.main.asyncAfter(deadline: .now() + min(5, Double(args.first as? Int ?? 0) / 1000)) { completionHandler("") }; return
            default: throw AppFailure.message("未知插件调用：" + method)
            }
            completionHandler(result)
        } catch { completionHandler(jsonText(["error": error.localizedDescription])) }
    }
    private func network(_ address: String, _ raw: String, completion: @escaping (String) -> Void) {
        guard let url = URL(string: address), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { completion(jsonText(["error": "仅支持 HTTP/HTTPS 地址"])); return }
        let options = raw.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSONObject } ?? [:]
        var request = URLRequest(url: url); request.httpMethod = options.string("method").isEmpty ? "GET" : options.string("method")
        if scope.hasPrefix("catalog-refresh-") { request.cachePolicy = .reloadIgnoringLocalCacheData }
        request.allHTTPHeaderFields = options["headers"] as? [String: String]
        if request.value(forHTTPHeaderField: "User-Agent") == nil { request.setValue(actualAgent, forHTTPHeaderField: "User-Agent") }
        if options["statusOnly"] as? Bool == true { request.httpMethod = "HEAD" }
        request.httpBody = (options["body"] as? String)?.data(using: .utf8); request.timeoutInterval = 30
        let transport = options["follow"] as? Bool == false ? noRedirect : URLSession.shared
        web.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
        guard let self else { completion(jsonText(["error":"插件运行环境已关闭"])); return }
        var request = request
        if request.value(forHTTPHeaderField: "Cookie") == nil {
            let host = url.host ?? ""
            let matching = cookies.filter { cookie in let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: ".")); return (host == domain || host.hasSuffix("." + domain)) && url.path.hasPrefix(cookie.path) && (!cookie.isSecure || url.scheme == "https") && (cookie.expiresDate == nil || cookie.expiresDate! > Date()) }
            if !matching.isEmpty { request.setValue(HTTPCookie.requestHeaderFields(with: matching)["Cookie"], forHTTPHeaderField: "Cookie") }
        }
        let task = transport.dataTask(with: request) { data, response, error in
            let result: JSONObject
            if let error { result = ["error": error.localizedDescription] }
            else if let response = response as? HTTPURLResponse {
                let data = data ?? Data()
                var encoding = String.Encoding.utf8
                let wanted = options.string("encoding").lowercased()
                if ["gbk", "gb2312", "gb18030"].contains(wanted) { encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))) }
                result = ["status": response.statusCode, "url": response.url?.absoluteString ?? address,
                          "headers": Dictionary(response.allHeaderFields.map { (String(describing: $0.key).lowercased(), String(describing: $0.value)) }, uniquingKeysWith: { _, last in last }),
                          "body": String(data: data, encoding: encoding) ?? String(decoding: data, as: UTF8.self), "raw": data.base64EncodedString()]
            } else { result = ["error": "网络未返回响应"] }
            DispatchQueue.main.async { completion(jsonText(result)) }
        }
        self.networkTasks.removeAll { $0.state == .completed }; self.networkTasks.append(task); task.resume()
        }
    }
}

private final class RedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: ScriptRuntime?
    init(_ target: ScriptRuntime) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { target?.userContentController(userContentController, didReceive: message) }
}

@MainActor
final class WebProbe: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    private var web: WKWebView?
    private var completion: (([String]) -> Void)?
    private var values: [String] = []
    private var poll: Task<Void, Never>?
    func open(_ address: String, options: JSONObject, sniff: Bool, completion: @escaping ([String]) -> Void) {
        guard let url = URL(string: address), ["http", "https"].contains(url.scheme ?? "") else { completion([]); return }
        self.completion = completion
        let config = WKWebViewConfiguration(); config.mediaTypesRequiringUserActionForPlayback = .all
        config.userContentController.add(self, name: "probe")
        let interception = """
        const report = value => { if (typeof value === 'string') webkit.messageHandlers.probe.postMessage(value) };
        const open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(m,u,...a){ report(u); return open.call(this,m,u,...a) };
        const fetchOriginal = fetch; globalThis.fetch = function(u,o){ report(typeof u === 'string' ? u : u.url); return fetchOriginal(u,o) };
        new PerformanceObserver(items => items.getEntries().forEach(item => report(item.name))).observe({entryTypes:['resource']});
        """
        if sniff { config.userContentController.addUserScript(.init(source: interception, injectionTime: .atDocumentStart, forMainFrameOnly: false)) }
        let web = WKWebView(frame: .zero, configuration: config); self.web = web; web.navigationDelegate = self
        web.load(URLRequest(url: url))
        poll = Task { [weak self] in
            let timeout = max(1500, min(40000, options["timeout"] as? Int ?? 18000))
            for _ in 0..<max(1, timeout / 1000) {
                try? await Task.sleep(nanoseconds: 1_000_000_000); guard !Task.isCancelled, let self else { return }
                let fallback = sniff ? "Array.from(document.querySelectorAll('audio,video,source')).map(n=>n.currentSrc||n.src).join('\\n')" : "document.documentElement.outerHTML"
                let value = try? await self.web?.evaluateJavaScript(options["script"] as? String ?? fallback)
                if let text = value as? String, !text.isEmpty { self.add(text) }
            }
            self?.close()
        }
    }
    private func add(_ value: String) { if value.count <= 1_000_000, !values.contains(value) { values.append(value); if values.count > 512 { values.removeFirst() } } }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { if let value = message.body as? String { add(value) } }
    func close() { poll?.cancel(); web?.stopLoading(); web?.configuration.userContentController.removeScriptMessageHandler(forName: "probe"); web = nil; let done = completion; completion = nil; done?(values) }
}
