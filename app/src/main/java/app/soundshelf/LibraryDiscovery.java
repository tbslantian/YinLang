package app.soundshelf;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class LibraryDiscovery {
    record Server(String name, String family, String address) {}

    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final HashSet<String> seen = new HashSet<>();
    private final LinkedHashMap<String, Server> servers = new LinkedHashMap<>();
    private final Consumer<List<Server>> complete;
    private final Runnable timeout = this::finish;
    private boolean closed, resolving;

    LibraryDiscovery(Context context, Consumer<List<Server>> complete) {
        manager = context.getSystemService(NsdManager.class);
        this.complete = complete;
    }

    void start() {
        if (manager == null) { finish(); return; }
        for (String type : List.of("_navidrome._tcp.", "_subsonic._tcp.", "_http._tcp.", "_https._tcp.")) {
            var listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String ignored) {}
                @Override public void onDiscoveryStopped(String ignored) {}
                @Override public void onStartDiscoveryFailed(String ignored, int code) {}
                @Override public void onStopDiscoveryFailed(String ignored, int code) {}
                @Override public void onServiceLost(NsdServiceInfo ignored) {}
                @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> found(info, type)); }
            };
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
                listeners.add(listener);
            } catch (RuntimeException ignored) {}
        }
        if (listeners.isEmpty()) finish();
        else main.postDelayed(timeout, 6500);
    }

    private void found(NsdServiceInfo info, String type) {
        if (closed) return;
        String identity = (info.getServiceName() + " " + type).toLowerCase(Locale.ROOT);
        if (!identity.contains("navidrome") && !identity.contains("subsonic")) return;
        if (seen.add(identity)) pending.add(info);
        resolveNext();
    }

    private void resolveNext() {
        if (closed || resolving || pending.isEmpty()) return;
        resolving = true;
        try {
            manager.resolveService(pending.remove(), new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo ignored, int code) { main.post(() -> resolved(null)); }
                @Override public void onServiceResolved(NsdServiceInfo info) { main.post(() -> resolved(info)); }
            });
        } catch (RuntimeException ignored) { main.post(() -> resolved(null)); }
    }

    private void resolved(NsdServiceInfo info) {
        if (closed) return;
        resolving = false;
        if (info != null) {
            InetAddress host = info.getHost();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                for (InetAddress address : info.getHostAddresses()) {
                    if (!(address instanceof Inet6Address) || !address.isLinkLocalAddress()) { host = address; break; }
                }
            }
            if (host != null && !host.isLinkLocalAddress() && info.getPort() > 0) {
                String identity = (info.getServiceName() + " " + info.getServiceType()).toLowerCase(Locale.ROOT);
                String family = identity.contains("navidrome") ? "Navidrome" : "Subsonic";
                String scheme = identity.contains("_https._tcp") ? "https" : "http";
                byte[] attribute = info.getAttributes().get("path");
                String path = attribute == null ? "" : new String(attribute, StandardCharsets.UTF_8);
                if (!path.startsWith("/") || path.startsWith("//")) path = "";
                String address = host.getHostAddress();
                if (host instanceof Inet6Address) address = "[" + address + "]";
                try {
                    address = Http.base(scheme + "://" + address + ":" + info.getPort() + path);
                    servers.putIfAbsent(address, new Server(info.getServiceName(), family, address));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        resolveNext();
    }

    private void finish() {
        if (closed) return;
        close();
        complete.accept(List.copyOf(servers.values()));
    }

    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        for (NsdManager.DiscoveryListener listener : listeners) {
            try { manager.stopServiceDiscovery(listener); } catch (RuntimeException ignored) {}
        }
        listeners.clear();
        pending.clear();
    }
}
