package app.soundshelf;

import org.json.JSONObject;

/** Keep stable track metadata; resolve a fresh audio address when listening again. */
record MusicResume(MediaEntry entry, String account, int position, int duration) {
    static final String KEY = "music-resume", POSITION_KEY = "music-resume-position", DURATION_KEY = "music-resume-duration";

    MusicResume {
        duration = Math.max(0, duration);
        position = Math.max(0, duration > 0 ? Math.min(position, duration) : position);
    }

    MusicResume progress(int position, int duration) { return new MusicResume(entry, account, position, duration); }

    int resumePosition() { return duration > 0 && position >= duration - 2000 ? 0 : position; }

    boolean available(String currentAccount) { return !"library".equals(entry.source()) || account.equals(currentAccount); }

    String metadata() {
        try {
            return new JSONObject().put("id", entry.id()).put("title", entry.title()).put("subtitle", entry.subtitle())
                .put("cover", entry.cover()).put("source", entry.source()).put("raw", entry.raw())
                .put("account", "library".equals(entry.source()) ? account : "").toString();
        } catch (Exception ignored) { return ""; }
    }

    static MusicResume read(String metadata, int position, int duration, String account) {
        try {
            JSONObject row = new JSONObject(metadata);
            String id = row.optString("id"), source = row.optString("source");
            if (id.isBlank() || source.isBlank()) return null;
            JSONObject raw = row.optJSONObject("raw");
            var entry = new MediaEntry(id, row.optString("title"), row.optString("subtitle"), row.optString("cover"),
                source, raw == null ? new JSONObject() : raw);
            var result = new MusicResume(entry, row.optString("account"), position, duration);
            return result.available(account) ? result : null;
        } catch (Exception ignored) { return null; }
    }
}
