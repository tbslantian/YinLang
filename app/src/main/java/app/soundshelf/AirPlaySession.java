package app.soundshelf;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

final class AirPlaySession implements AutoCloseable {
    static {
        System.loadLibrary("yinlang_airplay");
    }

    static final int FAILED = 0, CONNECTED = 1, DISCONNECTED = 2, PIN_REQUIRED = 3, CREDENTIALS = 4;
    interface Listener { void event(AirPlaySession session, int event, String message); }

    final AirPlayDiscovery.Device device;
    private final SharedPreferences settings;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long handle = create();
    private boolean closed;

    AirPlaySession(AirPlayDiscovery.Device device, SharedPreferences settings, Listener listener) {
        this.device = device;
        this.settings = settings;
        this.listener = listener;
    }

    void start(String password) {
        String credentials = settings.getString(credentialKey(), "");
        String identity = settings.getString("sender-id", "");
        if (identity.isEmpty()) {
            byte[] bytes = new byte[6];
            new SecureRandom().nextBytes(bytes);
            bytes[0] = (byte) ((bytes[0] & 0xfc) | 2);
            identity = String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
            settings.edit().putString("sender-id", identity).apply();
        }
        String senderId = identity;
        long current = handle;
        new Thread(() -> {
            try {
                run(current, utf8(device.host()), device.port(), utf8(device.name()), utf8(device.id()),
                    utf8(senderId), credentials.isEmpty() ? device.mode() : AirPlayDiscovery.PIN, utf8(credentials), utf8(password));
            } finally {
                synchronized (this) {
                    destroy(current);
                    handle = 0;
                }
            }
        }, "YinLang AirPlay").start();
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String credentialKey() { return "receiver:" + device.id(); }
    void forgetPairing() { settings.edit().remove(credentialKey()).apply(); }

    @SuppressWarnings("unused") // Native callback.
    private void onNativeEvent(int code, byte[] data) {
        String message = new String(data, StandardCharsets.UTF_8);
        main.post(() -> {
            synchronized (this) { if (closed) return; }
            if (code == CREDENTIALS) settings.edit().putString(credentialKey(), message).apply();
            else listener.event(this, code, message);
        });
    }

    boolean audio(short[] samples, int length) {
        long until = System.nanoTime() + 800_000_000L;
        do {
            synchronized (this) {
                if (closed || handle == 0) return false;
                if (write(handle, samples, length)) return true;
            }
            if (Thread.currentThread().isInterrupted()) return false;
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
        } while (System.nanoTime() < until);
        return false;
    }

    synchronized void discardAudio() { if (!closed && handle != 0) discard(handle); }
    synchronized void playback(boolean active) { if (!closed && handle != 0) playing(handle, active); }
    synchronized void submitPin(String code) { if (!closed && handle != 0) pin(handle, utf8(code)); }
    synchronized void setVolume(int percent) { if (!closed && handle != 0) volume(handle, percent); }
    synchronized void setSampleRate(int rate) { if (!closed && handle != 0) sampleRate(handle, rate); }
    synchronized void nowPlaying(String title, String artist, String album) {
        if (!closed && handle != 0) track(handle, utf8(title), utf8(artist), utf8(album));
    }

    @Override public synchronized void close() {
        closed = true;
        if (handle != 0) stop(handle);
    }

    private static native long create();
    private native void run(long handle, byte[] host, int port, byte[] name, byte[] receiverId, byte[] localId,
                            int mode, byte[] credentials, byte[] password);
    private static native boolean write(long handle, short[] data, int length);
    private static native void discard(long handle);
    private static native void playing(long handle, boolean playing);
    private static native void pin(long handle, byte[] code);
    private static native void volume(long handle, int value);
    private static native void sampleRate(long handle, int rate);
    private static native void track(long handle, byte[] title, byte[] artist, byte[] album);
    private static native void stop(long handle);
    private static native void destroy(long handle);
}
