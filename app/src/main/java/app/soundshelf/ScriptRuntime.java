package app.soundshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

final class ScriptRuntime {
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(8);
    private static final String DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String scope;
    private final WebView view;
    private final String agent;
    private final boolean reusable;
    private final long timeout;
    private final long created = SystemClock.elapsedRealtime();
    private final Http.Cancellation network = new Http.Cancellation();
    private final java.util.Set<Probe> probes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Runnable deadline = () -> finish(null, "音源响应超时");
    private final Runnable idleDeadline = this::cancel;
    private Callback pending;
    private volatile boolean closed;
    private boolean initialized, starting;
    private String expression;
    private long callId;

    interface Callback { void complete(Object value, String error); }

    ScriptRuntime(Context context, String scope, String scripts, String expression, Callback callback) {
        this(context, scope, scripts, expression, 120_000, callback);
    }

    ScriptRuntime(Context context, String scope, String scripts, String expression, long timeout, Callback callback) {
        this(context, scope, scripts, expression, timeout, false, callback);
    }

    ScriptRuntime(Context context, String scope, String scripts, String expression, long timeout, boolean reusable, Callback callback) {
        this.context = context;
        this.scope = scope;
        this.timeout = timeout;
        this.reusable = reusable;
        view = new WebView(context.getApplicationContext());
        agent = view.getSettings().getUserAgentString();
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setBlockNetworkLoads(true);
        view.addJavascriptInterface(new Bridge(), "Native");
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView source, String address) {
                if (closed || starting || !address.startsWith("https://runtime.soundshelf.invalid/")) return;
                starting = true;
                try {
                    String host = asset(context, "plugin-host.js");
                    view.evaluateJavascript(host, ignored -> {
                        if (closed) return;
                        view.evaluateJavascript("SoundShelfRuntime.initialize(" + JSONObject.quote(scripts) + ")", null);
                    });
                } catch (Exception error) {
                    finish(null, message(error));
                }
            }
            @Override public boolean onRenderProcessGone(WebView web, android.webkit.RenderProcessGoneDetail detail) {
                finish(null, "插件运行进程退出");
                return true;
            }
        });
        view.loadDataWithBaseURL("https://runtime.soundshelf.invalid/",
            "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-eval' 'unsafe-inline'; base-uri 'none'\"><title>插件运行环境</title>",
            "text/html", "UTF-8", null);
        execute(expression, callback);
    }

    boolean idle() { return !closed && initialized && pending == null; }

    void execute(String code, Callback callback) {
        if (closed || pending != null) throw new IllegalStateException("插件运行环境不可用");
        main.removeCallbacks(idleDeadline);
        pending = callback;
        expression = code;
        callId++;
        main.postDelayed(deadline, timeout);
        if (initialized) dispatch();
    }

    private void dispatch() {
        view.evaluateJavascript("SoundShelfRuntime.execute(" + callId + "," + JSONObject.quote(expression) + ")", null);
    }

    private void complete(Object value, String error) {
        if (!reusable) { finish(value, error); return; }
        main.removeCallbacks(deadline);
        Callback callback = pending;
        pending = null;
        expression = null;
        for (Probe probe : new ArrayList<>(probes)) probe.stop();
        // Keep just one idle runtime, and release it when discovery is no longer used.
        main.postDelayed(idleDeadline, 60_000);
        if (callback != null) callback.complete(value, error);
    }

    static String asset(Context context, String name) throws Exception {
        try (var stream = context.getAssets().open(name)) {
            return new String(Http.bytes(stream, 4_000_000), StandardCharsets.UTF_8);
        }
    }

    void cancel() { finish(null, null, false); }

    private void finish(Object value, String error) { finish(value, error, true); }
    private void finish(Object value, String error, boolean notify) {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        network.cancel();
        for (Probe probe : new ArrayList<>(probes)) probe.stop();
        view.removeJavascriptInterface("Native");
        view.destroy();
        Callback callback = pending;
        pending = null;
        expression = null;
        if (notify && callback != null) callback.complete(value, error);
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private final class Bridge {
        @JavascriptInterface public void ready(String error) {
            main.post(() -> {
                if (closed || initialized) return;
                if (!error.isEmpty()) { finish(null, error); return; }
                initialized = true;
                if (reusable) android.util.Log.d("BookDiscovery", "plugin initialization "
                    + (SystemClock.elapsedRealtime() - created) + "ms");
                dispatch();
            });
        }
        @JavascriptInterface public void updateAlert(String log, String url) {
            main.post(() -> SourceUpdates.get(context).receiveAlert(scope, log, url));
        }
        @JavascriptInterface public void result(String raw) {
            main.post(() -> {
                try {
                    if (closed || pending == null) return;
                    JSONObject value = new JSONObject(raw);
                    if (value.optLong("id", -1) != callId) return;
                    complete(value.opt("value"), value.has("error") ? value.optString("error") : null);
                } catch (Exception error) { finish(null, message(error)); }
            });
        }
        @JavascriptInterface public String network(String url, String raw) {
            try {
                JSONObject config = new JSONObject(raw);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", agent(config.optBoolean("desktop")));
                JSONObject custom = config.optJSONObject("headers");
                if (custom != null) for (var iter = custom.keys(); iter.hasNext();) {
                    String key = iter.next();
                    if ("user-agent".equalsIgnoreCase(key)) headers.remove("User-Agent");
                    headers.put(key, custom.getString(key));
                }
                var response = Http.request(url, config.optString("method", "GET"), headers,
                    config.has("body") && !config.isNull("body") ? config.getString("body") : null,
                    config.optBoolean("follow", true), config.optBoolean("statusOnly"), config.optString("encoding", ""),
                    !scope.equals("catalog"), 0, network);
                JSONObject result = new JSONObject().put("status", response.status()).put("body", response.body())
                    .put("headers", new JSONObject(response.headers())).put("url", response.url());
                if (config.optBoolean("binary")) result.put("raw", Base64.encodeToString(response.bytes(), Base64.NO_WRAP));
                return result.toString();
            } catch (Exception error) { return error(message(error)); }
        }
        @JavascriptInterface public void networkAsync(int id, String url, String options) {
            NETWORK.execute(() -> {
                String response = network(url, options);
                main.post(() -> { if (!closed) view.evaluateJavascript("networkComplete(" + id + "," + JSONObject.quote(response) + ")", null); });
            });
        }
        @JavascriptInterface public String networkBatch(String raw, int concurrency) {
            try {
                JSONArray requests = new JSONArray(raw);
                var results = new JSONArray();
                int width = Math.max(1, Math.min(concurrency, 6));
                for (int start = 0; start < requests.length(); start += width) {
                    var batch = new ArrayList<Future<String>>(width);
                    for (int index = start; index < Math.min(start + width, requests.length()); index++) {
                        JSONObject request = requests.getJSONObject(index);
                        String url = request.getString("url"), options = request.getJSONObject("options").toString();
                        batch.add(NETWORK.submit(() -> network(url, options)));
                    }
                    for (Future<String> request : batch) results.put(request.get());
                }
                return new JSONObject().put("values", results).toString();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return error("请求已中断");
            } catch (Exception failure) { return error(message(failure)); }
        }
        @JavascriptInterface public String probe(String url, String options, boolean sniff) {
            try {
                Http.url(url);
                JSONObject config = new JSONObject(options);
                int limit = Math.min(40_000, Math.max(1500, config.optInt("timeout", 18_000)));
                var probe = new Probe(url, config, sniff, limit);
                probes.add(probe);
                main.post(probe::open);
                probe.done.await(limit + 2000, TimeUnit.MILLISECONDS);
                synchronized (probe.values) { return new JSONObject().put("values", new JSONArray(probe.values)).toString(); }
            } catch (Exception error) { return error(message(error)); }
        }
        @JavascriptInterface public String cookies(String url) {
            try { Http.url(url); String value = CookieManager.getInstance().getCookie(url); return value == null ? "" : value; }
            catch (Exception ignored) { return ""; }
        }
        @JavascriptInterface public String get(String key) {
            return context.getSharedPreferences("plugin-" + scope, Context.MODE_PRIVATE).getString(key, "");
        }
        @JavascriptInterface public void put(String key, String value) {
            if (key.length() <= 256 && value.length() <= 500_000) {
                context.getSharedPreferences("plugin-" + scope, Context.MODE_PRIVATE).edit().putString(key, value).apply();
            }
        }
        private MediaCache.Bucket bookCache() { return MediaCache.get(context).bucket(true); }
        private String cacheKey(String key) { return "plugin:" + scope + ":" + key; }
        @JavascriptInterface public String readCache(String key) {
            byte[] data = bookCache().read(cacheKey(key));
            return data == null || data.length < Long.BYTES ? null
                : new String(data, Long.BYTES, data.length - Long.BYTES, StandardCharsets.UTF_8);
        }
        @JavascriptInterface public void writeCache(String key, String value) {
            if (key.length() > 256 || value.length() > 500_000) return;
            var cache = bookCache();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            byte[] data = java.nio.ByteBuffer.allocate(Long.BYTES + bytes.length).putLong(System.currentTimeMillis()).put(bytes).array();
            cache.write(cacheKey(key), data, cache.revision());
        }
        @JavascriptInterface public double cacheAge(String key) {
            byte[] data = bookCache().read(cacheKey(key));
            return data == null || data.length < Long.BYTES ? -1 : Math.max(0, System.currentTimeMillis() - java.nio.ByteBuffer.wrap(data).getLong());
        }
        @JavascriptInterface public String agent(boolean desktop) { return desktop ? DESKTOP : agent; }
        @JavascriptInterface public void notice(String value) { main.post(() -> Toast.makeText(context, value, Toast.LENGTH_SHORT).show()); }
        @JavascriptInterface public void sleep(int delay) { SystemClock.sleep(Math.max(0, Math.min(delay, 5000))); }
        @JavascriptInterface public String uuid() { return UUID.randomUUID().toString(); }
        @JavascriptInterface public String digest(String algorithm, String input) {
            if (!"MD5".equals(algorithm) && !"SHA-256".equals(algorithm)) return "";
            try {
                var text = new StringBuilder();
                for (byte value : MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8))) {
                    text.append(String.format(Locale.ROOT, "%02x", value & 255));
                }
                return text.toString();
            } catch (Exception ignored) { return ""; }
        }
        @JavascriptInterface public String decode64(String value) {
            try { return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8); }
            catch (Exception ignored) { return ""; }
        }
        @JavascriptInterface public String encode64(String value) {
            return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        @JavascriptInterface public String random(int length) {
            byte[] data = new byte[Math.max(0, Math.min(length, 4096))];
            new SecureRandom().nextBytes(data);
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }
        @JavascriptInterface public String decrypt(String value, String hex) {
            try {
                byte[] key = new byte[hex.length() / 2];
                for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
                return new String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), StandardCharsets.UTF_8);
            } catch (Exception ignored) { return ""; }
        }
        @JavascriptInterface public String encrypt(String mode, String value, String key, String iv) {
            try {
                mode = mode.toUpperCase(Locale.ROOT);
                boolean padded = mode.equals("ECB") || mode.equals("CBC");
                if (!padded && !java.util.Set.of("CTR", "CFB", "OFB").contains(mode)) return error("不支持的 AES 模式");
                var cipher = Cipher.getInstance("AES/" + mode + "/" + (padded ? "PKCS5Padding" : "NoPadding"));
                var secret = new SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES");
                if (mode.equals("ECB")) cipher.init(Cipher.ENCRYPT_MODE, secret);
                else cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(Base64.decode(iv, Base64.DEFAULT)));
                return resultBytes(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)));
            } catch (Exception failure) { return error("AES 加密失败"); }
        }
        @JavascriptInterface public String rsa(String value, String pem) {
            try {
                byte[] encoded = Base64.decode(pem.replaceAll("-----[^-]+-----|\\s", ""), Base64.DEFAULT);
                var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
                var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                return resultBytes(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)));
            } catch (Exception failure) { return error("RSA 加密失败"); }
        }
        @JavascriptInterface public String zip(String value, boolean inflate) {
            try {
                byte[] data = Base64.decode(value, Base64.DEFAULT);
                if (inflate) try (var input = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(data))) {
                    return resultBytes(Http.bytes(input, 4_000_000));
                }
                var output = new java.io.ByteArrayOutputStream();
                try (var compressed = new java.util.zip.DeflaterOutputStream(output)) { compressed.write(data); }
                return resultBytes(output.toByteArray());
            } catch (Exception failure) { return error("压缩处理失败"); }
        }
        @JavascriptInterface public String inflateCatalog(String value, boolean partial) {
            var inflater = new java.util.zip.Inflater();
            try (var output = new java.io.ByteArrayOutputStream()) {
                inflater.setInput(Base64.decode(value, Base64.DEFAULT));
                byte[] chunk = new byte[8192];
                while (!inflater.finished()) {
                    int count = inflater.inflate(chunk);
                    if (count == 0) {
                        if (partial && inflater.needsInput()) break;
                        throw new java.io.IOException("Invalid zlib data");
                    }
                    if (count + output.size() > 4_000_000) throw new java.io.IOException("Lyrics too large");
                    output.write(chunk, 0, count);
                }
                return resultBytes(output.toByteArray());
            } catch (Exception error) { return error("歌词解压失败"); }
            finally { inflater.end(); }
        }
    }

    private static String error(String value) { return "{\"error\":" + JSONObject.quote(value) + "}"; }
    private static String resultBytes(byte[] value) {
        return "{\"value\":" + JSONObject.quote(Base64.encodeToString(value, Base64.NO_WRAP)) + "}";
    }

    private final class Probe {
        final String url;
        final JSONObject options;
        final boolean sniff;
        final int timeout;
        final CountDownLatch done = new CountDownLatch(1);
        final LinkedHashSet<String> values = new LinkedHashSet<>();
        final Handler handler = new Handler(Looper.getMainLooper());
        WebView page;

        Probe(String url, JSONObject options, boolean sniff, int timeout) {
            this.url = url; this.options = options; this.sniff = sniff; this.timeout = timeout;
        }
        void add(String candidate) {
            synchronized (values) {
                if (candidate.length() > 1_000_000) return;
                if (sniff) {
                    if (values.size() < 512) values.add(candidate);
                } else {
                    values.remove(candidate);
                    if (values.size() >= 12) values.remove(values.iterator().next());
                    values.add(candidate);
                }
            }
        }
        void open() {
            if (closed) { stop(); return; }
            page = new WebView(context);
            page.getSettings().setJavaScriptEnabled(true);
            page.getSettings().setDomStorageEnabled(true);
            page.getSettings().setAllowContentAccess(false);
            page.getSettings().setAllowFileAccess(false);
            page.getSettings().setMediaPlaybackRequiresUserGesture(false);
            if (options.optBoolean("desktop")) page.getSettings().setUserAgentString(DESKTOP);
            page.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                    return !"http".equals(request.getUrl().getScheme()) && !"https".equals(request.getUrl().getScheme());
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                    if (sniff) {
                        String candidate = request.getUrl().toString();
                        add(candidate);
                        if (candidate.matches("(?i).*\\.(mp3|m4a|aac|flac|ogg|wav|m3u8)(?:[?#].*)?$")) {
                            return new WebResourceResponse("application/octet-stream", "UTF-8", new ByteArrayInputStream(new byte[0]));
                        }
                    }
                    return null;
                }
            });
            page.loadUrl(url);
            handler.postDelayed(this::poll, 1600);
            handler.postDelayed(this::stop, timeout);
        }
        void stop() {
            if (page != null) { page.stopLoading(); page.destroy(); page = null; }
            handler.removeCallbacksAndMessages(null);
            probes.remove(this);
            done.countDown();
        }
        void poll() {
            if (page == null) return;
            String fallback = sniff ? "(()=>{const node=document.querySelector('audio,video');if(node){node.muted=true;node.play().catch(()=>{});return node.currentSrc||node.src}return ''})()"
                : "document.documentElement.outerHTML";
            page.evaluateJavascript(options.optString("script", fallback), result -> {
                try {
                    Object value = new JSONTokener(result).nextValue();
                    if (value != JSONObject.NULL && !String.valueOf(value).trim().isEmpty()) add(String.valueOf(value));
                } catch (Exception ignored) {}
            });
            handler.postDelayed(this::poll, 1500);
        }
    }
}
