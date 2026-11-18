package app.soundshelf;

import org.json.JSONObject;

record MediaEntry(String id, String title, String subtitle, String cover, String source, JSONObject raw) {
    static String first(JSONObject row, String... keys) {
        for (String key : keys) {
            String value = row.optString(key, "");
            if (!value.trim().isEmpty() && !"null".equals(value)) return value;
        }
        return "";
    }
    static MediaEntry online(JSONObject row) {
        return new MediaEntry(first(row, "songmid", "songId", "copyrightId", "id", "hash"), first(row, "name", "title"),
            first(row, "singer", "artist"), first(row, "img", "pic", "cover", "coverUrl"), row.optString("source"), row);
    }
    static MediaEntry library(JSONObject row) {
        return new MediaEntry(row.optString("id"), first(row, "title", "name"), first(row, "artist", "album"),
            row.optString("coverArt"), "library", row);
    }
    static MediaEntry episode(JSONObject row, String source, String book, String cover) {
        return new MediaEntry(row.optString("url"), first(row, "title", "name"), book, cover, source, row);
    }
}
