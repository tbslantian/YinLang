package app.soundshelf;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChartArtwork {
    private static final Set<String> BATCH_SOURCES = Set.of("wy", "tx", "kg", "kw");
    private static final List<String> FIELDS = List.of("coverImgUrl", "cover", "img", "coverUrl", "pic", "picUrl", "imgurl", "imgUrl", "image");
    private static final int MAX_CANDIDATES = 3;

    private ChartArtwork() {}

    static boolean hasBatch(String source) { return BATCH_SOURCES.contains(source); }

    static String address(JSONObject entry) {
        if (entry == null) return "";
        for (String field : FIELDS) {
            String address = entry.optString(field).strip().replace("{size}", "400");
            if (address.startsWith("//")) address = "https:" + address;
            if (!address.startsWith("http://") && !address.startsWith("https://")) continue;
            try { return Http.url(address).toString(); }
            catch (IllegalArgumentException ignored) { /* The next field may have a usable URL. */ }
        }
        return "";
    }

    static String fromBatch(Object payload, String id) {
        JSONArray rows = payload instanceof JSONObject result ? result.optJSONArray("list") : null;
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) return address(row);
        }
        return "";
    }

    static List<String> catalog(Object payload) {
        return catalog(payload, Integer.MAX_VALUE);
    }

    static List<String> trackCovers(Object payload) {
        return catalog(payload, 6);
    }

    static List<String> topTrackCover(Object payload) {
        JSONArray rows = payload instanceof JSONObject result ? result.optJSONArray("list") : null;
        String cover = address(rows == null ? null : rows.optJSONObject(0));
        return cover.isEmpty() ? List.of() : List.of(cover);
    }

    private static List<String> catalog(Object payload, int limit) {
        var covers = new LinkedHashSet<String>();
        JSONArray rows = payload instanceof JSONObject result ? result.optJSONArray("list") : null;
        for (int i = 0; rows != null && i < rows.length() && covers.size() < limit; i++) add(covers, rows.optJSONObject(i));
        return List.copyOf(covers);
    }

    static List<String> fromDetail(Object payload) {
        if (!(payload instanceof JSONObject result)) return List.of();
        var covers = new LinkedHashSet<String>();
        add(covers, result);
        add(covers, result.optJSONObject("playlist"));
        JSONArray tracks = result.optJSONArray("list");
        for (int i = 0; tracks != null && i < tracks.length() && covers.size() < MAX_CANDIDATES; i++) {
            add(covers, tracks.optJSONObject(i));
        }
        return List.copyOf(covers);
    }

    private static void add(Set<String> covers, JSONObject entry) {
        String candidate = address(entry);
        if (!candidate.isEmpty()) covers.add(candidate);
    }
}
