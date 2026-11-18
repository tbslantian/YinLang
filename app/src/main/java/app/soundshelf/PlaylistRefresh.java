package app.soundshelf;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class PlaylistRefresh {
    static final int JOB = 9045;
    static final String DISCOVERY_CHARTS = "discovery-art-chart-songs";
    static final String DISCOVERY_LISTS = "discovery-art-lists";
    private static final int DISCOVERY_COVER_LIMIT = 12;
    static final String[] MODES = {"off", "open", "3", "6", "12", "24"};
    static final String[] LABELS = {"关闭", "每次打开 App", "每 3 小时", "每 6 小时", "每 12 小时", "每天"};
    private static final long FRESH_FOR = 20 * 60_000L;
    private static final long ONLINE_FRESH_FOR = 3 * 3_600_000L;
    private static final long TAGS_FRESH_FOR = 24 * 3_600_000L;
    private static final ExecutorService DISK = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PlaylistRefresh instance;
    private final Context context;
    private final MediaSources sources;
    private final SharedPreferences settings;
    private final MediaCache.Bucket cache;
    private record RequestKey(String key, long revision) {}
    private final HashMap<RequestKey, List<ScriptRuntime.Callback>> requests = new HashMap<>();
    private final LruCache<RequestKey, Long> recentlyChecked = new LruCache<>(128);
    private final List<java.util.function.Consumer<Report>> listeners = new ArrayList<>();
    private boolean running;
    private int updated, failed;
    private long nextTime;
    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private int inProgress;
    private long navMutation;
    record Report(int updated, int failed) {}
    interface Task { void start(java.util.function.Consumer<Boolean> complete); }

    static synchronized PlaylistRefresh get(Context context) {
        if (instance == null) instance = new PlaylistRefresh(context.getApplicationContext());
        return instance;
    }
    private PlaylistRefresh(Context context) {
        this.context = context;
        sources = new MediaSources(context);
        settings = sources.settings;
        cache = MediaCache.get(context).bucket(false);
    }

    String mode() { return settings.getString("playlist-refresh-mode", "off"); }
    boolean unmeteredOnly() { return settings.getBoolean("playlist-refresh-unmetered", true); }
    void setUnmeteredOnly(boolean enabled) {
        settings.edit().putBoolean("playlist-refresh-unmetered", enabled).apply();
        schedule();
    }
    private boolean unmetered() {
        return !context.getSystemService(ConnectivityManager.class).isActiveNetworkMetered();
    }
    void setMode(String mode) {
        settings.edit().putString("playlist-refresh-mode", mode).apply();
        schedule();
    }
    void schedule() {
        var scheduler = context.getSystemService(JobScheduler.class);
        String mode = mode();
        if ("off".equals(mode) || "open".equals(mode)) { scheduler.cancel(JOB); return; }
        long interval = Long.parseLong(mode) * 3_600_000L;
        JobInfo pending = scheduler.getPendingJob(JOB);
        int network = unmeteredOnly() ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
        if (pending == null || pending.getIntervalMillis() != interval || pending.getNetworkType() != network) {
            scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, RefreshJob.class))
                .setPersisted(true).setPeriodic(interval).setRequiredNetworkType(network).build());
        }
    }
    void foreground(java.util.function.Consumer<Report> callback) {
        if (unmeteredOnly() && !unmetered()) return;
        String mode = mode();
        if ("open".equals(mode) || (!"off".equals(mode)
            && System.currentTimeMillis() - settings.getLong("playlist-refresh-at", 0) >= Long.parseLong(mode) * 3_600_000L)) {
            automaticRefresh(callback);
        }
    }
    private JSONObject tracked() {
        try { return new JSONObject(settings.getString("tracked-playlists", "{}")); }
        catch (Exception error) { return new JSONObject(); }
    }
    private static String id(String source, String id) { return source + "\n" + id; }
    void remember(String source, String id, String title, Boolean followed) {
        remember(source, id, title, followed, "");
    }
    void remember(String source, String id, String title, Boolean followed, String cover) {
        if (id.trim().isEmpty()) return;
        var records = tracked();
        String key = id(source, id);
        JSONObject old = records.optJSONObject(key);
        boolean follow = followed != null ? followed : old != null && old.optBoolean("follow");
        if (cover.isEmpty() && old != null) cover = old.optString("cover");
        try {
            records.put(key, MediaSources.args("source", source, "id", id, "title", title, "follow", follow, "cover", cover));
            settings.edit().putString("tracked-playlists", records.toString()).apply();
        } catch (Exception ignored) {}
    }
    boolean followed(String source, String id) {
        JSONObject record = tracked().optJSONObject(id(source, id));
        return record != null && record.optBoolean("follow");
    }
    List<JSONObject> following() {
        JSONObject records = tracked();
        var result = new ArrayList<JSONObject>();
        for (Iterator<String> keys = records.keys(); keys.hasNext();) {
            JSONObject item = records.optJSONObject(keys.next());
            if (item != null && item.optBoolean("follow")) result.add(item);
        }
        return result;
    }

    List<String> discoveryCovers(String key) {
        try {
            var saved = new JSONArray(settings.getString(key, "[]"));
            var covers = new ArrayList<String>();
            for (int i = 0; i < saved.length(); i++) covers.add(saved.getString(i));
            return covers;
        } catch (Exception error) { return List.of(); }
    }

    void rememberDiscoveryCovers(String key, List<String> covers, boolean rotate) {
        if (covers.isEmpty()) return;
        List<String> previous = discoveryCovers(key);
        if (!rotate && !previous.isEmpty()) return;
        String last = settings.getString(key + "-shown", previous.isEmpty() ? "" : previous.get(0));
        List<String> selected = rotateDiscoveryCovers(covers, last);
        if (!selected.equals(previous)) {
            settings.edit().remove(key + "-shown").putString(key, new JSONArray(selected).toString()).apply();
        }
    }

    void noteDiscoveryCoverShown(String key, String address) {
        settings.edit().putString(key + "-shown", address).apply();
    }

    static List<String> rotateDiscoveryCovers(List<String> covers, String previous) {
        int next = covers.indexOf(previous) + 1;
        var selected = new ArrayList<String>();
        for (int i = 0; i < Math.min(covers.size(), DISCOVERY_COVER_LIMIT); i++) {
            selected.add(covers.get((next + i) % covers.size()));
        }
        return selected;
    }

    void cachedOnline(String operation, JSONObject args, ScriptRuntime.Callback done) {
        String key = onlineKey(operation, args);
        long revision = cache.revision();
        DISK.execute(() -> {
            JSONObject snapshot = read(key);
            MAIN.post(() -> done.complete(cache.revision() == revision ? snapshot : null, null));
        });
    }

    void cachedChartTrackCovers(String source, List<String> ids, java.util.function.Consumer<List<String>> done) {
        long revision = cache.revision();
        DISK.execute(() -> {
            var covers = new java.util.LinkedHashSet<String>();
            for (String id : ids) {
                JSONObject options = MediaSources.args("source", source, "id", id);
                JSONObject detail = read(onlineKey("chart-cover", options));
                if (detail == null) detail = read(onlineKey("chart", options));
                covers.addAll(ChartArtwork.topTrackCover(detail));
                if (covers.size() >= 6) break;
            }
            MAIN.post(() -> done.accept(cache.revision() == revision ? List.copyOf(covers) : List.of()));
        });
    }

    void online(String operation, JSONObject args, boolean cacheFirst, ScriptRuntime.Callback done) {
        if (!cacheFirst) { fetchOnline(operation, args, done); return; }
        String key = onlineKey(operation, args);
        long revision = cache.revision();
        DISK.execute(() -> {
            JSONObject snapshot = read(key);
            MAIN.post(() -> {
                if (snapshot == null || revision != cache.revision() || MediaCache.get(context).limitMb(false) == 0) {
                    fetchOnline(operation, args, done);
                    return;
                }
                if (!freshOnline(operation, snapshot)) {
                    Long latest = recentlyChecked.get(new RequestKey(key, revision));
                    if (unmetered() && (latest == null || SystemClock.elapsedRealtime() - latest >= 5 * 60_000L)) {
                        fetchOnline(operation, args, (value, error) -> {});
                    }
                }
                done.complete(snapshot, null);
            });
        });
    }
    private void fetchOnline(String operation, JSONObject args, ScriptRuntime.Callback done) {
        RequestKey request = new RequestKey(onlineKey(operation, args), cache.revision());
        var waiting = requests.get(request);
        if (waiting != null) { waiting.add(done); return; }
        requests.put(request, new ArrayList<>(List.of(done)));
        recentlyChecked.put(request, SystemClock.elapsedRealtime());
        sources.catalog(operation, args, (value, error) -> {
            if (error == null && value instanceof JSONObject payload) {
                DISK.execute(() -> {
                    JSONArray failed = payload.optJSONArray("failed");
                    JSONObject previous = failed != null && failed.length() > 0 ? read(request.key()) : null;
                    if (previous == null) write(request.key(), payload, request.revision());
                    MAIN.post(() -> finishOnline(request, payload, null));
                });
            } else finishOnline(request, null, error == null ? "平台未返回有效列表" : error);
        });
    }
    private void finishOnline(RequestKey key, JSONObject payload, String error) {
        for (var callback : requests.remove(key)) callback.complete(payload, error);
    }
    private boolean freshOnline(String operation, JSONObject value) {
        long age = System.currentTimeMillis() - value.optLong("_cachedAt", 0);
        long maxAge = "tags".equals(operation) ? TAGS_FRESH_FOR : ONLINE_FRESH_FOR;
        JSONArray failed = value.optJSONArray("failed");
        if (failed != null && failed.length() > 0) maxAge = 5 * 60_000L;
        return age >= 0 && age < maxAge;
    }
    private static String onlineKey(String operation, JSONObject args) {
        var key = new StringBuilder("playlist:" + operation);
        for (String name : List.of("source", "id", "query", "tag", "sort", "page")) {
            key.append('\n').append(name).append('=').append(args.optString(name, "page".equals(name) ? "1" : ""));
        }
        return key.toString();
    }
    private JSONObject read(String key) {
        byte[] data = cache.read(key);
        try { return data == null ? null : new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (Exception error) { return null; }
    }
    private void write(String key, JSONObject value, long revision) {
        try {
            JSONObject snapshot = new JSONObject(value.toString());
            snapshot.put("_cachedAt", System.currentTimeMillis());
            cache.write(key, snapshot.toString().getBytes(StandardCharsets.UTF_8), revision);
        } catch (Exception ignored) {}
    }
    private boolean fresh(JSONObject snapshot) {
        if (snapshot == null) return false;
        String mode = mode();
        long maxAge = List.of("off", "open").contains(mode) ? FRESH_FOR : Long.parseLong(mode) * 3_600_000L;
        return System.currentTimeMillis() - snapshot.optLong("_cachedAt", 0) < maxAge;
    }
    private static String navKey(String account, String id) { return "nav-lists:" + account + "\n" + id; }
    synchronized void navChanged(String account, String playlistId) {
        navMutation++;
        cache.remove(navKey(account, ""));
        if (!playlistId.isEmpty()) cache.remove(navKey(account, playlistId));
    }
    JSONArray navLists(boolean fresh) throws Exception {
        return nav(navKey(sources.navCacheKey(), ""), fresh, () -> sources.navidrome().playlists());
    }
    JSONArray navList(String id, boolean fresh) throws Exception {
        JSONArray entries = navPlaylist(id, fresh).optJSONArray("entry");
        return entries == null ? new JSONArray() : entries;
    }
    JSONObject navPlaylist(String id, boolean fresh) throws Exception {
        String key = navKey(sources.navCacheKey(), id);
        JSONObject snapshot;
        long mutation, revision;
        synchronized (this) {
            snapshot = read(key);
            if (!fresh && fresh(snapshot) && snapshot.optJSONObject("playlist") != null) return snapshot.getJSONObject("playlist");
            mutation = navMutation;
            revision = cache.revision();
        }
        JSONObject result;
        try { result = sources.navidrome().playlistDetails(id); }
        catch (Exception error) {
            if (!fresh && snapshot != null && snapshot.optJSONObject("playlist") != null) return snapshot.getJSONObject("playlist");
            // Older app versions cached only tracks. Keep offline playback, but do not infer edit permission.
            if (!fresh && snapshot != null && snapshot.optJSONArray("list") != null) {
                return MediaSources.args("id", id, "entry", snapshot.getJSONArray("list"), "readonly", true, "_legacyCache", true);
            }
            throw error;
        }
        synchronized (this) {
            if (mutation == navMutation) write(key, MediaSources.args("playlist", result), revision);
        }
        return result;
    }
    JSONArray navDiscoveryAlbums() throws Exception {
        return nav("nav-discovery-albums:" + sources.navCacheKey(), false, () -> sources.navidrome().newestAlbums(0, 8));
    }
    JSONArray navDiscovery(Navidrome.DiscoverySection section, boolean fresh) throws Exception {
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        return nav("nav-discovery-feed:v1:" + account + "\n" + section.name(), fresh, () -> server.discovery(section, 0, 20));
    }
    private interface Nav { JSONArray load() throws Exception; }
    private JSONArray nav(String key, boolean fresh, Nav loader) throws Exception {
        JSONObject snapshot;
        long mutation, revision;
        synchronized (this) {
            snapshot = read(key);
            if (!fresh && fresh(snapshot)) return snapshot.getJSONArray("list");
            mutation = navMutation;
            revision = cache.revision();
        }
        JSONArray result;
        try { result = loader.load(); }
        catch (Exception error) {
            if (!fresh && snapshot != null) return snapshot.getJSONArray("list");
            throw error;
        }
        synchronized (this) {
            if (mutation == navMutation) write(key, MediaSources.args("list", result), revision);
        }
        return result;
    }

    void refresh(java.util.function.Consumer<Report> complete) {
        refresh(true, complete);
    }
    void automaticRefresh(java.util.function.Consumer<Report> complete) {
        if (unmeteredOnly() && !unmetered()) { if (complete != null) complete.accept(new Report(0, 0)); return; }
        refresh(false, complete);
    }
    private void refresh(boolean manual, java.util.function.Consumer<Report> complete) {
        if (complete != null) listeners.add(complete);
        if (running) return;
        running = true;
        updated = failed = 0;
        nextTime = System.currentTimeMillis();
        queue.add(done -> refreshOnline("lists", MediaSources.args("source", "all", "sort", "@hot"), done));
        for (int i = 1; manual && i < MediaSources.KEYS.length; i++) {
            String source = MediaSources.KEYS[i];
            queue.add(done -> refreshOnline("lists", MediaSources.args("source", source, "sort", "@hot"), done));
        }
        var records = tracked();
        for (Iterator<String> keys = records.keys(); keys.hasNext();) {
            JSONObject item = records.optJSONObject(keys.next());
            if (item == null || (!manual && !item.optBoolean("follow"))) continue;
            var args = MediaSources.args("source", item.optString("source"), "id", item.optString("id"));
            queue.add(done -> refreshDetail(args, 1, new java.util.HashSet<>(), done));
        }
        if (!settings.getString("nav-url", "").isEmpty()) queue.add(done -> DISK.execute(() -> {
            try {
                JSONArray list = navLists(true);
                MAIN.post(() -> {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null || item.optString("id").isEmpty()) continue;
                        String id = item.optString("id");
                        queue.add(result -> DISK.execute(() -> {
                            try { navList(id, true); MAIN.post(() -> result.accept(true)); }
                            catch (Exception error) { MAIN.post(() -> result.accept(false)); }
                        }));
                    }
                    done.accept(true);
                });
            } catch (Exception error) { MAIN.post(() -> done.accept(false)); }
        }));
        pump();
    }
    private void refreshOnline(String operation, JSONObject args, java.util.function.Consumer<Boolean> done) {
        online(operation, args, false, (value, error) -> {
            JSONArray unavailable = value instanceof JSONObject data ? data.optJSONArray("failed") : null;
            if (error == null && "all".equals(args.optString("source"))) {
                rememberDiscoveryCovers(DISCOVERY_LISTS, ChartArtwork.catalog(value), true);
            }
            done.accept(error == null && (unavailable == null || unavailable.length() == 0));
        });
    }
    private void refreshDetail(JSONObject args, int page, java.util.Set<String> known, java.util.function.Consumer<Boolean> done) {
        JSONObject request = MediaSources.args("source", args.optString("source"), "id", args.optString("id"), "page", page);
        online("list", request, false, (value, error) -> {
            if (error != null || !(value instanceof JSONObject result)) { done.accept(false); return; }
            JSONArray songs = result.optJSONArray("list");
            int before = known.size();
            for (int i = 0; songs != null && i < songs.length(); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                MediaEntry entry = MediaEntry.online(song);
                known.add(entry.id().isEmpty() ? entry.title() + "\n" + entry.subtitle() : entry.id());
            }
            if (result.optBoolean("more") && known.size() > before && page < 60) refreshDetail(args, page + 1, known, done);
            else done.accept(!result.optBoolean("more") || known.size() == before);
        });
    }
    private void pump() {
        while (inProgress < 3 && !queue.isEmpty()) {
            Task task = queue.remove();
            inProgress++;
            task.start(ok -> MAIN.post(() -> {
                inProgress--;
                if (ok) updated++; else failed++;
                pump();
            }));
        }
        if (inProgress != 0 || !running) return;
        running = false;
        if (updated > 0) settings.edit().putLong("playlist-refresh-at", nextTime).apply();
        Report report = new Report(updated, failed);
        var callbacks = new ArrayList<>(listeners);
        listeners.clear();
        for (var callback : callbacks) callback.accept(report);
    }
}
