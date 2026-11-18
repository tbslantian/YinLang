package app.soundshelf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A short lived, token protected LAN relay. CachedAudio is owned by PlaybackService. */
final class DlnaStreamServer implements AutoCloseable {
    private final Context context;
    private final String address, mime, token = UUID.randomUUID().toString();
    private final Bundle headers;
    private final CachedAudio cached;
    private final ServerSocket server;
    private final ExecutorService clients = Executors.newFixedThreadPool(3);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> upstreams = ConcurrentHashMap.newKeySet();
    private final String base;
    private volatile boolean closed;
    private volatile String artwork = "";
    private volatile Bundle artworkHeaders = new Bundle();
    private final long size;

    static DlnaStreamServer start(Context context, String rendererHost, String address, Bundle headers,
            CachedAudio cached, boolean hls, String extension, AudioDetails details) throws IOException {
        if (hls) throw new IOException("此音源为 HLS，请更换音源或音质后重试");
        return new DlnaStreamServer(context, rendererHost, address, headers, cached, extension, details);
    }

    private DlnaStreamServer(Context context, String host, String address, Bundle headers,
            CachedAudio cached, String extension, AudioDetails details) throws IOException {
        this.context = context.getApplicationContext();
        this.address = address;
        this.headers = headers == null ? new Bundle() : new Bundle(headers);
        this.cached = cached;
        String format = extension == null || extension.isBlank() ? details.format() : extension;
        if (format.isBlank()) {
            int query = address.indexOf('?');
            String path = query < 0 ? address : address.substring(0, query);
            format = path.substring(path.lastIndexOf('.') + 1);
        }
        mime = switch (format.toLowerCase(Locale.ROOT).replace(".", "")) {
            case "flac" -> "audio/flac";
            case "m4a", "mp4", "alac" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg", "opus" -> "audio/ogg";
            case "wav", "wave" -> "audio/wav";
            default -> "audio/mpeg";
        };
        if (cached != null) size = cached.getSize();
        else if (address.startsWith("file:")) size = new File(URI.create(address)).length();
        else size = details.bytes() > 0 ? details.bytes() : -1;
        InetAddress local;
        try (DatagramSocket route = new DatagramSocket()) {
            var manager = context.getSystemService(ConnectivityManager.class);
            for (var network : manager.getAllNetworks()) {
                var capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    network.bindSocket(route);
                    break;
                }
            }
            route.connect(InetAddress.getByName(host), 1900);
            local = route.getLocalAddress();
        }
        if (local.isAnyLocalAddress() || local.isLoopbackAddress()) throw new IOException("无法连接接收设备所在局域网");
        server = new ServerSocket();
        server.bind(new InetSocketAddress(local, 0), 8);
        String ip = local.getHostAddress();
        base = "http://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + server.getLocalPort() + "/" + token;
        Thread accept = new Thread(this::accept, "YinLang DLNA relay");
        accept.setDaemon(true);
        accept.start();
    }

    String url() { return base + "/audio"; }
    String mimeType() { return mime; }
    long length() { return size; }
    String artworkUrl(String address, Bundle headers) {
        if (address == null || !address.matches("(?i)^https?://.*")) return "";
        artworkHeaders = headers == null ? new Bundle() : new Bundle(headers);
        artwork = address;
        return base + "/art";
    }

    private void accept() {
        while (!closed) try {
            Socket client = server.accept();
            client.setSoTimeout(12000);
            sockets.add(client);
            if (sockets.size() > 8) { sockets.remove(client); client.close(); continue; }
            clients.execute(() -> serve(client));
        } catch (IOException | RejectedExecutionException failure) { if (closed) return; }
    }

    private void serve(Socket client) {
        try (client) {
            var input = new BufferedInputStream(client.getInputStream());
            String first = line(input);
            String[] request = first.split(" ");
            var fields = new HashMap<String,String>();
            int total = first.length();
            while (true) {
                String value = line(input);
                if (value.isEmpty()) break;
                if ((total += value.length()) > 16384) return;
                int colon = value.indexOf(':');
                if (colon > 0) fields.put(value.substring(0, colon).toLowerCase(Locale.ROOT), value.substring(colon+1).trim());
            }
            if (request.length < 2) return;
            boolean head = request[0].equals("HEAD");
            var output = new BufferedOutputStream(client.getOutputStream());
            if (!head && !request[0].equals("GET")) { respond(output, 405, "text/plain", 0, ""); return; }
            boolean art = request[1].equals("/" + token + "/art");
            if (!art && !request[1].equals("/" + token + "/audio")) { respond(output, 404, "text/plain", 0, ""); return; }
            String range = fields.getOrDefault("range", "");
            if (art) remote(output, head, artwork, artworkHeaders, "", true);
            else if (cached != null || address.startsWith("file:") || address.startsWith("content:")) {
                long length = size;
                if (cached == null) {
                    try (var descriptor = context.getContentResolver().openAssetFileDescriptor(android.net.Uri.parse(address), "r")) {
                        if (descriptor == null) throw new IOException("无法读取音频");
                        length = descriptor.getLength();
                    }
                }
                if (length <= 0) { respond(output, 503, "text/plain", 0, ""); return; }
                long[] slice = slice(range, length);
                if (slice == null) { respond(output, 416, mime, 0, "Content-Range: bytes */" + length + "\r\n"); return; }
                long count = slice[1] - slice[0] + 1;
                respond(output, range.isEmpty() ? 200 : 206, mime, count, range.isEmpty() ? "" :
                    "Content-Range: bytes " + slice[0] + "-" + slice[1] + "/" + length + "\r\n");
                if (head) return;
                byte[] buffer = new byte[64 * 1024];
                if (cached != null) {
                    for (long position = slice[0]; position <= slice[1] && !closed;) {
                        int read = cached.readAt(position, buffer, 0, (int)Math.min(buffer.length, slice[1] - position + 1));
                        if (read <= 0) break;
                        output.write(buffer, 0, read);
                        output.flush();
                        position += read;
                    }
                } else try (var data = context.getContentResolver().openInputStream(android.net.Uri.parse(address))) {
                    if (data == null) return;
                    long remaining = slice[0];
                    while (remaining > 0) { long skipped = data.skip(remaining); if (skipped <= 0) { if (data.read() < 0) return; skipped = 1; } remaining -= skipped; }
                    copy(data, output, count);
                }
                output.flush();
            } else remote(output, head, address, headers, range, false);
        } catch (Exception ignored) {
        } finally { sockets.remove(client); }
    }

    private void remote(OutputStream output, boolean head, String url, Bundle values, String range, boolean art) throws IOException {
        if (url.isEmpty()) { respond(output, 404, "text/plain", 0, ""); return; }
        HttpURLConnection connection = null;
        try {
            String current = url;
            for (int redirect = 0; redirect < 6; redirect++) {
                connection = (HttpURLConnection) new URL(current).openConnection();
                upstreams.add(connection);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                for (String name : values.keySet()) connection.setRequestProperty(name, values.getString(name, ""));
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (!range.isEmpty()) connection.setRequestProperty("Range", range);
                if (head) connection.setRequestMethod("HEAD");
                int status = connection.getResponseCode();
                if (status < 300 || status > 399) break;
                String location = connection.getHeaderField("Location");
                if (location == null) break;
                String next = URI.create(current).resolve(location).toString();
                if (!next.matches("(?i)^https?://.*")) throw new IOException("无效重定向");
                // Do not forward secrets to another server after redirects.
                if (!Objects.equals(URI.create(current).getHost(), URI.create(next).getHost())) values = new Bundle();
                upstreams.remove(connection); connection.disconnect(); connection = null;
                current = next;
            }
            if (connection == null) throw new IOException("重定向过多");
            int code = connection.getResponseCode();
            if (code != 200 && code != 206) { respond(output, 502, "text/plain", 0, ""); return; }
            long count = connection.getContentLengthLong();
            String contentRange = connection.getHeaderField("Content-Range");
            String type = art ? connection.getContentType() : mime;
            respond(output, code, type == null ? "image/jpeg" : type, count,
                contentRange == null ? "" : "Content-Range: " + contentRange + "\r\n");
            if (!head) try (var data = connection.getInputStream()) { copy(data, output, count); }
        } finally { if (connection != null) { upstreams.remove(connection); connection.disconnect(); } }
    }

    private void copy(InputStream input, OutputStream output, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count < 0 ? Long.MAX_VALUE : count;
        while (!closed && remaining > 0) {
            int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read); output.flush(); remaining -= read;
        }
    }
    private static void respond(OutputStream out, int code, String type, long count, String extra) throws IOException {
        String headers = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 206 ? "Partial Content" : "Error")
            + "\r\nConnection: close\r\nContent-Type: " + type.replace("\r", "").replace("\n", "")
            + "\r\nAccept-Ranges: bytes\r\ntransferMode.dlna.org: Streaming\r\n"
            + (count >= 0 ? "Content-Length: " + count + "\r\n" : "") + extra + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    private static String line(InputStream input) throws IOException {
        var out = new ByteArrayOutputStream();
        while (out.size() < 8192) { int value = input.read(); if (value < 0 || value == '\n') return new String(out.toByteArray(), StandardCharsets.US_ASCII).trim(); out.write(value); }
        throw new IOException("HTTP header too long");
    }
    private static long[] slice(String range, long size) {
        if (range.isEmpty()) return new long[]{0, size - 1};
        try {
            if (!range.startsWith("bytes=") || range.contains(",")) return null;
            String[] parts = range.substring(6).split("-", -1);
            if (parts.length != 2) return null;
            long start = parts[0].isEmpty() ? Math.max(0, size - Long.parseLong(parts[1])) : Long.parseLong(parts[0]);
            long end = parts[0].isEmpty() || parts[1].isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(parts[1]));
            return start >= 0 && start < size && end >= start ? new long[]{start, end} : null;
        } catch (NumberFormatException failure) { return null; }
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        for (var upstream : upstreams) upstream.disconnect();
        clients.shutdownNow();
    }
}
