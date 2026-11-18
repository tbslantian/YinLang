package app.soundshelf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class Navidrome {
    static final int RECENT_ALBUMS_PER_PAGE = 12;
    record RecentSongs(JSONArray tracks, boolean hasMore) {}
    enum DiscoverySection {
        SONGS("随心听", "为你随机挑选的歌曲", ""),
        FREQUENT("常听专辑", "那些值得反复聆听的声音", "frequent"),
        RANDOM("随机推荐", "从音乐库里，发现新的心情", "random"),
        NEWEST("最近添加", "刚刚加入音乐库的专辑", "newest");

        final String title, subtitle, albumType;
        DiscoverySection(String title, String subtitle, String albumType) {
            this.title = title;
            this.subtitle = subtitle;
            this.albumType = albumType;
        }
        boolean random() { return this == SONGS || this == RANDOM; }
    }
    private final String base;
    private final String user;
    private final String password;

    Navidrome(String url, String user, String password) {
        base = Http.base(url).replaceFirst("/rest$", "");
        this.user = user;
        this.password = password;
        if (user.trim().isEmpty()) throw new IllegalArgumentException("请填写 Navidrome 用户名");
    }

    String endpoint(String command, String parameters) throws Exception {
        String salt = UUID.randomUUID().toString().replace("-", "");
        byte[] digest = MessageDigest.getInstance("MD5").digest((password + salt).getBytes(StandardCharsets.UTF_8));
        var token = new StringBuilder();
        for (byte value : digest) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        return base + "/rest/" + command + ".view?u=" + Http.enc(user) + "&s=" + salt
            + "&t=" + token + "&v=1.16.1&c=SoundShelf&f=json" + parameters;
    }

    JSONObject get(String command, String parameters) throws Exception {
        JSONObject response = Http.json(endpoint(command, parameters), Map.of(), null).getJSONObject("subsonic-response");
        if (!"ok".equals(response.optString("status"))) {
            throw new IllegalStateException(response.getJSONObject("error").optString("message", "Navidrome 请求失败"));
        }
        return response;
    }

    JSONArray search(String text, int offset) throws Exception {
        return array(get("search3", "&query=" + Http.enc(text) + "&artistCount=0&albumCount=0&songCount=50&songOffset=" + offset), "searchResult3", "song");
    }

    JSONArray playlists() throws Exception { return array(get("getPlaylists", ""), "playlists", "playlist"); }
    JSONObject playlistDetails(String id) throws Exception { return get("getPlaylist", "&id=" + Http.enc(id)).getJSONObject("playlist"); }
    JSONArray playlist(String id) throws Exception {
        JSONArray entries = playlistDetails(id).optJSONArray("entry");
        return entries == null ? new JSONArray() : entries;
    }
    boolean ownsPlaylist(JSONObject playlist) {
        String owner = playlist.optString("owner");
        return owner.isEmpty() || owner.equalsIgnoreCase(user);
    }
    boolean canEditPlaylistTracks(JSONObject playlist) {
        if (playlist == null) return false;
        // Navidrome computes readonly for the authenticated user, including smart/locked playlists.
        if (playlist.has("readonly") && !playlist.isNull("readonly")) return !playlist.optBoolean("readonly", true);
        String owner = playlist.optString("owner").trim();
        return !owner.isEmpty() && owner.equalsIgnoreCase(user.trim());
    }

    void removeFromPlaylist(String playlistId, JSONArray expected, int position) throws Exception {
        if (position < 0 || position >= expected.length()) throw new IllegalArgumentException("无效的歌单歌曲位置");
        JSONObject current = playlistDetails(playlistId);
        if (!canEditPlaylistTracks(current)) throw new IllegalStateException("当前账号没有修改此歌单的权限");
        JSONArray entries = current.optJSONArray("entry");
        if (entries == null) entries = new JSONArray();
        // The API removes by zero-based position. Never reuse a position after another client edits the list.
        if (entries.length() != expected.length()) throw new IllegalStateException("歌单内容已发生变化，请刷新后重试");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject actual = entries.optJSONObject(i), before = expected.optJSONObject(i);
            if (actual == null || before == null || before.optString("id").isEmpty()
                || !before.optString("id").equals(actual.optString("id"))) {
                throw new IllegalStateException("歌单内容已发生变化，请刷新后重试");
            }
        }
        get("updatePlaylist", "&playlistId=" + Http.enc(playlistId) + "&songIndexToRemove=" + position);
    }
    void createPlaylist(String name, String firstSongId) throws Exception {
        String title = name.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("请输入歌单名称");
        get("createPlaylist", "&name=" + Http.enc(title) + (firstSongId.isEmpty() ? "" : "&songId=" + Http.enc(firstSongId)));
    }
    void editPlaylist(String id, String name, String comment, boolean shared) throws Exception {
        String title = name.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("请输入歌单名称");
        get("updatePlaylist", "&playlistId=" + Http.enc(id) + "&name=" + Http.enc(title)
            + "&comment=" + Http.enc(comment) + "&public=" + shared);
    }
    void deletePlaylist(String id) throws Exception {
        get("deletePlaylist", "&id=" + Http.enc(id));
    }
    boolean addToPlaylist(String playlistId, String songId) throws Exception {
        JSONArray entries = playlist(playlistId);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject song = entries.optJSONObject(i);
            if (song != null && songId.equals(song.optString("id"))) return false;
        }
        get("updatePlaylist", "&playlistId=" + Http.enc(playlistId) + "&songIdToAdd=" + Http.enc(songId));
        return true;
    }
    JSONArray albums(int offset) throws Exception { return newestAlbums(offset, 50); }
    JSONArray discovery(DiscoverySection section, int offset, int size) throws Exception {
        if (offset < 0 || size < 1 || size > 500 || (section.random() && offset != 0)) {
            throw new IllegalArgumentException("无效的推荐分页参数");
        }
        if (section == DiscoverySection.SONGS) {
            return array(get("getRandomSongs", "&size=" + size), "randomSongs", "song");
        }
        return array(get("getAlbumList2", "&type=" + section.albumType + "&size=" + size + "&offset=" + offset), "albumList2", "album");
    }
    JSONArray newestAlbums(int offset, int size) throws Exception {
        return array(get("getAlbumList2", "&type=newest&size=" + size + "&offset=" + offset), "albumList2", "album");
    }
    RecentSongs recentSongs(int offset) throws Exception {
        JSONArray albums = newestAlbums(offset, RECENT_ALBUMS_PER_PAGE);
        var tracks = new JSONArray();
        for (int i = 0; i < albums.length(); i++) {
            JSONObject record = albums.optJSONObject(i);
            if (record == null || record.optString("id").isEmpty()) continue;
            JSONArray songs = album(record.optString("id"));
            for (int j = 0; j < songs.length(); j++) {
                if (songs.optJSONObject(j) != null) tracks.put(songs.optJSONObject(j));
            }
        }
        return new RecentSongs(tracks, albums.length() == RECENT_ALBUMS_PER_PAGE);
    }
    JSONObject songDetails(String id) throws Exception {
        if (id.isBlank()) throw new IllegalArgumentException("缺少歌曲编号");
        JSONObject song = get("getSong", "&id=" + Http.enc(id)).getJSONObject("song");
        if (!id.equals(song.optString("id"))) throw new IllegalStateException("服务器返回的歌曲信息不匹配");
        return song;
    }
    JSONObject albumDetails(String id) throws Exception {
        if (id.isBlank()) throw new IllegalArgumentException("缺少专辑编号");
        JSONObject album = get("getAlbum", "&id=" + Http.enc(id)).getJSONObject("album");
        if (!id.equals(album.optString("id"))) throw new IllegalStateException("服务器返回的专辑信息不匹配");
        return album;
    }
    JSONArray album(String id) throws Exception { return array(get("getAlbum", "&id=" + Http.enc(id)), "album", "song"); }
    JSONArray artist(String id) throws Exception { return array(get("getArtist", "&id=" + Http.enc(id)), "artist", "album"); }
    JSONArray favorites() throws Exception { return array(get("getStarred2", ""), "starred2", "song"); }
    int songCount() throws Exception {
        JSONObject scan = get("getScanStatus", "").optJSONObject("scanStatus");
        return scan == null ? -1 : scan.optInt("count", -1);
    }
    void favorite(String id, boolean enabled) throws Exception { get(enabled ? "star" : "unstar", "&id=" + Http.enc(id)); }
    JSONArray artists() throws Exception {
        var artists = new JSONArray();
        JSONArray indexes = array(get("getArtists", ""), "artists", "index");
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject group = indexes.optJSONObject(i);
            JSONArray rows = group == null ? null : group.optJSONArray("artist");
            for (int j = 0; rows != null && j < rows.length(); j++) artists.put(rows.get(j));
        }
        return artists;
    }

    String lyrics(MediaEntry song) throws Exception {
        try {
            JSONArray lists = array(get("getLyricsBySongId", "&id=" + Http.enc(song.id())), "lyricsList", "structuredLyrics");
            String unsynced = "";
            for (int i = 0; i < lists.length(); i++) {
                JSONObject item = lists.optJSONObject(i);
                if (item == null) continue;
                JSONArray rows = item.optJSONArray("line");
                if (rows == null || rows.length() == 0) continue;
                boolean synced = item.optBoolean("synced");
                int offset = item.optInt("offset");
                var result = new StringBuilder();
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject row = rows.optJSONObject(j);
                    if (row == null) continue;
                    if (synced && row.has("start")) {
                        long ms = Math.max(0, row.optLong("start") + offset);
                        result.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", ms / 60_000, ms / 1000 % 60, ms % 1000));
                    }
                    result.append(row.optString("value")).append('\n');
                }
                if (synced) return result.toString();
                if (unsynced.isEmpty()) unsynced = result.toString();
            }
            if (!unsynced.trim().isEmpty()) return unsynced;
        } catch (Exception ignored) { /* Older servers support getLyrics only. */ }
        JSONObject result = get("getLyrics", "&artist=" + Http.enc(song.subtitle()) + "&title=" + Http.enc(song.title())).optJSONObject("lyrics");
        return result == null ? "" : result.optString("value");
    }

    private static JSONArray array(JSONObject response, String parent, String child) {
        JSONObject value = response.optJSONObject(parent);
        JSONArray result = value == null ? null : value.optJSONArray(child);
        return result == null ? new JSONArray() : result;
    }
}
