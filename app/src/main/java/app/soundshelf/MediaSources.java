package app.soundshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class MediaSources {
    static final String[] KEYS = {"all", "wy", "tx", "kg", "kw", "mg"};
    static final String[] NAMES = {"聚合", "网易云", "QQ 音乐", "酷狗", "酷我", "咪咕"};
    static final String SSTS_SOURCES = "https://github.com/whykang/ssts--Android/releases/download/v5.7/Sources-v5.7.zip";
    static final String SSTS_VERSION = "5.7";
    final SharedPreferences settings;
    private static final Object SOURCE_FILES = new Object();
    private static final java.util.concurrent.ExecutorService AUDIO_PROBES = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final Context context;
    private String catalog;
    private static final java.util.concurrent.ExecutorService BOOK_SCRIPTS = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler bookMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private ScriptRuntime discoveryRuntime;
    private String discoveryRuntimeSignature = "";
    private BookRequest discoveryRequest;

    record Resolved(String url, Bundle headers, boolean hls, String quality, String extension, AudioDetails details) {
        Resolved(String url, Bundle headers, boolean hls, String quality) { this(url, headers, hls, quality, "", AudioDetails.EMPTY); }
        Resolved(String url, Bundle headers, boolean hls) { this(url, headers, hls, ""); }
        JSONObject jsonHeaders() throws Exception {
            var json = new JSONObject();
            for (String name : headers.keySet()) json.put(name.toLowerCase(Locale.ROOT), headers.getString(name));
            return json;
        }
    }
    interface ResolveCallback { void complete(Resolved media, String error); }

    MediaSources(Context context) {
        this.context = context.getApplicationContext();
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    static String label(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return key.equals("library") ? "Navidrome" : key;
    }

    Navidrome navidrome() {
        return new Navidrome(settings.getString("nav-url", ""), settings.getString("nav-user", ""), settings.getString("nav-password", ""));
    }

    LibraryAudioSettings.Profile libraryAudioProfile() { return LibraryAudioSettings.profile(context, settings); }

    void catalog(String operation, JSONObject options, ScriptRuntime.Callback callback) {
        try {
            if (catalog == null) catalog = ScriptRuntime.asset(context, "catalog.bundle.js");
            new ScriptRuntime(context, "catalog", catalog, "SoundShelfCatalog(" + JSONObject.quote(operation) + "," + options + ")", callback);
        } catch (Exception error) { callback.complete(null, "音乐目录未加载：" + error.getMessage()); }
    }

    void lyrics(MediaEntry entry, ScriptRuntime.Callback callback) {
        Object embedded = entry.raw().opt("lrc");
        if (embedded instanceof String value && !value.trim().isEmpty() && !value.startsWith("http")) {
            callback.complete(args("lyric", value), null);
            return;
        }
        if (List.of(KEYS).contains(entry.source()) && !"all".equals(entry.source())) {
            catalog("lyric", args("source", entry.source(), "music", onlineMusicInfo(entry)), callback);
            return;
        }
        callback.complete(args("lyric", ""), null);
    }

    private static JSONObject onlineMusicInfo(MediaEntry entry) {
        try {
            JSONObject raw = new JSONObject(entry.raw().toString());
            JSONObject meta = raw.optJSONObject("meta");
            if (meta == null) meta = new JSONObject();
            for (var keys = meta.keys(); keys.hasNext();) {
                String key = keys.next();
                fillMusicField(raw, key, meta.opt(key));
            }
            fillMusicField(raw, "name", entry.title());
            fillMusicField(raw, "singer", entry.subtitle());
            fillMusicField(raw, "songmid", meta.opt("songId"), raw.opt("songId"), entry.id());
            fillMusicField(raw, "songId", meta.opt("id"), raw.opt("songmid"));
            fillMusicField(raw, "albumName", meta.opt("albumName"), raw.opt("album"));
            fillMusicField(raw, "img", meta.opt("picUrl"), entry.cover());
            fillMusicField(raw, "types", meta.opt("qualitys"));
            fillMusicField(raw, "_types", meta.opt("_qualitys"));
            fillMusicField(meta, "albumName", raw.opt("albumName"), "");
            fillMusicField(meta, "picUrl", entry.cover());
            fillMusicField(meta, "qualitys", raw.optJSONArray("types"), new JSONArray());
            fillMusicField(meta, "_qualitys", raw.optJSONObject("_types"), new JSONObject());
            for (String field : List.of("albumId", "hash", "albumAudioId", "strMediaMid", "albumMid", "copyrightId", "lrcUrl", "mrcUrl", "trcUrl")) {
                fillMusicField(meta, field, raw.opt(field));
                fillMusicField(raw, field, meta.opt(field));
            }
            if (entry.source().equals("tx")) fillMusicField(meta, "id", raw.opt("songId"));
            if (entry.source().equals("mg")) fillMusicField(meta, "songId", raw.opt("copyrightId"));
            fillMusicField(meta, "songId", raw.opt("songmid"));
            raw.put("meta", meta);
            return raw;
        } catch (Exception error) { throw new IllegalArgumentException(error); }
    }

    private static void fillMusicField(JSONObject target, String key, Object... values) throws org.json.JSONException {
        if (!MediaEntry.first(target, key).isEmpty()) return;
        for (Object value : values) {
            if (value == null || value == JSONObject.NULL || value instanceof String text && text.trim().isEmpty()) continue;
            target.put(key, value);
            return;
        }
    }

    String navCacheKey() {
        return settings.getString("nav-url", "") + "\n" + settings.getString("nav-user", "") + "\n"
            + MediaCache.digest(settings.getString("nav-password", ""));
    }

    static JSONObject args(Object... pairs) {
        var result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        } catch (Exception error) { throw new IllegalArgumentException(error); }
        return result;
    }

    JSONArray registered(String key) {
        try { return new JSONArray(settings.getString(key, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    private File scriptFile(String key) { return new File(context.getFilesDir(), "source-" + key + ".js"); }
    private String script(String key) throws Exception {
        synchronized (SOURCE_FILES) {
            return new String(new android.util.AtomicFile(scriptFile(key)).readFully(), StandardCharsets.UTF_8);
        }
    }
    static String nameOf(String code) {
        String name = scriptField(code, "name");
        return name.isEmpty() ? "LX 自定义音源" : name;
    }
    static String scriptField(String code, String field) {
        var match = java.util.regex.Pattern.compile("(?m)@" + field + "[ \t]+([^\r\n]+)").matcher(code);
        return match.find() ? match.group(1).trim() : "";
    }
    private static String musicScript(String code) {
        return "globalThis.lx.currentScriptInfo=" + args("name", nameOf(code), "description", scriptField(code, "description"),
            "version", scriptField(code, "version"), "author", scriptField(code, "author"), "homepage", scriptField(code, "homepage"),
            "rawScript", code) + ";\n" + code;
    }

    boolean installedCodeMatches(String id, String code) {
        try { return code.equals(script(id)); } catch (Exception error) { return false; }
    }

    private JSONObject installed(String group, String name, String id) {
        JSONArray entries = registered(group);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && (id.isEmpty() ? name.equals(entry.optString("name")) : id.equals(entry.optString("id")))) return entry;
        }
        return null;
    }
    private void saveSource(String group, String name, String expectedId, JSONObject replacement, String code) throws Exception {
        JSONObject previous = installed(group, name, expectedId);
        if (!expectedId.isEmpty() && previous == null) throw new IllegalStateException("源已移除");
        JSONArray entries = registered(group);
        JSONArray updated = new JSONArray();
        String previousId = previous == null ? "" : previous.getString("id");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            updated.put(previousId.equals(entry.optString("id")) ? replacement : entry);
        }
        if (previous == null) updated.put(replacement);
        synchronized (SOURCE_FILES) {
            var file = new android.util.AtomicFile(scriptFile(replacement.getString("id")));
            var stream = file.startWrite();
            try {
                stream.write(code.getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (Exception error) {
                file.failWrite(stream);
                throw error;
            }
        }
        settings.edit().putString(group, updated.toString()).apply();
    }

    void importMusic(String code, String address, String expectedId, ScriptRuntime.Callback callback) {
        String name = nameOf(code);
        JSONObject old = installed("music-sources", name, expectedId);
        if (!expectedId.isEmpty() && (old == null || !name.equals(old.optString("name")))) {
            callback.complete(null, "更新包的名称与已安装的音乐源不一致");
            return;
        }
        String key = old == null ? UUID.randomUUID().toString() : old.optString("id");
        new ScriptRuntime(context, key, musicScript(code), "lxInstallation()", (value, error) -> {
            if (error != null) { callback.complete(null, error); return; }
            try {
                JSONObject result = (JSONObject) value;
                JSONObject platforms = result.getJSONObject("platforms");
                if (platforms.length() == 0) throw new IllegalArgumentException("没有注册音乐平台");
                JSONObject current = installed("music-sources", name, old == null ? "" : key);
                JSONObject replacement = args("id", key, "name", name, "platforms", platforms,
                    "version", scriptField(code, "version"), "enabled", current == null || current.optBoolean("enabled", true),
                    "update-url", address.isEmpty() && current != null ? current.optString("update-url") : address);
                saveSource("music-sources", name, old == null ? expectedId : key, replacement, code);
                JSONObject alert = result.optJSONObject("alert");
                if (alert != null && expectedId.isEmpty()) SourceUpdates.get(context).receiveAlert(key, alert.optString("log"), alert.optString("updateUrl"));
                callback.complete(name, null);
            } catch (Exception failure) { callback.complete(null, failure.getMessage()); }
        });
    }

    void importBooks(PluginArchive plugin, String address, String expectedId, ScriptRuntime.Callback callback) {
        JSONObject old = installed("book-plugins", plugin.name(), expectedId);
        if (!expectedId.isEmpty() && (old == null || !plugin.name().equals(old.optString("name")))) {
            callback.complete(null, "更新包的名称与已安装的听书插件不一致");
            return;
        }
        String key = old == null ? UUID.randomUUID().toString() : old.optString("id");
        new ScriptRuntime(context, key, plugin.scripts(), "bookSources()", (value, error) -> {
            if (error != null) { callback.complete(null, error); return; }
            try {
                JSONArray sources = new JSONArray();
                JSONArray registered = (JSONArray) value;
                for (int i = 0; i < registered.length(); i++) {
                    JSONObject source = registered.optJSONObject(i);
                    if (allowedBookSource(source)) sources.put(source);
                }
                if (sources.length() == 0) throw new IllegalArgumentException("没有注册听书源");
                JSONObject current = installed("book-plugins", plugin.name(), old == null ? "" : key);
                saveSource("book-plugins", plugin.name(), old == null ? expectedId : key,
                    args("id", key, "name", plugin.name(), "version", plugin.version(), "sources", sources, "revision", UUID.randomUUID().toString(),
                        "update-url", address.isEmpty() && current != null ? current.optString("update-url") : address), plugin.scripts());
                callback.complete(sources.length(), null);
            } catch (Exception failure) { callback.complete(null, failure.getMessage()); }
        });
    }

    // The imported collection also registers a music-only NetEase adapter.
    // Exclude it at import and read time, including previously installed packs.
    private static boolean allowedBookSource(JSONObject source) {
        return source != null && !"2f8b6d4a0c9e43f7b15a8e6d3c7f0a94".equals(source.optString("id"))
            && !"网易云音乐".equals(source.optString("name").trim());
    }

    List<JSONObject> books(boolean enabled) {
        var result = new ArrayList<JSONObject>();
        JSONArray groups = registered("book-plugins");
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray entries = group.optJSONArray("sources");
            for (int j = 0; entries != null && j < entries.length(); j++) {
                JSONObject item = entries.optJSONObject(j);
                if (!allowedBookSource(item)) continue;
                try { item.put("plugin", group.getString("id")); } catch (Exception ignored) {}
                if (!enabled || settings.getBoolean("book-enable-" + item.optString("id"), item.optBoolean("enabled", true))) result.add(item);
            }
        }
        return result;
    }
    JSONObject bookSource(String id) {
        return books(false).stream().filter(item -> id.equals(item.optString("id"))).findFirst().orElse(null);
    }

    String bookDiscoverySignature(JSONObject source) {
        String plugin = source.optString("plugin");
        JSONObject pack = installed("book-plugins", "", plugin);
        var settingsKey = new java.util.TreeMap<String, Object>();
        String prefix = "book-pref-" + source.optString("id") + "-";
        settings.getAll().forEach((key, value) -> { if (key.startsWith(prefix)) settingsKey.put(key, value); });
        return plugin + ":" + (pack == null ? "" : pack.optString("version") + ":" + pack.optString("revision")) + ":" + settingsKey;
    }

    void bundledBooks(ScriptRuntime.Callback callback) {
        JSONObject old = installed("book-plugins", "ShunSources", "");
        if (old != null && !SourceUpdates.older(SSTS_VERSION, old.optString("version"))) {
            callback.complete(null, null);
            return;
        }
        // Keep custom update locations under the user's control.
        if (old != null && !officialBookAddress(old.optString("update-url"))) {
            callback.complete(null, null);
            return;
        }
        try (var stream = context.getAssets().open("book-sources/Sources-v5.7.zip")) {
            importBooks(PluginArchive.read(Http.bytes(stream, 4_000_000)), SSTS_SOURCES,
                old == null ? "" : old.optString("id"), callback);
        } catch (Exception error) { callback.complete(null, error.getMessage()); }
    }

    static boolean officialBookAddress(String address) {
        return address.isEmpty() || address.equals(SSTS_SOURCES)
            || address.equals("https://raw.githubusercontent.com/whykang/ssts--Android/main/plugins/dist/ShunSources.zip")
            || address.matches("https://github\\.com/whykang/ssts--Android/releases/download/v[\\d.]+/Sources-v[\\d.]+\\.zip")
            || address.matches("https://gitee\\.com/whykan/ssxz/raw/master/(?:ShunSources|Sources-v[\\d.]+)\\.zip");
    }
    ScriptRuntime book(String sourceId, String operation, JSONArray arguments, ScriptRuntime.Callback callback) {
        JSONObject source = bookSource(sourceId);
        if (source == null) { callback.complete(null, "请先导入对应的听书源"); return null; }
        try {
            String plugin = source.getString("plugin");
            return new ScriptRuntime(context, plugin, script(plugin), "bookCall(" + JSONObject.quote(sourceId) + ","
                + JSONObject.quote(operation) + "," + arguments + ")", callback);
        } catch (Exception error) { callback.complete(null, error.getMessage()); return null; }
    }

    final class BookRequest {
        private volatile boolean cancelled;
        private ScriptRuntime runtime;

        void cancel() {
            cancelled = true;
            if (runtime != null) { runtime.cancel(); runtime = null; }
            if (discoveryRequest == this) discoveryRequest = null;
        }
    }

    /** Discovery alone keeps an idle engine; playback and search retain isolated runtimes. */
    BookRequest bookDiscovery(String sourceId, String signature, String operation, JSONArray arguments, ScriptRuntime.Callback callback) {
        if (discoveryRequest != null) discoveryRequest.cancel();
        var request = new BookRequest();
        discoveryRequest = request;
        long started = android.os.SystemClock.elapsedRealtime();
        String expression = "bookCall(" + JSONObject.quote(sourceId) + "," + JSONObject.quote(operation) + "," + arguments + ")";
        // Always dispatch later, so callers can store the handle before any completion.
        bookMain.post(() -> {
            if (request.cancelled) return;
            JSONObject source = bookSource(sourceId);
            ScriptRuntime.Callback completed = (value, error) -> {
                request.runtime = null;
                if (request.cancelled) return;
                if (discoveryRequest == request) discoveryRequest = null;
                android.util.Log.d("BookDiscovery", sourceId + " " + operation + " "
                    + (android.os.SystemClock.elapsedRealtime() - started) + "ms");
                callback.complete(value, error);
            };
            if (source == null || !signature.equals(bookDiscoverySignature(source))) {
                completed.complete(null, "听书源已变更，请刷新后重试");
                return;
            }
            if (discoveryRuntime != null && discoveryRuntime.idle() && signature.equals(discoveryRuntimeSignature)) {
                request.runtime = discoveryRuntime;
                discoveryRuntime.execute(expression, completed);
                return;
            }
            if (discoveryRuntime != null) { discoveryRuntime.cancel(); discoveryRuntime = null; }
            BOOK_SCRIPTS.execute(() -> {
                try {
                    String code = script(source.optString("plugin"));
                    bookMain.post(() -> {
                        if (request.cancelled) return;
                        JSONObject current = bookSource(sourceId);
                        if (current == null || !signature.equals(bookDiscoverySignature(current))) {
                            completed.complete(null, "听书源已变更，请刷新后重试");
                            return;
                        }
                        try {
                            discoveryRuntimeSignature = signature;
                            discoveryRuntime = new ScriptRuntime(context, source.optString("plugin"), code,
                                expression, 120_000, true, completed);
                            request.runtime = discoveryRuntime;
                        } catch (Exception error) { completed.complete(null, error.getMessage()); }
                    });
                } catch (Exception error) { bookMain.post(() -> completed.complete(null, error.getMessage())); }
            });
        });
        return request;
    }

    void closeBookDiscovery() {
        if (discoveryRequest != null) discoveryRequest.cancel();
        if (discoveryRuntime != null) { discoveryRuntime.cancel(); discoveryRuntime = null; }
    }

    void resolve(MediaEntry entry, boolean audiobook, boolean download, ResolveCallback callback) {
        resolve(entry, audiobook, download, (name, status) -> {}, callback);
    }

    void resolveDownload(MediaEntry entry, boolean audiobook, String quality, ResolveCallback callback) {
        resolve(entry, audiobook, true, quality, (name, status) -> {}, callback);
    }

    interface ResolveStatus { void changed(String source, String status); }
    private void resolve(MediaEntry entry, boolean audiobook, boolean download, ResolveStatus status, ResolveCallback callback) {
        resolve(entry, audiobook, download, "", status, callback);
    }

    boolean canRequestQuality(MediaEntry entry, MusicQuality quality) {
        JSONArray scripts = registered("music-sources");
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject source = scripts.optJSONObject(i);
            JSONObject platform = enabledPlatform(source, entry.source());
            if (platform == null) continue;
            JSONArray supported = platform.optJSONArray("qualitys");
            if (quality.advertisedKeys().stream().anyMatch(key -> supports(supported, key))) return true;
        }
        return false;
    }

    private JSONObject enabledPlatform(JSONObject script, String source) {
        if (script == null || !script.optBoolean("enabled", true)) return null;
        JSONObject platforms = script.optJSONObject("platforms");
        return platforms == null ? null : platforms.optJSONObject(source);
    }

    private void resolve(MediaEntry entry, boolean audiobook, boolean download, String chosen, ResolveStatus status, ResolveCallback callback) {
        if (entry.source().equals("offline")) {
            callback.complete(new Resolved(entry.id(), new Bundle(), false), null);
        } else if (entry.source().equals("library")) {
            try {
                String command = download ? "download" : "stream";
                var profile = libraryAudioProfile();
                String parameters = "&id=" + Http.enc(entry.id()) + (download ? "" : profile.parameters());
                callback.complete(new Resolved(navidrome().endpoint(command, parameters), new Bundle(), false,
                    download ? "" : profile.cacheKey(), download ? "" : profile.extension(), AudioDetails.EMPTY), null);
            } catch (Exception error) { callback.complete(null, error.getMessage()); }
        } else if (audiobook) {
            book(entry.source(), "audio", new JSONArray().put(entry.id()), (value, error) -> asMedia(value, error, callback));
        } else {
            String quality = chosen.isEmpty() ? settings.getString("music-quality", "320k") : chosen;
            onlineResolution(entry, quality, download, status).next(callback);
        }
    }

    OnlineResolution playbackResolution(MediaEntry entry, String quality, ResolveStatus status) {
        return onlineResolution(entry, quality, false, status);
    }

    private record Attempt(String sourceId, String name, String quality, MediaEntry entry) {}

    private OnlineResolution onlineResolution(MediaEntry entry, String quality, boolean download, ResolveStatus status) {
        JSONArray scripts = registered("music-sources");
        var attempts = new ArrayList<Attempt>();
        boolean hasSource = false;
        var known = new java.util.HashSet<String>();
        JSONObject raw = onlineMusicInfo(entry);
        JSONObject types = raw.optJSONObject("_types");
        if (types != null) types.keys().forEachRemaining(known::add);
        JSONArray rows = raw.optJSONArray("types");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) known.add(row.optString("type"));
        }
        for (String requested : MusicQuality.attempts(quality, download, known)) {
            for (int i = 0; i < scripts.length(); i++) {
                JSONObject source = scripts.optJSONObject(i);
                JSONObject platform = enabledPlatform(source, entry.source());
                if (platform == null) continue;
                hasSource = true;
                JSONArray advertised = platform.optJSONArray("qualitys");
                List<String> aliases = "atmos_plus".equals(requested) ? List.of("atmos_plus", "atmos")
                    : "atmos".equals(requested) ? MusicQuality.SPATIAL.advertisedKeys() : List.of(requested);
                for (String key : aliases) {
                    if (supports(advertised, key)) attempts.add(new Attempt(source.optString("id"), source.optString("name", "LX 音源"), key, entry));
                }
            }
        }
        String unavailable = hasSource ? "已启用的音源不支持「" + MusicQuality.label(quality) + "」"
            : "请在音乐设置导入或启用支持「" + label(entry.source()) + "」的 LX 音源";
        return new OnlineResolution(entry, quality, attempts, status, unavailable, download);
    }

    private static boolean supports(JSONArray advertised, String quality) {
        if (advertised == null || advertised.length() == 0) return true;
        for (int i = 0; i < advertised.length(); i++) if (quality.equals(advertised.optString(i))) return true;
        return false;
    }

    final class OnlineResolution {
        private final MediaEntry original;
        private final String quality;
        private final java.util.ArrayDeque<String> alternatives = new java.util.ArrayDeque<>();
        private final ArrayList<Attempt> attempts;
        private final ResolveStatus status;
        private final String unavailable;
        private final boolean download;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private int index;
        private String lastError;
        private ScriptRuntime runtime;
        private java.util.concurrent.Future<?> probe;
        private final java.util.HashSet<String> unavailableSources = new java.util.HashSet<>();
        private final java.util.HashSet<String> unavailablePlatforms = new java.util.HashSet<>();
        private boolean cancelled;
        private Attempt current;

        OnlineResolution(MediaEntry original, String quality, ArrayList<Attempt> attempts, ResolveStatus status, String unavailable, boolean download) {
            this.original = original;
            this.quality = quality;
            this.attempts = attempts;
            this.status = status;
            this.unavailable = unavailable;
            this.download = download;
            for (String platform : KEYS) {
                if (platform.equals("all") || platform.equals(original.source())) continue;
                JSONArray scripts = registered("music-sources");
                for (int i = 0; i < scripts.length(); i++) {
                    if (enabledPlatform(scripts.optJSONObject(i), platform) != null) {
                        alternatives.add(platform);
                        break;
                    }
                }
            }
        }

        void cancel() {
            cancelled = true;
            if (runtime != null) runtime.cancel();
            if (probe != null) probe.cancel(true);
        }

        void failed(String reason) {
            lastError = (reason == null || reason.trim().isEmpty()) ? "音源解析失败" : reason;
            status.changed(attemptLabel(), "解析失败");
        }

        private String attemptLabel() {
            return current.name() + " · " + label(current.entry().source()) + " · " + MusicQuality.label(current.quality());
        }

        void next(ResolveCallback callback) {
            if (cancelled) return;
            while (index < attempts.size()) {
                current = attempts.get(index++);
                if (unavailableSources.contains(current.sourceId())) continue;
                if (unavailablePlatforms.contains(current.sourceId() + ":" + current.entry().source())) continue;
                status.changed(attemptLabel(), "正在解析");
                try {
                    String expression = "lxResolve(" + JSONObject.quote(current.entry().source()) + "," + JSONObject.quote(current.quality())
                        + "," + onlineMusicInfo(current.entry()) + ")";
                    runtime = new ScriptRuntime(context, current.sourceId(), musicScript(script(current.sourceId())), expression, 25_000, (value, error) -> {
                        runtime = null;
                        if (error != null && error.matches("(?is).*(?:\\b429\\b|限流|请求过速|key.{0,8}(?:失效|无效)).*")) {
                            unavailableSources.add(current.sourceId());
                        } else if (error != null && error.matches("(?is).*(?:\\b401\\b|权限不足|音源响应超时).*")) {
                            // An unavailable platform must not disable this script's
                            // other platforms during the cross-platform search.
                            unavailablePlatforms.add(current.sourceId() + ":" + current.entry().source());
                        }
                        if (!cancelled) asMedia(value, error, (media, failure) -> candidate(media, failure, callback));
                    });
                    return;
                } catch (Exception failure) { unavailableSources.add(current.sourceId()); failed(failure.getMessage()); }
            }
            if (!alternatives.isEmpty()) { searchAlternative(callback); return; }
            callback.complete(null, lastError == null ? unavailable : lastError);
        }

        private void searchAlternative(ResolveCallback callback) {
            String platform = alternatives.removeFirst();
            status.changed(label(platform), "正在查找同名歌曲");
            try {
                if (catalog == null) catalog = ScriptRuntime.asset(context, "catalog.bundle.js");
                String expression = "SoundShelfCatalog(\"search\"," + args("source", platform,
                    "query", original.title() + " " + original.subtitle(), "page", 1) + ")";
                runtime = new ScriptRuntime(context, "catalog", catalog, expression, 15_000, (value, error) -> {
                    runtime = null;
                    if (cancelled) return;
                    JSONArray rows = value instanceof JSONObject result ? result.optJSONArray("list") : null;
                    var matches = new ArrayList<MediaEntry>();
                    var seen = new java.util.HashSet<String>();
                    for (int i = 0; rows != null && i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        MediaEntry entry = MediaEntry.online(row);
                        if (!entry.source().equals(platform) || entry.id().isBlank() || !seen.add(entry.id())) continue;
                        if (SongMatch.same(original, entry)) matches.add(entry);
                    }
                    matches.sort(java.util.Comparator.comparingInt(entry -> SongMatch.distance(original, entry)));
                    for (MediaEntry match : matches.subList(0, Math.min(2, matches.size()))) {
                        attempts.addAll(onlineResolution(match, quality, download, status).attempts);
                    }
                    next(callback);
                });
            } catch (Exception failure) { next(callback); }
        }

        private void candidate(Resolved media, String error, ResolveCallback callback) {
            if (error != null) { failed(error); next(callback); return; }
            probe = AUDIO_PROBES.submit(() -> {
                AudioCheck checked = validateAudio(media, download);
                main.post(() -> {
                    probe = null;
                    if (cancelled) return;
                    if (checked.failure() != null) { failed(checked.failure()); next(callback); return; }
                    status.changed(current.name() + " · " + label(current.entry().source()) + " · " + MusicQuality.label(media.quality()), "解析成功");
                    callback.complete(checked.media(), null);
                });
            });
        }
    }

    private record AudioCheck(Resolved media, String failure) {}
    private static final int AUDIO_PREFIX = 16 * 1024;

    private static AudioCheck validateAudio(Resolved media, boolean download) {
        var headers = new java.util.LinkedHashMap<String, String>();
        for (String name : media.headers().keySet()) {
            if (!name.equalsIgnoreCase("Range") && !name.equalsIgnoreCase("Accept-Encoding")) {
                headers.put(name, media.headers().getString(name));
            }
        }
        headers.put("Accept-Encoding", "identity");
        try {
            Http.Response response = Http.sample(media.url(), headers, 6500, AUDIO_PREFIX);
            if (List.of(400, 405, 416).contains(response.status())) {
                headers.put("Range", "bytes=0-" + (AUDIO_PREFIX - 1));
                response = Http.sample(media.url(), headers, 6500, AUDIO_PREFIX);
            }
            if (response.status() != 200 && response.status() != 206) return new AudioCheck(null, "音频地址返回 HTTP " + response.status());
            String mime = response.headers().getOrDefault("content-type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            AudioSignature format = AudioSignature.read(response.bytes());
            if (format == AudioSignature.UNKNOWN) {
                int offset = AudioSignature.audioOffset(response.bytes());
                if (offset >= response.bytes().length && offset > 0 && offset <= 8 * MediaCache.MB) {
                    format = samplePastTag(media.url(), headers, offset);
                }
            }
            if (format == AudioSignature.ERROR || format == AudioSignature.UNKNOWN
                && (mime.contains("html") || mime.contains("json") || mime.contains("xml"))) {
                return new AudioCheck(null, "音频地址返回了错误页面");
            }
            if (MusicQuality.incompatibleEncoding(media.quality(), format.encoding)) {
                return new AudioCheck(null, "文件实际是 " + format.label + "，与「" + MusicQuality.label(media.quality()) + "」不符");
            }
            if (format == AudioSignature.UNKNOWN && MusicQuality.incompatibleMime(media.quality(), mime)) {
                return new AudioCheck(null, "无法确认「" + MusicQuality.label(media.quality()) + "」，媒体响应标为 " + mime);
            }
            String size = response.headers().getOrDefault("content-length", "");
            if (response.status() == 206) {
                String range = response.headers().getOrDefault("content-range", "");
                size = range.contains("/") ? range.substring(range.lastIndexOf('/') + 1) : "";
            }
            boolean hls = format == AudioSignature.HLS || format == AudioSignature.UNKNOWN && (media.hls() || mime.contains("mpegurl"));
            if (response.bytes().length == 0 || (!hls && size.matches("\\d{1,4}") && Integer.parseInt(size) < 1024)) {
                return new AudioCheck(null, "音频地址返回了空文件或占位响应");
            }
            long length;
            try { length = hls ? 0 : Long.parseLong(size); }
            catch (NumberFormatException ignored) { length = 0; }
            var details = AudioDetails.fromPrefix(format, response.bytes(), mime, length);
            return new AudioCheck(new Resolved(media.url(), media.headers(), hls, media.quality(), format.extension, details), null);
        } catch (java.io.IOException failure) {
            return download ? new AudioCheck(null, "无法读取下载地址 · " + failure.getClass().getSimpleName()) : new AudioCheck(media, null);
        } catch (IllegalArgumentException failure) {
            return new AudioCheck(null, "音频地址无效");
        }
    }

    private static AudioSignature samplePastTag(String url, Map<String, String> headers, int offset) {
        var range = new java.util.LinkedHashMap<>(headers);
        range.put("Range", "bytes=" + offset + "-" + (offset + AUDIO_PREFIX - 1));
        try {
            var tail = Http.sample(url, range, 6500, AUDIO_PREFIX);
            if (tail.status() == 206 && tail.headers().getOrDefault("content-range", "").startsWith("bytes " + offset + "-")) {
                return AudioSignature.read(tail.bytes());
            }
        } catch (java.io.IOException ignored) { /* The response headers remain available as a fallback. */ }
        return AudioSignature.UNKNOWN;
    }

    private static void asMedia(Object value, String error, ResolveCallback callback) {
        if (error != null) { callback.complete(null, error); return; }
        try {
            JSONObject item = (JSONObject) value;
            String url = Http.url(item.getString("url")).toString();
            var headers = new Bundle();
            JSONObject extra = item.optJSONObject("headers");
            boolean hls = Http.url(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8") || "hls".equalsIgnoreCase(item.optString("mediaType"));
            if (extra != null) for (var iter = extra.keys(); iter.hasNext();) {
                String name = iter.next();
                if (name.equalsIgnoreCase("X-Media-Type")) hls |= "hls".equalsIgnoreCase(extra.getString(name));
                else headers.putString(name, extra.getString(name));
            }
            callback.complete(new Resolved(url, headers, hls, item.optString("selectedQuality")), null);
        } catch (Exception failure) { callback.complete(null, "音源未返回有效的 HTTP 音频地址"); }
    }
}
