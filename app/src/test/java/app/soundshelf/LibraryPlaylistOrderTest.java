package app.soundshelf;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.Test;

public class LibraryPlaylistOrderTest {
    private ArrayList<JSONObject> playlists(String... ids) throws Exception {
        var result = new ArrayList<JSONObject>();
        for (String id : ids) result.add(new JSONObject().put("id", id));
        return result;
    }

    @Test public void orderSurvivesReloadAndAppendsNewPlaylists() throws Exception {
        String saved = LibraryPlaylistOrder.encode(playlists("c", "a", "b"));
        var refreshed = playlists("new2", "b", "new1", "c", "a");
        LibraryPlaylistOrder.apply(refreshed, saved);
        assertEquals("[\"c\",\"a\",\"b\",\"new2\",\"new1\"]", LibraryPlaylistOrder.encode(refreshed));
    }

    @Test public void missingAndDuplicateSavedIdsDoNotDuplicateOrHideRows() throws Exception {
        var refreshed = playlists("b", "a", "new");
        LibraryPlaylistOrder.apply(refreshed, "[\"deleted\",\"a\",\"a\",\"b\"]");
        assertEquals("[\"a\",\"b\",\"new\"]", LibraryPlaylistOrder.encode(refreshed));
    }

    @Test public void malformedPreferenceKeepsServerOrder() throws Exception {
        var refreshed = playlists("b", "a");
        LibraryPlaylistOrder.apply(refreshed, "broken");
        assertEquals("[\"b\",\"a\"]", LibraryPlaylistOrder.encode(refreshed));
    }

    @Test public void deletionOnlyRemovesRequestedPlaylist() {
        assertEquals("[\"c\",\"b\"]", LibraryPlaylistOrder.remove("[\"c\",\"a\",\"b\"]", "a"));
        assertEquals("[]", LibraryPlaylistOrder.remove("broken", "a"));
    }
}
