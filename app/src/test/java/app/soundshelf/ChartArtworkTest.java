package app.soundshelf;

import static org.junit.Assert.assertEquals;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ChartArtworkTest {
    @Test public void chartFallbackUsesOnlyRankOne() throws Exception {
        var payload = new JSONObject("{\"cover\":\"https://example.com/board.jpg\",\"list\":[{\"img\":\"https://example.com/top.jpg\"},{\"img\":\"https://example.com/second.jpg\"}]}");
        assertEquals(List.of("https://example.com/top.jpg"), ChartArtwork.topTrackCover(payload));
    }

    @Test public void missingTopCoverKeepsPlaceholderInsteadOfTryingOtherRanks() throws Exception {
        var payload = new JSONObject("{\"list\":[{}, {\"img\":\"https://example.com/second.jpg\"}]}");
        assertEquals(List.of(), ChartArtwork.topTrackCover(payload));
        assertEquals(List.of(), ChartArtwork.topTrackCover(null));
    }
}
