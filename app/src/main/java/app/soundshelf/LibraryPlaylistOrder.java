package app.soundshelf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local order of a server account's own playlists; unknown IDs stay at the end. */
final class LibraryPlaylistOrder {
    static void apply(List<JSONObject> playlists, String saved) {
        var positions = new HashMap<String, Integer>();
        List<String> ids = ids(saved);
        for (int i = 0; i < ids.size(); i++) positions.putIfAbsent(ids.get(i), i);
        playlists.sort(java.util.Comparator.comparingInt(item ->
            positions.getOrDefault(item.optString("id"), Integer.MAX_VALUE)));
    }

    static String encode(List<JSONObject> playlists) {
        var result = new JSONArray();
        for (JSONObject item : playlists) result.put(item.optString("id"));
        return result.toString();
    }

    static String remove(String saved, String id) {
        var result = new JSONArray();
        for (String entry : ids(saved)) if (!entry.equals(id)) result.put(entry);
        return result.toString();
    }

    private static List<String> ids(String saved) {
        var result = new ArrayList<String>();
        try {
            JSONArray values = new JSONArray(saved);
            for (int i = 0; i < values.length(); i++) {
                String id = values.optString(i);
                if (!id.isEmpty()) result.add(id);
            }
        } catch (Exception ignored) { /* A damaged preference must not hide playlists. */ }
        return result;
    }
}
