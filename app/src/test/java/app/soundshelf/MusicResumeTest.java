package app.soundshelf;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class MusicResumeTest {
    @Test public void restoresOnlineTrackMetadataForFreshResolution() throws Exception {
        var raw = new JSONObject("{\"songmid\":\"song-1\",\"source\":\"wy\",\"_types\":{\"flac\":{\"size\":1000}}}");
        var entry = new MediaEntry("song-1", "一首歌", "歌手", "https://example.com/cover.jpg", "wy", raw);
        var saved = new MusicResume(entry, "private-library-account", 42000, 180000);
        var restored = MusicResume.read(saved.metadata(), 42000, 180000, "another-account");
        assertNotNull(restored);
        assertEquals(entry.id(), restored.entry().id());
        assertEquals(entry.title(), restored.entry().title());
        assertEquals(entry.cover(), restored.entry().cover());
        assertEquals(1000, restored.entry().raw().getJSONObject("_types").getJSONObject("flac").getInt("size"));
        assertEquals(42000, restored.resumePosition());
        assertFalse(saved.metadata().contains("private-library-account"));
    }

    @Test public void libraryTrackCannotResumeUnderADifferentAccount() throws Exception {
        var entry = MediaEntry.library(new JSONObject("{\"id\":\"123\",\"title\":\"歌曲\",\"coverArt\":\"album-cover\"}"));
        var saved = new MusicResume(entry, "server-and-account-a", 12000, 200000);
        assertNull(MusicResume.read(saved.metadata(), 12000, 200000, "server-and-account-b"));
        var restored = MusicResume.read(saved.metadata(), 12000, 200000, "server-and-account-a");
        assertNotNull(restored);
        assertEquals("album-cover", restored.entry().cover());
        assertEquals("library", restored.entry().source());
    }

    @Test public void offlineTracksPreserveLocalUri() {
        var entry = new MediaEntry("content://downloads/123", "下载歌曲", "", "", "offline", new JSONObject());
        var saved = new MusicResume(entry, "", 1000, 100000);
        var restored = MusicResume.read(saved.metadata(), 1000, 100000, "");
        assertEquals(entry.id(), restored.entry().id());
        assertEquals("offline", restored.entry().source());
    }

    @Test public void completedSongsRestartAndOtherPositionsRemainInBounds() {
        var entry = new MediaEntry("1", "歌曲", "", "", "wy", new JSONObject());
        var saved = new MusicResume(entry, "", 999999, 180000);
        assertEquals(180000, saved.position());
        assertEquals(0, saved.resumePosition());
        assertEquals(0, saved.progress(-1, 180000).position());
        assertEquals(42000, saved.progress(42000, 180000).resumePosition());
        assertEquals(0, saved.progress(179000, 180000).resumePosition());
    }

    @Test public void emptyOrCorruptHistoryDoesNotCreateACard() {
        assertNull(MusicResume.read("", 0, 0, ""));
        assertNull(MusicResume.read("{bad", 0, 0, ""));
        assertNull(MusicResume.read("{}", 0, 0, ""));
        assertNull(MusicResume.read("{\"id\":\"1\"}", 0, 0, ""));
    }
}
