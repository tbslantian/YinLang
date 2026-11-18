package app.soundshelf;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

final class AirPlayDiscovery implements AutoCloseable, NsdManager.DiscoveryListener {
    static final String SERVICE = "_raop._tcp.";
    static final int OPEN = 0, PASSWORD = 1, AIRPORT = 2, TRANSIENT = 3, PIN = 4;
    record Device(String id, String name, String host, int port, int mode) {}

    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BiConsumer<List<Device>, String> changed;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final HashSet<String> seen = new HashSet<>();
    private final LinkedHashMap<String, Device> devices = new LinkedHashMap<>();
    private boolean closed, resolving;

    AirPlayDiscovery(Context context, BiConsumer<List<Device>, String> changed) {
        manager = context.getSystemService(NsdManager.class);
        this.changed = changed;
    }

    void start() {
        try { manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, this); }
        catch (RuntimeException failure) { reportFailure(); }
    }

    @Override public void onDiscoveryStarted(String type) {}
    @Override public void onDiscoveryStopped(String type) {}
    @Override public void onStartDiscoveryFailed(String type, int code) { main.post(this::reportFailure); }
    @Override public void onStopDiscoveryFailed(String type, int code) {}

    @Override public void onServiceFound(NsdServiceInfo service) {
        main.post(() -> {
            if (closed || !seen.add(service.getServiceName())) return;
            pending.add(service);
            resolveNext();
        });
    }

    @Override public void onServiceLost(NsdServiceInfo service) {
        main.post(() -> {
            if (closed) return;
            String name = service.getServiceName();
            seen.remove(name);
            pending.removeIf(item -> item.getServiceName().equals(name));
            if (devices.remove(name) != null) publish("");
        });
    }

    private void resolveNext() {
        if (closed || resolving || pending.isEmpty()) return;
        var service = pending.removeFirst();
        resolving = true;
        var listener = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo ignored, int code) {
                main.post(() -> resolved(service.getServiceName(), null));
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                main.post(() -> resolved(service.getServiceName(), info));
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) manager.resolveService(service, getExecutor(), listener);
            else manager.resolveService(service, listener);
        } catch (RuntimeException failure) { resolved(service.getServiceName(), null); }
    }

    private java.util.concurrent.Executor getExecutor() { return main::post; }

    private void resolved(String key, NsdServiceInfo service) {
        if (closed) return;
        resolving = false;
        if (service != null && seen.contains(key)) {
            List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34 ? service.getHostAddresses()
                : service.getHost() == null ? List.of() : List.of(service.getHost());
            for (InetAddress host : addresses) {
                if (!(host instanceof Inet4Address) || host.isAnyLocalAddress() || host.isLoopbackAddress()
                    || host.isMulticastAddress() || service.getPort() < 1) continue;
                int separator = key.indexOf('@');
                String name = separator < 0 ? key : key.substring(separator + 1);
                String id = separator < 0 ? key : key.substring(0, separator).toUpperCase(Locale.ROOT);
                devices.put(key, new Device(id, name, host.getHostAddress(), service.getPort(), mode(service)));
                publish("");
                break;
            }
        } else seen.remove(key);
        resolveNext();
    }

    private static int mode(NsdServiceInfo device) {
        String model = txt(device, "am").toLowerCase(Locale.ROOT);
        boolean television = model.startsWith("appletv") && !model.matches("appletv[123],.*");
        long flags = hex(txt(device, "sf"));
        String features = txt(device, "ft");
        String[] featureParts = features.split(",", 2);
        long highFeatures = featureParts.length == 2 ? hex(featureParts[1]) : 0;
        // Modern Apple TVs use persistent HAP pairing; start the on-screen PIN
        // flow directly rather than guessing transient pairing from pw/sf.
        if (television) return PIN;
        if (television || model.startsWith("audioaccessory") || model.startsWith("mac")
            || (highFeatures & 0x10000L) != 0 || (flags & 0x200) != 0) {
            return (flags & 0x200) != 0 || (television && "true".equalsIgnoreCase(txt(device, "pw"))) ? PIN : TRANSIENT;
        }
        if ("true".equalsIgnoreCase(txt(device, "pw"))) return PASSWORD;
        return model.startsWith("airport") && List.of(txt(device, "et").split(",")).contains("4") ? AIRPORT : OPEN;
    }

    private static long hex(String value) {
        try { return Long.parseUnsignedLong(value.replaceFirst("(?i)^0x", ""), 16); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String txt(NsdServiceInfo service, String key) {
        byte[] value = service.getAttributes().get(key);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private void publish(String status) { if (!closed) changed.accept(List.copyOf(devices.values()), status); }
    private void reportFailure() { publish("无法搜索接收设备，请检查局域网连接"); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        try { manager.stopServiceDiscovery(this); }
        catch (IllegalArgumentException | IllegalStateException ignored) {}
    }
}
