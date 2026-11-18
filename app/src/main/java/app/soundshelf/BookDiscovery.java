package app.soundshelf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Plugin category addresses are opaque tokens, including podcast and radio cursors. */
final class BookDiscovery {
    record Tab(String title, String url) {}
    record Menu(String title, List<Tab> tabs) {}
    final String source, signature;
    List<Menu> menus = List.of();
    int menu, tab, scroll;
    boolean menusLoaded;
    final LinkedHashMap<String, Page> pages = new LinkedHashMap<>(8, .75f, true);

    BookDiscovery(String source, String signature) { this.source = source; this.signature = signature; }

    /** Keep cheap menus for many sources; trim old book lists independently. */
    static final class Cache {
        private final int maxSources, maxBooks;
        private final LinkedHashMap<String, BookDiscovery> sources = new LinkedHashMap<>(16, .75f, true);

        Cache(int maxSources, int maxBooks) { this.maxSources = maxSources; this.maxBooks = maxBooks; }
        BookDiscovery get(String id) { return sources.get(id); }
        void put(String id, BookDiscovery state) {
            sources.put(id, state);
            while (sources.size() > maxSources) sources.remove(sources.keySet().iterator().next());
        }
        void evictAll() { sources.clear(); }
        void trim(BookDiscovery active) {
            int count = sources.values().stream().mapToInt(Cache::books).sum();
            for (BookDiscovery state : sources.values()) {
                if (count <= maxBooks) break;
                if (state == active) continue;
                count -= books(state);
                state.pages.clear();
                state.scroll = 0;
            }
        }
        private static int books(BookDiscovery state) {
            return state.pages.values().stream().mapToInt(page -> page.books.size()).sum();
        }
    }

    static List<Menu> menus(JSONArray rows) {
        var result = new ArrayList<Menu>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            var tabs = new ArrayList<Tab>();
            Set<String> seen = new HashSet<>();
            JSONArray entries = row.optJSONArray("tabs");
            for (int j = 0; entries != null && j < entries.length(); j++) {
                JSONObject entry = entries.optJSONObject(j);
                if (entry == null) continue;
                String address = entry.optString("url", "");
                if (address.isBlank() || !seen.add(address)) continue;
                tabs.add(new Tab(entry.optString("title", "分类"), address));
            }
            if (!tabs.isEmpty()) result.add(new Menu(row.optString("title", "分类"), List.copyOf(tabs)));
        }
        return List.copyOf(result);
    }

    Tab selected() {
        if (menus.isEmpty()) return null;
        menu = Math.max(0, Math.min(menu, menus.size() - 1));
        tab = Math.max(0, Math.min(tab, menus.get(menu).tabs().size() - 1));
        return menus.get(menu).tabs().get(tab);
    }

    Page page() {
        Tab selected = selected();
        if (selected == null) return null;
        Page value = pages.get(selected.url());
        if (value == null) {
            value = new Page(selected.url());
            pages.put(selected.url(), value);
            if (pages.size() > 12) pages.remove(pages.keySet().iterator().next());
        }
        return value;
    }

    static final class Page {
        final String first;
        final LinkedHashMap<String, JSONObject> books = new LinkedHashMap<>();
        final Set<String> visited = new HashSet<>();
        String next, error = "";
        int current = 1, total = 1;
        boolean loaded;

        Page(String first) { this.first = first; next = first; }
        void accept(String address, JSONObject result) {
            visited.add(address);
            JSONArray rows = result.optJSONArray("books");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject book = rows.optJSONObject(i);
                if (book == null) continue;
                String key = book.optString("bookUrl", "");
                if (!key.isBlank()) books.put(key, book);
            }
            current = Math.max(1, result.optInt("currentPage", visited.size()));
            total = Math.max(current, result.optInt("totalPage", current));
            String cursor = result.optString("nextUrl", "");
            next = cursor.isBlank() || visited.contains(cursor) ? "" : cursor;
            loaded = true;
            error = "";
        }
    }
}
