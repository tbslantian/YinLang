package app.soundshelf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

final class Http {
    record Response(int status, String body, String url, Map<String, String> headers, byte[] bytes) {}

    /** One runtime can issue several parallel requests; cancellation covers redirects too. */
    static final class Cancellation {
        private final java.util.Set<HttpURLConnection> connections = new java.util.HashSet<>();
        private volatile boolean cancelled;

        void check() throws IOException {
            if (cancelled) throw new java.io.InterruptedIOException("请求已取消");
        }
        synchronized void attach(HttpURLConnection connection) throws IOException {
            check();
            connections.add(connection);
        }
        synchronized void detach(HttpURLConnection connection) { connections.remove(connection); }
        void cancel() {
            java.util.List<HttpURLConnection> active;
            synchronized (this) {
                cancelled = true;
                active = java.util.List.copyOf(connections);
                connections.clear();
            }
            for (HttpURLConnection connection : active) connection.disconnect();
        }
    }

    static String base(String input) {
        URI uri = url(input.trim());
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("服务器地址不能包含查询参数或片段");
        }
        return uri.toString().replaceAll("/+$", "");
    }

    static URI url(String input) {
        URI uri = URI.create(input);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 地址");
        }
        return uri;
    }

    static String enc(String input) {
        try { return URLEncoder.encode(input, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    static byte[] bytes(InputStream input, int maximum) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count + output.size() > maximum) throw new IOException("文件超过大小限制");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static Response request(String address, String method, Map<String, String> headers, String body, boolean follow) throws IOException {
        return request(address, method, headers, body, follow, false, null, false);
    }

    static Response request(String address, String method, Map<String, String> headers, String body, boolean follow,
                            boolean statusOnly, String encoding, boolean pluginCookies) throws IOException {
        return request(address, method, headers, body, follow, statusOnly, encoding, pluginCookies, 0);
    }

    static Response request(String address, String method, Map<String, String> headers, String body, boolean follow,
                            boolean statusOnly, String encoding, boolean pluginCookies, int timeout) throws IOException {
        return request(address, method, headers, body, follow, statusOnly, encoding, pluginCookies, timeout, 0, null);
    }

    static Response request(String address, String method, Map<String, String> headers, String body, boolean follow,
                            boolean statusOnly, String encoding, boolean pluginCookies, int timeout, Cancellation cancellation) throws IOException {
        return request(address, method, headers, body, follow, statusOnly, encoding, pluginCookies, timeout, 0, cancellation);
    }

    static Response sample(String address, Map<String, String> headers, int timeout, int maximum) throws IOException {
        return request(address, "GET", headers, null, true, false, "", false, timeout, maximum, null);
    }

    private static Response request(String address, String method, Map<String, String> headers, String body, boolean follow,
                                    boolean statusOnly, String encoding, boolean pluginCookies, int timeout, int sampleLimit,
                                    Cancellation cancellation) throws IOException {
        URI current = url(address);
        for (int hop = 0; hop <= 5; hop++) {
            if (cancellation != null) cancellation.check();
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            try {
                if (cancellation != null) cancellation.attach(connection);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(timeout > 0 ? timeout : 10_000);
                connection.setReadTimeout(timeout > 0 ? timeout : 15_000);
                connection.setInstanceFollowRedirects(false);
                for (var header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if (pluginCookies && headers.keySet().stream().noneMatch(name -> "cookie".equalsIgnoreCase(name))) {
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(current.toString());
                    if (cookie != null) connection.setRequestProperty("Cookie", cookie);
                }
                if (body != null) {
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (var stream = connection.getOutputStream()) { stream.write(payload); }
                }
                int status = connection.getResponseCode();
                if (cancellation != null) cancellation.check();
                var responseHeaders = new LinkedHashMap<String, String>();
                for (var header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null) continue;
                    String key = header.getKey().toLowerCase(java.util.Locale.ROOT);
                    responseHeaders.put(key, String.join(", ", header.getValue()));
                    if (pluginCookies && "set-cookie".equals(key)) for (String cookie : header.getValue()) {
                        android.webkit.CookieManager.getInstance().setCookie(current.toString(), cookie);
                    }
                }
                String location = connection.getHeaderField("Location");
                if (follow && location != null && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                    URI next = url(current.resolve(location).toString());
                    if (!current.getHost().equalsIgnoreCase(next.getHost()) || current.getPort() != next.getPort()
                        || !current.getScheme().equalsIgnoreCase(next.getScheme())) {
                        if (sampleLimit == 0) headers = Collections.emptyMap();
                        else {
                            var forwarded = new LinkedHashMap<>(headers);
                            forwarded.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie"));
                            headers = forwarded;
                        }
                    }
                    if (status == 303 || ((status == 301 || status == 302) && method.equals("POST"))) {
                        body = null;
                        method = "GET";
                    }
                    current = next;
                    continue;
                }
                if (statusOnly) return new Response(status, "", current.toString(), responseHeaders, new byte[0]);
                if (sampleLimit > 0) {
                    byte[] prefix = new byte[0];
                    if (status >= 200 && status < 300) try (var stream = connection.getInputStream()) {
                        prefix = sampleBytes(stream, sampleLimit);
                    }
                    return new Response(status, "", current.toString(), responseHeaders, prefix);
                }
                try (var stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    byte[] result = stream == null ? new byte[0] : bytes(stream, 8_000_000);
                    if (cancellation != null) cancellation.check();
                    String charset = encoding;
                    if (charset == null || charset.trim().isEmpty()) {
                        var matcher = java.util.regex.Pattern.compile("charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(responseHeaders.getOrDefault("content-type", "") + " "
                                + new String(result, 0, Math.min(result.length, 2048), StandardCharsets.US_ASCII));
                        charset = matcher.find() ? matcher.group(1) : "UTF-8";
                    }
                    Charset decoder;
                    try { decoder = Charset.forName(charset); } catch (Exception ignored) { decoder = StandardCharsets.UTF_8; }
                    return new Response(status, new String(result, decoder), current.toString(), responseHeaders, result);
                }
            } finally {
                if (cancellation != null) cancellation.detach(connection);
                connection.disconnect();
            }
        }
        throw new IOException("重定向次数过多");
    }

    private static byte[] sampleBytes(InputStream input, int length) throws IOException {
        byte[] sample = new byte[length];
        int used = 0;
        while (used < length) {
            int count = input.read(sample, used, length - used);
            if (count < 0) break;
            used += count;
        }
        return used == length ? sample : java.util.Arrays.copyOf(sample, used);
    }

    static JSONObject json(String address, Map<String, String> headers, JSONObject body) throws Exception {
        var response = request(address, body == null ? "GET" : "POST", headers, body == null ? null : body.toString(), false);
        if (response.status() < 200 || response.status() >= 300) {
            String message = "";
            try { message = new JSONObject(response.body()).optString("error"); } catch (Exception ignored) {}
            throw new IOException("HTTP " + response.status() + (message.trim().isEmpty() ? "" : " · " + message));
        }
        return new JSONObject(response.body());
    }
}
