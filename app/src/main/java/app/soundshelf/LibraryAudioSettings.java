package app.soundshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.util.Locale;

/** Navidrome streaming preferences. Downloads always use the original download endpoint. */
final class LibraryAudioSettings {
    static final String MOBILE_KEY = "nav-quality-mobile";
    static final String WIFI_KEY = "nav-quality-wifi";
    static final String FORMAT_KEY = "nav-transcode-format";

    enum Quality {
        ORIGINAL("无损", 0), LOW("较低", 128), STANDARD("标准", 192), HIGH("较高", 256), VERY_HIGH("极高", 320);
        final String label;
        final int kbps;
        Quality(String label, int kbps) { this.label = label; this.kbps = kbps; }
        String description() { return this == ORIGINAL ? "不转码，按服务端原始音频播放。" : "转码为约 " + kbps + " kbps。"; }
        static Quality read(String value) {
            try { return valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException failure) { return ORIGINAL; }
        }
    }

    enum Format {
        MP3("MP3", "mp3"), OPUS("OPUS", "opus"), AAC("AAC", "aac");
        final String label, extension;
        Format(String label, String extension) { this.label = label; this.extension = extension; }
        static Format read(String value) {
            try { return valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException failure) { return MP3; }
        }
    }

    record Profile(Quality quality, Format format) {
        String parameters() {
            return quality == Quality.ORIGINAL ? "&format=raw&maxBitRate=0"
                : "&format=" + format.extension + "&maxBitRate=" + quality.kbps;
        }
        String cacheKey() {
            return quality == Quality.ORIGINAL ? "nav-original" : "nav-" + format.extension + "-" + quality.kbps;
        }
        String extension() { return quality == Quality.ORIGINAL ? "" : format.extension; }
        String label() { return quality == Quality.ORIGINAL ? quality.label : format.label + " · " + quality.kbps + " kbps"; }
    }

    static Quality quality(SharedPreferences preferences, boolean wifi) {
        return Quality.read(preferences.getString(wifi ? WIFI_KEY : MOBILE_KEY, Quality.ORIGINAL.name()));
    }
    static Format format(SharedPreferences preferences) {
        return Format.read(preferences.getString(FORMAT_KEY, Format.MP3.name()));
    }
    static Profile profile(Context context, SharedPreferences preferences) {
        return new Profile(quality(preferences, wifi(context)), format(preferences));
    }
    private static boolean wifi(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return false;
        NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (network != null) {
            // An explicitly connected Wi-Fi follows the Wi-Fi preference even on a metered hotspot.
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true;
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false;
        }
        // VPN and other transports do not always expose their underlying network.
        return !connectivity.isActiveNetworkMetered();
    }
}
