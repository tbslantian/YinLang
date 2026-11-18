package app.soundshelf;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class SourceUpdates {
    private static final int JOB = 9046;
    private static final long DAY = 86_400_000;
    private static SourceUpdates instance;
    private final Context context;
    private final MediaSources sources;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Runnable> observers = new ArrayList<>();
    private final List<java.util.function.Consumer<Report>> waiting = new ArrayList<>();
    private final Set<String> installing = new HashSet<>();
    private boolean checking;
    private int checked, found, applied, failed;

    record Item(String group, String id, String name, String version, String address) {
        boolean book() { return group.equals("book-plugins"); }
    }
    record Report(int checked, int found, int applied, int failed) {}
    private record Package(String code, PluginArchive plugin, String version, String address, String finalAddress) {}
    private record Download(byte[] bytes, String finalAddress) {}

    static synchronized SourceUpdates get(Context context) {
        if (instance == null) instance = new SourceUpdates(context.getApplicationContext());
        return instance;
    }
    private SourceUpdates(Context context) {
        this.context = context;
        sources = new MediaSources(context);
        prefs = sources.settings;
    }

    void observe(Runnable observer) { observers.add(observer); }
    void removeObserver(Runnable observer) { observers.remove(observer); }
    private void changed() { for (Runnable observer : List.copyOf(observers)) observer.run(); }
    boolean automatic() { return prefs.getBoolean("source-auto-update", false); }
    void setAutomatic(boolean enabled) {
        prefs.edit().putBoolean("source-auto-update", enabled).apply();
        if (enabled) check(true, null);
    }
    void schedule() {
        var scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, RefreshJob.class))
            .setPersisted(true).setPeriodic(DAY).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    List<Item> items() {
        List<Item> result = new ArrayList<>();
        for (String group : List.of("music-sources", "book-plugins")) {
            JSONArray list = sources.registered(group);
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry != null) result.add(new Item(group, entry.optString("id"), entry.optString("name"),
                    entry.optString("version"), entry.optString("update-url")));
            }
        }
        return result;
    }
    private Item find(String id) {
        return items().stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }
    private JSONObject notices() {
        try { return new JSONObject(prefs.getString("source-updates", "{}")); }
        catch (Exception error) { return new JSONObject(); }
    }
    JSONObject notice(String id) { return notices().optJSONObject(id); }
    int count() {
        JSONObject data = notices();
        int count = 0;
        for (var item : items()) if (data.has(item.id())) count++;
        return count;
    }
    private void store(String id, JSONObject notice) {
        JSONObject data = notices();
        if (notice == null) data.remove(id);
        else try { data.put(id, notice); } catch (Exception ignored) {}
        prefs.edit().putString("source-updates", data.toString()).apply();
        changed();
    }
    void receiveAlert(String id, String log, String url) {
        Item item = find(id);
        if (item == null || item.book() || installing.contains(id) || (log.trim().isEmpty() && url.trim().isEmpty())) return;
        try { url = Http.url(url).toString(); } catch (Exception error) { url = ""; }
        JSONObject previous = notice(id);
        if (previous != null && previous.optString("address").equals(url) && previous.optString("log").equals(log)) return;
        if (previous != null && previous.optBoolean("verified") && !previous.optString("address").equals(url)) return;
        store(id, MediaSources.args("address", url, "log", log, "version", previous == null ? "" : previous.optString("version"),
            "verified", previous != null && previous.optBoolean("verified")));
        if (automatic() && !checking && !url.isEmpty()) check(true, null);
    }
    void setAddress(Item item, String input) throws Exception {
        String url = input.isEmpty() ? "" : Http.url(input).toString();
        JSONArray entries = sources.registered(item.group());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject row = entries.optJSONObject(i);
            if (row != null && item.id().equals(row.optString("id"))) row.put("update-url", url);
        }
        prefs.edit().putString(item.group(), entries.toString()).apply();
        store(item.id(), null);
    }
    void check(boolean force, java.util.function.Consumer<Report> done) {
        if (checking) {
            if (done != null) waiting.add(done);
            return;
        }
        if (!force && System.currentTimeMillis() - prefs.getLong("source-check-at", 0) < DAY) {
            if (done != null) main.post(() -> done.accept(new Report(0, count(), 0, 0)));
            return;
        }
        if (done != null) waiting.add(done);
        checking = true;
        checked = found = applied = failed = 0;
        var candidates = items().iterator();
        next(candidates);
    }
    private void next(Iterator<Item> candidates) {
        if (!candidates.hasNext()) {
            checking = false;
            prefs.edit().putLong("source-check-at", System.currentTimeMillis()).apply();
            var result = new Report(checked, found, applied, failed);
            var callbacks = List.copyOf(waiting);
            waiting.clear();
            for (var done : callbacks) done.accept(result);
            changed();
            return;
        }
        Item item = candidates.next();
        JSONObject previous = notice(item.id());
        String hinted = previous == null ? "" : previous.optString("address");
        String address = hinted.isEmpty() ? item.address() : hinted;
        if (address.isEmpty() || installing.contains(item.id())) {
            main.post(() -> next(candidates));
            return;
        }
        checked++;
        io.execute(() -> {
            try {
                Package candidate = load(item, address);
                boolean different = !sources.installedCodeMatches(item.id(), candidate.code());
                main.post(() -> {
                    Item current = find(item.id());
                    if (current != null && current.version().equals(item.version()) && current.address().equals(item.address())
                        && different && !older(current.version(), candidate.version())) {
                        found++;
                        store(item.id(), MediaSources.args("address", address, "version", candidate.version(), "verified", true,
                            "log", previous == null ? "" : previous.optString("log")));
                        if (automatic() && sameOrigin(item.address(), address) && sameOrigin(item.address(), candidate.finalAddress())) {
                            apply(item, candidate, (value, error) -> {
                                if (error == null) applied++; else failed++;
                                next(candidates);
                            });
                            return;
                        }
                    } else if (current != null && !different && previous != null && previous.optBoolean("verified")) store(item.id(), null);
                    next(candidates);
                });
            } catch (Exception error) { main.post(() -> { failed++; next(candidates); }); }
        });
    }
    static boolean older(String installed, String candidate) {
        if (!installed.matches("v?\\d+(\\.\\d+)*") || !candidate.matches("v?\\d+(\\.\\d+)*")) return false;
        String[] old = installed.replaceFirst("^v", "").split("\\.");
        String[] update = candidate.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(old.length, update.length); i++) {
            var first = new java.math.BigInteger(i < old.length ? old[i] : "0");
            var second = new java.math.BigInteger(i < update.length ? update[i] : "0");
            int compare = second.compareTo(first);
            if (compare != 0) return compare < 0;
        }
        return false;
    }
    private static boolean sameOrigin(String first, String second) {
        try {
            URI a = Http.url(first), b = Http.url(second);
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
        } catch (Exception error) { return false; }
    }
    private Package load(Item item, String address) throws Exception {
        Download download = download(address);
        byte[] bytes = download.bytes();
        if (item.book()) {
            PluginArchive plugin = PluginArchive.read(bytes);
            if (!plugin.name().equals(item.name())) throw new IllegalArgumentException("听书插件名称不匹配");
            return new Package(plugin.scripts(), plugin, plugin.version(), address, download.finalAddress());
        }
        String code = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        if (!MediaSources.nameOf(code).equals(item.name())) throw new IllegalArgumentException("音乐源名称不匹配");
        if (code.trim().startsWith("<")) throw new IllegalArgumentException("更新地址需直接指向 JS 文件");
        return new Package(code, null, MediaSources.scriptField(code, "version"), address, download.finalAddress());
    }
    static byte[] fetch(String address) throws Exception { return download(address).bytes(); }
    private static Download download(String address) throws Exception {
        URI url = Http.url(address);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            try {
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (location != null && Set.of(301, 302, 303, 307, 308).contains(status)) {
                    URI next = Http.url(url.resolve(location).toString());
                    if ("https".equalsIgnoreCase(url.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                        throw new IllegalArgumentException("更新地址重定向到了不安全的连接");
                    }
                    url = next;
                    continue;
                }
                if (status != 200) throw new java.io.IOException("更新地址 HTTP " + status);
                try (var stream = connection.getInputStream()) { return new Download(Http.bytes(stream, 4_000_000), url.toString()); }
            } finally { connection.disconnect(); }
        }
        throw new java.io.IOException("更新地址重定向过多");
    }
    void install(Item item, ScriptRuntime.Callback done) {
        JSONObject update = notice(item.id());
        String address = update == null ? item.address() : update.optString("address", item.address());
        if (address.isEmpty()) address = item.address();
        if (address.isEmpty()) { done.complete(null, "请先设置更新地址"); return; }
        if (!installing.add(item.id())) { done.complete(null, "该音源正在更新"); return; }
        String url = address;
        io.execute(() -> {
            try {
                Package candidate = load(item, url);
                main.post(() -> {
                    installing.remove(item.id());
                    if (older(item.version(), candidate.version())) { done.complete(null, "更新地址提供的版本较旧"); return; }
                    if (sources.installedCodeMatches(item.id(), candidate.code())) {
                        store(item.id(), null);
                        done.complete("已是最新版本", null);
                    } else apply(item, candidate, done);
                });
            } catch (Exception error) { main.post(() -> { installing.remove(item.id()); done.complete(null, error.getMessage()); }); }
        });
    }
    private void apply(Item item, Package candidate, ScriptRuntime.Callback done) {
        if (!installing.add(item.id())) { done.complete(null, "该音源正在更新"); return; }
        ScriptRuntime.Callback result = (value, error) -> {
            installing.remove(item.id());
            if (error == null) {
                prefs.edit().putString("source-last-update", "最近已更新：" + item.name()
                    + (candidate.version().isEmpty() ? "" : " " + candidate.version())).apply();
                store(item.id(), null);
            }
            done.complete(value, error);
        };
        String trusted = item.address().isEmpty() ? candidate.address() : item.address();
        if (item.book()) sources.importBooks(candidate.plugin(), trusted, item.id(), result);
        else sources.importMusic(candidate.code(), trusted, item.id(), result);
    }
}
