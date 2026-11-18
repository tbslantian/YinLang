package app.soundshelf;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

public class NavidromePlaylistTest {
    private MockWebServer http;
    private Navidrome client;

    @Before public void start() throws Exception {
        http = new MockWebServer();
        http.start();
        client = new Navidrome(http.url("/").toString(), "owner", "password");
    }

    @After public void stop() throws Exception { http.shutdown(); }

    private void ok() { http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\"}}")); }

    @Test public void editEncodesMetadataAndSupportsClearingDescription() throws Exception {
        ok();
        client.editPlaylist("list & 1", "  通勤 & 夜晚  ", "说明 = + ?", true);
        var request = http.takeRequest(1, TimeUnit.SECONDS).getRequestUrl();
        assertEquals("/rest/updatePlaylist.view", request.encodedPath());
        assertEquals("list & 1", request.queryParameter("playlistId"));
        assertEquals("通勤 & 夜晚", request.queryParameter("name"));
        assertEquals("说明 = + ?", request.queryParameter("comment"));
        assertEquals("true", request.queryParameter("public"));
        ok();
        client.editPlaylist("list & 1", "通勤", "", false);
        var cleared = http.takeRequest(1, TimeUnit.SECONDS).getRequestUrl();
        assertEquals("", cleared.queryParameter("comment"));
        assertEquals("false", cleared.queryParameter("public"));
    }

    @Test public void blankNameDoesNotReachServer() {
        assertThrows(IllegalArgumentException.class, () -> client.editPlaylist("1", "  ", "", false));
        assertEquals(0, http.getRequestCount());
    }

    @Test public void deletionUsesPlaylistIdWithoutRemovingSongs() throws Exception {
        ok();
        client.deletePlaylist("列表 & 1");
        var request = http.takeRequest(1, TimeUnit.SECONDS).getRequestUrl();
        assertEquals("/rest/deletePlaylist.view", request.encodedPath());
        assertEquals("列表 & 1", request.queryParameter("id"));
        assertNull(request.queryParameter("playlistId"));
        assertNull(request.queryParameter("songIndexToRemove"));
    }

    @Test public void permissionFailureIsReported() {
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":50,\"message\":\"Not the owner\"}}}"));
        Exception failure = assertThrows(IllegalStateException.class, () -> client.deletePlaylist("shared"));
        assertEquals("Not the owner", failure.getMessage());
    }

    @Test public void trackEditPermissionsUseServerReadonlyWithStrictOwnerFallback() throws Exception {
        assertTrue(client.canEditPlaylistTracks(new JSONObject("{\"owner\":\"owner\",\"readonly\":false}")));
        assertFalse(client.canEditPlaylistTracks(new JSONObject("{\"owner\":\"owner\",\"readonly\":true}")));
        assertFalse(client.canEditPlaylistTracks(new JSONObject("{\"owner\":\"someone-else\",\"public\":true}")));
        assertTrue(client.canEditPlaylistTracks(new JSONObject("{\"owner\":\"OWNER\"}")));
        assertFalse(client.canEditPlaylistTracks(new JSONObject()));
        assertFalse(client.canEditPlaylistTracks(null));
    }

    @Test public void removalTargetsOnlyTheSelectedOccurrenceUsingOriginalZeroBasedPosition() throws Exception {
        var snapshot = new JSONArray("[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"a\"}]");
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\",\"playlist\":{\"id\":\"list & 1\",\"owner\":\"owner\",\"readonly\":false,\"entry\":" + snapshot + "}}}"));
        ok();
        client.removeFromPlaylist("list & 1", snapshot, 2);
        assertEquals("/rest/getPlaylist.view", http.takeRequest(1, TimeUnit.SECONDS).getRequestUrl().encodedPath());
        var request = http.takeRequest(1, TimeUnit.SECONDS).getRequestUrl();
        assertEquals("/rest/updatePlaylist.view", request.encodedPath());
        assertEquals("list & 1", request.queryParameter("playlistId"));
        assertEquals("2", request.queryParameter("songIndexToRemove"));
        assertNull(request.queryParameter("songIdToAdd"));
        assertNull(request.queryParameter("name"));
        assertEquals(2, http.getRequestCount());
    }

    @Test public void revokedPermissionStopsRemovalBeforeUpdate() throws Exception {
        var snapshot = new JSONArray("[{\"id\":\"a\"}]");
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\",\"playlist\":{\"owner\":\"owner\",\"readonly\":true,\"entry\":" + snapshot + "}}}"));
        Exception failure = assertThrows(IllegalStateException.class, () -> client.removeFromPlaylist("list", snapshot, 0));
        assertEquals("当前账号没有修改此歌单的权限", failure.getMessage());
        assertEquals(1, http.getRequestCount());
    }

    @Test public void changedPlaylistOrderStopsRemovalInsteadOfDeletingTheWrongSong() throws Exception {
        var snapshot = new JSONArray("[{\"id\":\"a\"},{\"id\":\"b\"}]");
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\",\"playlist\":{\"owner\":\"owner\",\"readonly\":false,\"entry\":[{\"id\":\"b\"},{\"id\":\"a\"}]}}}"));
        Exception failure = assertThrows(IllegalStateException.class, () -> client.removeFromPlaylist("list", snapshot, 0));
        assertEquals("歌单内容已发生变化，请刷新后重试", failure.getMessage());
        assertEquals(1, http.getRequestCount());
    }

    @Test public void invalidOccurrenceDoesNotSendAnyRequest() throws Exception {
        var snapshot = new JSONArray("[{\"id\":\"a\"}]");
        assertThrows(IllegalArgumentException.class, () -> client.removeFromPlaylist("list", snapshot, -1));
        assertThrows(IllegalArgumentException.class, () -> client.removeFromPlaylist("list", snapshot, 1));
        assertEquals(0, http.getRequestCount());
    }

    @Test public void serverAuthorizationFailureDuringUpdateIsReported() throws Exception {
        var snapshot = new JSONArray("[{\"id\":\"a\"}]");
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\",\"playlist\":{\"owner\":\"owner\",\"entry\":" + snapshot + "}}}"));
        http.enqueue(new MockResponse().setBody("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":50,\"message\":\"Not the owner\"}}}"));
        Exception failure = assertThrows(IllegalStateException.class, () -> client.removeFromPlaylist("list", snapshot, 0));
        assertEquals("Not the owner", failure.getMessage());
        assertEquals(2, http.getRequestCount());
    }
}
