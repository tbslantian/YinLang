package app.soundshelf;

import android.content.SharedPreferences;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class EffectSettings {
    static final int[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    // Preset gains adapted from lxserver 2.2.0, public/music/js/player/sound-effects.js, Apache-2.0.
    enum Preset {
        POP("流行", 6, 5, -3, -2, 5, 4, -4, -3, 6, 4),
        DANCE("舞曲", 4, 3, -4, -6, 0, 0, 3, 4, 4, 5),
        ROCK("摇滚", 7, 6, 2, 1, -3, -4, 2, 1, 4, 5),
        CLASSICAL("古典", 6, 7, 1, 2, -1, 1, -4, -6, -7, -8),
        VOCAL("人声", -5, -6, -4, -3, 3, 4, 5, 4, -3, -3),
        BALLAD("慢歌", 5, 4, 2, 0, -2, 0, 3, 6, 7, 8),
        ELECTRONIC("电子乐", 6, 5, 0, -5, -4, 0, 6, 8, 8, 7),
        BASS("重低音", 8, 7, 5, 4, 0, 0, 0, 0, 0, 0),
        SOFT("柔和", -5, -5, -4, -4, 3, 2, 4, 4, 0, 0);

        final String label;
        final int[] gain;
        Preset(String label, int... gain) { this.label = label; this.gain = gain; }
    }

    enum Room {
        OFF("关闭"), SMALL("小房间"), MEDIUM("室内"), LARGE("影院"),
        HALL("大厅"), CHURCH("教堂"), PLATE("板式");

        final String label;
        Room(String label) { this.label = label; }
    }

    boolean enabled = true;
    final int[] gain = new int[FREQUENCIES.length];
    Room room = Room.OFF;
    int dry = 100, wet = 25, pitch = 100;
    boolean surround;
    int speed = 25, distance = 5;

    static EffectSettings load(SharedPreferences prefs) {
        var settings = new EffectSettings();
        String saved = prefs.getString("music-effects", "");
        if (saved.isEmpty()) {
            for (int i = 0; i < FREQUENCIES.length; i++) {
                int hz = FREQUENCIES[i];
                settings.gain[i] = switch (prefs.getString("player-effect", "ORIGINAL")) {
                    case "BASS" -> hz <= 250 ? 3 : 0;
                    case "VOICE" -> hz >= 350 && hz <= 4000 ? 2 : -1;
                    case "TREBLE" -> hz >= 4000 ? 3 : 0;
                    default -> 0;
                };
            }
            return settings;
        }
        try {
            var json = new JSONObject(saved);
            settings.enabled = json.optBoolean("enabled", true);
            settings.restoreBands(json.optJSONArray("gain"));
            settings.room = Room.valueOf(json.optString("room", "OFF"));
            settings.dry = limit(json.optInt("dry", 100), 0, 100);
            settings.wet = limit(json.optInt("wet", 25), 0, 100);
            settings.pitch = limit(json.optInt("pitch", 100), 50, 200);
            settings.surround = json.optBoolean("surround");
            settings.speed = limit(json.optInt("speed", 25), 1, 50);
            settings.distance = limit(json.optInt("distance", 5), 1, 30);
        } catch (JSONException | IllegalArgumentException ignored) {}
        return settings;
    }

    static int limit(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    boolean hasEq() { return Arrays.stream(gain).anyMatch(value -> value != 0); }
    boolean hasReverb() { return room != Room.OFF && (wet != 0 || dry != 100); }
    boolean hasEffects() { return enabled && (hasEq() || hasReverb() || pitch != 100 || surround); }
    void resetEq() { Arrays.fill(gain, 0); }
    void resetSurround() { surround = false; speed = 25; distance = 5; }
    void reset() {
        resetEq();
        resetSurround();
        room = Room.OFF;
        dry = pitch = 100;
        wet = 25;
    }

    String presetName() {
        for (Preset preset : Preset.values()) if (Arrays.equals(gain, preset.gain)) return preset.label;
        return hasEq() ? "自定义" : "";
    }

    float gainAt(float frequency) {
        int hi = 0;
        while (hi < FREQUENCIES.length && frequency > FREQUENCIES[hi]) hi++;
        if (hi == 0) return gain[0];
        if (hi == FREQUENCIES.length) return gain[hi - 1];
        int lo = hi - 1;
        float ratio = (float) (Math.log(frequency / FREQUENCIES[lo]) / Math.log((double) FREQUENCIES[hi] / FREQUENCIES[lo]));
        return gain[lo] + (gain[hi] - gain[lo]) * ratio;
    }

    void restoreBands(JSONArray bands) {
        if (bands == null) return;
        for (int i = 0; i < gain.length; i++) gain[i] = limit(bands.optInt(i), -12, 12);
    }

    JSONArray bands() {
        var bands = new JSONArray();
        for (int value : gain) bands.put(value);
        return bands;
    }

    void save(SharedPreferences prefs) {
        JSONObject json = MediaSources.args("enabled", enabled, "gain", bands(), "room", room.name(),
            "dry", dry, "wet", wet, "pitch", pitch, "surround", surround, "speed", speed, "distance", distance);
        prefs.edit().putString("music-effects", json.toString()).apply();
    }
}
