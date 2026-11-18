package app.soundshelf;

import android.media.MediaDataSource;
import android.os.Bundle;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Locale;
import java.util.regex.Pattern;

final class CachedAudio extends MediaDataSource {
    private static final int BLOCK = 512 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile("(audio-v3:[0-9a-f]{64}) ([0-9]+)");
    private final MediaCache.Bucket cache;
    private final String address, identity;
    private final Bundle headers;
    private final long length, revision;
    private final String indexKey;
    private BitSet parts;
    private Sequential sequential;
    private volatile boolean closed;
    private final Object stateLock = new Object();
    private final Object sequentialLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.FutureTask<byte[]>> fetching = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<HttpURLConnection> connections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService prefetch;
    private BitSet downloaded;
    private volatile long bufferedBytes;
    private volatile boolean indexedReady;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> readyListener = new java.util.concurrent.atomic.AtomicReference<>();
    private record MemoryBlock(long start, byte[] bytes) {}
    private volatile MemoryBlock playbackBlock;
    private final java.util.concurrent.Semaphore rangeSlots = new java.util.concurrent.Semaphore(3, true);

    record Capture(String indexKey, String address, Bundle headers, boolean hls, long revision) {}
    enum Outcome {
        READY("已可离线播放"), STREAM_ONLY("该音源为流式播放，需联网"), TOO_LARGE("歌曲超过音乐缓存上限"),
        INCOMPLETE("音频缓存不完整"), STOPPED("");
        final String label;
        Outcome(String label) { this.label = label; }
    }

    static CachedAudio openOffline(MediaCache cache, String indexKey) {
        if (indexKey.isEmpty() || cache.limitMb(false) == 0) return null;
        var bucket = cache.bucket(false);
        byte[] record = bucket.read(indexKey);
        if (record == null) return null;
        var match = INDEX.matcher(new String(record, StandardCharsets.US_ASCII));
        if (!match.matches()) return null;
        try {
            long size = Long.parseLong(match.group(2));
            String identity = match.group(1);
            if (size <= 0 || size > cache.limitMb(false) * MediaCache.MB) return null;
            if (bucket.audioBlocks(identity, size, BLOCK).cardinality() != blockCount(size)) return null;
            return new CachedAudio(bucket, "", identity, new Bundle(), size, bucket.revision(), "");
        } catch (NumberFormatException ignored) { return null; }
    }

    static CachedAudio open(MediaCache cache, boolean book, String address, Bundle headers, String stableId, String indexKey) throws IOException {
        if (cache.limitMb(book) == 0) return null;
        var uri = Http.url(address);
        if (uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(m3u8?|flv)$")) return null;
        String key = "audio:" + (stableId.isEmpty() ? address : stableId);
        var names = new java.util.ArrayList<>(headers.keySet());
        java.util.Collections.sort(names);
        for (String name : names) key += "\n" + name + ":" + headers.getString(name, "");
        if (!indexKey.isEmpty()) key = "audio-v3:" + MediaCache.digest(indexKey + "\n" + key);
        var bucket = cache.bucket(book);
        long revision = bucket.revision();
        byte[] metadata = bucket.read(key + ":size");
        if (metadata != null) {
            try {
                long size = Long.parseLong(new String(metadata, StandardCharsets.US_ASCII));
                if (size > 0) return new CachedAudio(bucket, address, key, headers, size, revision, indexKey);
            } catch (NumberFormatException ignored) {}
        }
        HttpURLConnection connection = request(address, headers, 0, BLOCK - 1);
        boolean streamOwned = false;
        try {
            if (!indexKey.isEmpty() && java.util.Set.of(400, 405, 416).contains(connection.getResponseCode())) {
                connection.disconnect();
                connection = request(address, headers, -1, -1);
            }
            Range value = range(connection);
            String mime = mime(connection);
            if (isPlaylist(mime) || isInvalid(mime)) return null;
            if (value == null && !indexKey.isEmpty() && connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                long size = connection.getContentLengthLong();
                String encoding = connection.getContentEncoding();
                if (size <= 0 || size > bucket.audioLimit() || (encoding != null && !encoding.equalsIgnoreCase("identity"))) return null;
                var result = new CachedAudio(bucket, address, key, headers, size, revision, indexKey);
                result.sequential = new Sequential(connection, connection.getInputStream());
                result.readSequential(0);
                streamOwned = true;
                return result;
            }
            if (value == null || value.start() != 0 || value.end() >= Math.min(value.total(), BLOCK)) return null;
            byte[] data = new byte[(int) Math.min(value.total(), BLOCK)];
            int received = readRangeChunk(connection, value, data, 0);
            connection.disconnect();
            var result = new CachedAudio(bucket, address, key, headers, value.total(), revision, indexKey);
            result.fillRange(0, data, received);
            result.storeBlock(0, data);
            bucket.write(key + ":size", Long.toString(value.total()).getBytes(StandardCharsets.US_ASCII), revision);
            return result;
        } finally { if (!streamOwned) connection.disconnect(); }
    }

    private CachedAudio(MediaCache.Bucket cache, String address, String identity, Bundle headers, long length, long revision, String indexKey) {
        this.cache = cache; this.address = address; this.identity = identity; this.headers = headers;
        this.length = length; this.revision = revision;
        this.indexKey = indexKey;
        downloaded = cache.audioBlocks(identity, length, BLOCK);
        updateBufferedBytes();
        if (!indexKey.isEmpty() && length <= cache.audioLimit()) {
            parts = cache.audioBlocks(identity, length, BLOCK);
            indexWhenComplete();
        }
    }

    private static long blockCount(long size) { return (size - 1) / BLOCK + 1; }

    private static boolean publish(MediaCache.Bucket bucket, String key, String identity, long size, long revision) {
        synchronized (bucket) {
            if (size <= 0 || !active(bucket, revision) || bucket.audioBlocks(identity, size, BLOCK).cardinality() != blockCount(size)) return false;
            byte[] record = (identity + " " + size).getBytes(StandardCharsets.US_ASCII);
            bucket.write(key, record, revision);
            return java.util.Arrays.equals(record, bucket.read(key))
                && bucket.audioBlocks(identity, size, BLOCK).cardinality() == blockCount(size);
        }
    }

    private void storeBlock(long start, byte[] data) {
        cache.write(identity + ":" + start, data, revision);
        synchronized (stateLock) {
            downloaded.set((int) (start / BLOCK));
            updateBufferedBytes();
            if (parts == null) return;
            parts.set((int) (start / BLOCK));
            indexWhenComplete();
        }
        notifyReady();
    }

    private void updateBufferedBytes() {
        bufferedBytes = Math.min(length, downloaded.nextClearBit(0) * (long) BLOCK);
    }

    long bufferedBytes() { return cache.revision() == revision ? bufferedBytes : 0; }

    void onOfflineReady(Runnable listener) {
        readyListener.set(listener);
        notifyReady();
    }

    private void notifyReady() {
        if (!closed && indexedReady && cache.revision() == revision) {
            var listener = readyListener.getAndSet(null);
            if (listener != null) listener.run();
        }
    }

    /** Fetch ahead independently of MediaPlayer's small read window. */
    synchronized void startPrefetch() {
        if (closed || prefetch != null || address.isEmpty() || length > cache.audioLimit()) return;
        int workers = sequential == null ? 3 : 1;
        prefetch = java.util.concurrent.Executors.newFixedThreadPool(workers);
        var next = new java.util.concurrent.atomic.AtomicLong();
        for (int i = 0; i < workers; i++) prefetch.execute(() -> {
            for (long start; (start = next.getAndAdd(BLOCK)) < length && !closed && active(cache, revision);) {
                try { block(start); }
                catch (IOException failure) { return; } // Playback can retry this range when needed.
            }
        });
    }

    private byte[] block(long start) throws IOException {
        int expected = (int) Math.min(BLOCK, length - start);
        byte[] stored = cache.read(identity + ":" + start);
        if (stored != null && stored.length == expected) return stored;
        if (closed) throw new IOException("音频缓存已关闭");
        if (address.isEmpty()) throw new IOException("离线音频缓存不完整");
        var task = new java.util.concurrent.FutureTask<byte[]>(() -> {
            byte[] data = cache.read(identity + ":" + start);
            if (data != null && data.length == expected) return data;
            if (sequential != null) synchronized (sequentialLock) {
                data = cache.read(identity + ":" + start);
                if (data != null && data.length == expected) return data;
                return readSequential(start);
            }
            data = fetchRange(start, expected);
            if (!closed) storeBlock(start, data);
            return data;
        });
        var existing = fetching.putIfAbsent(start, task);
        var request = existing == null ? task : existing;
        if (existing == null) task.run();
        try { return request.get(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("音频缓存已取消", failure); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failure) {
            throw new IOException("音频分片读取失败", failure.getCause() == null ? failure : failure.getCause());
        } finally { if (request.isDone()) fetching.remove(start, request); }
    }

    private void indexWhenComplete() {
        if (parts.cardinality() != blockCount(length)) return;
        if (publish(cache, indexKey, identity, length, revision)) { parts = null; indexedReady = true; }
        else parts = cache.audioBlocks(identity, length, BLOCK);
    }

    static Outcome finish(MediaCache mediaCache, Capture capture, CachedAudio source) {
        var bucket = mediaCache.bucket(false);
        if (!active(bucket, capture.revision())) return Outcome.STOPPED;
        if (capture.hls()) return Outcome.STREAM_ONLY;
        if (openOffline(mediaCache, capture.indexKey()) != null) return Outcome.READY;
        if (source != null && source.length > bucket.audioLimit()) return Outcome.TOO_LARGE;
        try {
            if (source != null && source.finishParts(capture.revision())) return Outcome.READY;
        } catch (IOException ignored) { /* Try a full response when missing ranges cannot be retrieved. */ }
        if (!active(bucket, capture.revision())) return Outcome.STOPPED;
        try { return captureFile(bucket, capture); }
        catch (IOException | IllegalArgumentException failure) {
            return active(bucket, capture.revision()) ? Outcome.INCOMPLETE : Outcome.STOPPED;
        }
    }

    private boolean finishParts(long expectedRevision) throws IOException {
        BitSet available = cache.audioBlocks(identity, length, BLOCK);
        for (int part = available.nextClearBit(0); part < blockCount(length); part = available.nextClearBit(part + 1)) {
            if (!active(cache, expectedRevision)) return false;
            long start = part * (long) BLOCK;
            synchronized (sequentialLock) {
                int expected = (int) Math.min(BLOCK, length - start);
                byte[] stored = cache.read(identity + ":" + start);
                if (stored != null && stored.length == expected) continue;
                if (sequential != null) readSequential(start);
                else storeBlock(start, fetchRange(start, expected));
            }
        }
        return publish(cache, indexKey, identity, length, expectedRevision);
    }

    private static Outcome captureFile(MediaCache.Bucket bucket, Capture capture) throws IOException {
        String identity = "audio-v3:" + MediaCache.digest(capture.indexKey() + "\n" + java.util.UUID.randomUUID());
        HttpURLConnection connection = request(capture.address(), capture.headers(), -1, -1);
        long length = 0;
        boolean saved = false;
        try {
            String mime = mime(connection);
            if (isPlaylist(mime)) return Outcome.STREAM_ONLY;
            if (isInvalid(mime)) return Outcome.INCOMPLETE;
            long expected = connection.getContentLengthLong();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
                Range response = range(connection);
                if (response == null || response.start() != 0 || response.end() != response.total() - 1) return Outcome.INCOMPLETE;
                expected = response.total();
            } else if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return Outcome.INCOMPLETE;
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) return Outcome.INCOMPLETE;
            if (expected > bucket.audioLimit()) return Outcome.TOO_LARGE;
            try (InputStream stream = connection.getInputStream()) {
                byte[] block = new byte[BLOCK];
                int count;
                while ((count = readBlock(stream, block)) != 0) {
                    if (!active(bucket, capture.revision())) return Outcome.STOPPED;
                    if (length + count > bucket.audioLimit()) return Outcome.TOO_LARGE;
                    if (length == 0) {
                        String prefix = new String(block, 0, Math.min(count, 64), StandardCharsets.UTF_8)
                            .replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
                        if (prefix.startsWith("#extm3u")) return Outcome.STREAM_ONLY;
                        if (prefix.startsWith("<html") || prefix.startsWith("<!doctype") || prefix.startsWith("<?xml")
                            || prefix.startsWith("{") || prefix.startsWith("[")) return Outcome.INCOMPLETE;
                    }
                    bucket.write(identity + ":" + length, count == BLOCK ? block : java.util.Arrays.copyOf(block, count), capture.revision());
                    length += count;
                    if (count < BLOCK) break;
                }
            }
            if (expected >= 0 && expected != length) return Outcome.INCOMPLETE;
            saved = publish(bucket, capture.indexKey(), identity, length, capture.revision());
            return saved ? Outcome.READY : Outcome.INCOMPLETE;
        } finally {
            connection.disconnect();
            if (!saved) for (long offset = 0; offset < length; offset += BLOCK) bucket.remove(identity + ":" + offset);
        }
    }

    private static boolean active(MediaCache.Bucket cache, long revision) {
        return !Thread.currentThread().isInterrupted() && cache.revision() == revision && cache.audioLimit() > 0;
    }

    private static String mime(HttpURLConnection connection) {
        return String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
    }

    private static boolean isPlaylist(String mime) { return mime.contains("mpegurl"); }
    private static boolean isInvalid(String mime) { return mime.contains("html") || mime.contains("json") || mime.contains("xml"); }

    private static int readBlock(InputStream stream, byte[] data) throws IOException {
        return readBytes(stream, data, 0, data.length);
    }

    private static int readBytes(InputStream stream, byte[] data, int offset, int size) throws IOException {
        int used = 0;
        while (used < size) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("音频缓存已取消");
            int count = stream.read(data, offset + used, size - used);
            if (count < 0) break;
            used += count;
        }
        return used;
    }

    private static final class Sequential {
        final HttpURLConnection connection;
        final InputStream input;
        long offset;
        Sequential(HttpURLConnection connection, InputStream input) { this.connection = connection; this.input = input; }
    }

    private byte[] readSequential(long start) throws IOException {
        if (start < sequential.offset) throw new IOException("无法向前读取已清理的音频缓存");
        byte[] data = null;
        try {
            while (sequential.offset <= start) {
                if (closed) throw new IOException("音频缓存已关闭");
                data = new byte[(int) Math.min(BLOCK, length - sequential.offset)];
                if (readBlock(sequential.input, data) != data.length) throw new IOException("音频数据不完整");
                if (!closed) storeBlock(sequential.offset, data);
                sequential.offset += data.length;
            }
            if (sequential.offset == length) sequential.connection.disconnect();
            return data;
        } catch (IOException failure) {
            sequential.connection.disconnect();
            throw failure;
        }
    }

    private static HttpURLConnection request(String address, Bundle headers, long start, long end) throws IOException {
        URI url = Http.url(address);
        boolean credentials = true;
        for (int hop = 0; hop <= 5; hop++) {
            var connection = (HttpURLConnection) url.toURL().openConnection();
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(9500);
            connection.setInstanceFollowRedirects(false);
            for (String name : headers.keySet()) {
                if (name.equalsIgnoreCase("Range") || name.equalsIgnoreCase("Accept-Encoding")) continue;
                if (credentials || !(name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie"))) {
                    connection.setRequestProperty(name, headers.getString(name, ""));
                }
            }
            if (start >= 0) connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setRequestProperty("Accept-Encoding", "identity");
            boolean keep = false;
            try {
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (location == null || !java.util.Set.of(301, 302, 303, 307, 308).contains(status)) {
                    keep = true;
                    return connection;
                }
                URI next = Http.url(url.resolve(location).toString());
                credentials &= url.getScheme().equalsIgnoreCase(next.getScheme()) && url.getHost().equalsIgnoreCase(next.getHost()) && url.getPort() == next.getPort();
                url = next;
            } finally { if (!keep) connection.disconnect(); }
        }
        throw new IOException("音频重定向次数过多");
    }

    private record Range(long start, long end, long total) {}
    private static Range range(HttpURLConnection connection) throws IOException {
        if (connection.getResponseCode() != 206) return null;
        var matcher = RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1)), end = Long.parseLong(matcher.group(2)), total = Long.parseLong(matcher.group(3));
            return start <= end && end < total ? new Range(start, end, total) : null;
        }
        catch (NumberFormatException ignored) { return null; }
    }

    @Override public int readAt(long position, byte[] target, int offset, int count) throws IOException {
        if (closed) throw new IOException("音频缓存已关闭");
        if (count == 0) return 0;
        if (position < 0 || position >= length) return -1;
        int total = (int) Math.min(count, length - position);
        int copied = 0;
        while (copied < total) {
            long cursor = position + copied;
            long start = cursor / BLOCK * BLOCK;
            var memory = playbackBlock;
            byte[] data = memory != null && memory.start() == start ? memory.bytes() : block(start);
            playbackBlock = new MemoryBlock(start, data);
            int within = (int) (cursor - start);
            int available = Math.min(total - copied, data.length - within);
            System.arraycopy(data, within, target, offset + copied, available);
            copied += available;
        }
        return copied;
    }
    private byte[] fetchRange(long start, int expected) throws IOException {
        try { rangeSlots.acquire(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("音频缓存已取消", failure); }
        try {
            byte[] data = new byte[expected];
            fillRange(start, data, 0);
            return data;
        } finally { rangeSlots.release(); }
    }

    private void fillRange(long start, byte[] data, int received) throws IOException {
        long end = start + data.length - 1;
        int failures = 0;
        for (int attempt = 0; received < data.length && attempt < 32; attempt++) {
            if (closed) throw new IOException("音频缓存已关闭");
            HttpURLConnection connection = null;
            try {
                connection = request(address, headers, start + received, end);
                connections.add(connection);
                if (closed) throw new IOException("音频缓存已关闭");
                Range value = range(connection);
                if (value == null || value.start() != start + received || value.end() > end || value.total() != length) {
                    throw new IOException("音频服务器没有返回所请求的片段");
                }
                received += readRangeChunk(connection, value, data, received);
            } catch (IOException failure) {
                if (closed || ++failures >= 3) throw failure;
            } finally { if (connection != null) { connections.remove(connection); connection.disconnect(); } }
        }
        if (received != data.length) throw new IOException("音频分片过小");
    }

    private static int readRangeChunk(HttpURLConnection connection, Range range, byte[] data, int offset) throws IOException {
        int size = (int) (range.end() - range.start() + 1);
        try (InputStream stream = connection.getInputStream()) {
            if (readBytes(stream, data, offset, size) != size || stream.read() != -1) throw new IOException("音频片段大小不符");
        }
        return size;
    }
    @Override public long getSize() { return length; }
    @Override public void close() {
        if (closed) return;
        closed = true;
        readyListener.set(null);
        if (prefetch != null) prefetch.shutdownNow();
        for (var task : fetching.values()) task.cancel(true);
        for (var connection : connections) java.util.concurrent.CompletableFuture.runAsync(connection::disconnect);
        if (sequential != null) java.util.concurrent.CompletableFuture.runAsync(sequential.connection::disconnect);
    }
}
