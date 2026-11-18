package app.soundshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class MediaCache {
    static final long MB = 1024L * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static MediaCache instance;
    private final Bucket music, books;
    private final SharedPreferences settings;

    static synchronized MediaCache get(Context context) {
        if (instance == null) instance = new MediaCache(context.getApplicationContext());
        return instance;
    }

    private MediaCache(Context context) {
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        var root = new File(context.getCacheDir(), "media-v2");
        music = new Bucket(new File(root, "music"), false);
        books = new Bucket(new File(root, "books"), true);
    }

    private static String preference(boolean book) { return book ? "cache-books-mb" : "cache-music-mb"; }
    int limitMb(boolean book) { return settings.getInt(preference(book), 512); }
    void setLimit(boolean book, int limit) {
        settings.edit().putInt(preference(book), limit).apply();
        bucket(book).trim();
    }
    Bucket bucket(boolean book) { return book ? books : music; }

    static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            char[] key = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                key[2 * i] = HEX[(hash[i] & 255) >>> 4];
                key[2 * i + 1] = HEX[hash[i] & 15];
            }
            return new String(key);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    final class Bucket {
        private record Entry(File file, long size) {}
        private final File directory;
        private final boolean book;
        private final Map<String, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);
        private boolean loaded;
        private long size;
        private long revision;

        Bucket(File directory, boolean book) { this.directory = directory; this.book = book; }

        private void load() {
            if (loaded) return;
            loaded = true;
            File[] files = directory.listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f]{64}"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File file : files) {
                long length = file.length();
                entries.put(file.getName(), new Entry(file, length));
                size += length;
            }
        }
        synchronized long size() { load(); return size; }
        synchronized long revision() { return revision; }
        long audioLimit() { return limitMb(book) * MB; }
        synchronized BitSet audioBlocks(String identity, long length, int blockSize) {
            load();
            var blocks = new BitSet();
            if (length <= 0 || length > audioLimit()) return blocks;
            for (long offset = 0; offset < length; offset += blockSize) {
                Entry entry = entries.get(digest(identity + ":" + offset));
                if (entry != null && entry.file().length() == Math.min(blockSize, length - offset)) {
                    blocks.set((int) (offset / blockSize));
                }
            }
            return blocks;
        }
        synchronized byte[] read(String key) {
            load();
            Entry entry = entries.get(digest(key));
            if (entry == null) return null;
            try {
                byte[] bytes = Files.readAllBytes(entry.file().toPath());
                entry.file().setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (IOException failure) {
                if (!entry.file().exists()) { entries.remove(entry.file().getName()); size -= entry.size(); }
                return null;
            }
        }
        synchronized void write(String key, byte[] data, long expectedRevision) {
            load();
            long limit = limitMb(book) * MB;
            if (revision != expectedRevision || data.length == 0 || data.length > limit) return;
            directory.mkdirs();
            String name = digest(key);
            Entry old = entries.get(name);
            long replaced = old == null ? 0 : old.size();
            evictTo(limit - data.length + replaced, name);
            if (size - replaced + data.length > limit) return;
            File file = new File(directory, name);
            var atomic = new AtomicFile(file);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(data);
                atomic.finishWrite(output);
                entries.put(name, new Entry(file, data.length));
                size += data.length - replaced;
            } catch (IOException error) {
                if (output != null) atomic.failWrite(output);
            }
        }
        synchronized void trim() {
            load();
            evictTo(limitMb(book) * MB, "");
        }
        synchronized void remove(String key) {
            load();
            String name = digest(key);
            Entry entry = entries.get(name);
            if (entry != null && (entry.file().delete() || !entry.file().exists())) {
                entries.remove(name);
                size -= entry.size();
            }
        }
        synchronized void clear() { load(); revision++; evictTo(0, ""); }

        private void evictTo(long limit, String preserve) {
            var iterator = entries.entrySet().iterator();
            while (size > limit && iterator.hasNext()) {
                var item = iterator.next();
                Entry entry = item.getValue();
                if (item.getKey().equals(preserve)) continue;
                if (entry.file().delete() || !entry.file().exists()) {
                    size -= entry.size();
                    iterator.remove();
                }
            }
        }
    }
}
