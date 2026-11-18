package app.soundshelf;

import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BookDiscoveryTest {
    @Test public void menusPreserveOpaquePodcastAndRadioAddresses() throws Exception {
        var rows = new JSONArray("[{\"title\":\"主题\",\"tabs\":[{\"title\":\"故事\",\"url\":\"podcast:topic:故事\"},"
            + "{\"title\":\"重复\",\"url\":\"podcast:topic:故事\"},{\"title\":\"电台\",\"url\":\"radio:cn#1\"},"
            + "{\"title\":\"空地址\"}]},{\"title\":\"空分组\",\"tabs\":[]},null]");
        var menus = BookDiscovery.menus(rows);
        assertEquals(1, menus.size());
        assertEquals(List.of(new BookDiscovery.Tab("故事", "podcast:topic:故事"),
            new BookDiscovery.Tab("电台", "radio:cn#1")), menus.get(0).tabs());
    }

    @Test public void pagesDeduplicateBooksAndStopCursorLoops() throws Exception {
        var page = new BookDiscovery.Page("radio:cn#1");
        page.accept("radio:cn#1", new JSONObject("{\"books\":[{\"title\":\"甲\",\"bookUrl\":\"live:a\"},"
            + "{\"title\":\"缺地址\"}],\"nextUrl\":\"radio:cn#2\",\"currentPage\":1,\"totalPage\":2}"));
        assertEquals("radio:cn#2", page.next);
        page.accept("radio:cn#2", new JSONObject("{\"books\":[{\"title\":\"甲新版\",\"bookUrl\":\"live:a\"},"
            + "{\"title\":\"乙\",\"bookUrl\":\"live:b\"}],\"nextUrl\":\"radio:cn#1\",\"currentPage\":2}"));
        assertEquals(List.of("live:a", "live:b"), List.copyOf(page.books.keySet()));
        assertEquals("甲新版", page.books.get("live:a").getString("title"));
        assertEquals("", page.next);
        assertEquals(2, page.current);
    }

    @Test public void noNextCursorNeverGuessesAPluginAddressFromPageNumbers() throws Exception {
        var page = new BookDiscovery.Page("opaque-search-token");
        page.accept(page.first, new JSONObject("{\"books\":[],\"currentPage\":1,\"totalPage\":99}"));
        assertTrue(page.loaded);
        assertEquals("", page.next);
    }

    @Test public void switchingCategoriesAndSourcesKeepsIndependentContent() throws Exception {
        var first = new BookDiscovery("source-a", "version-a");
        first.menus = BookDiscovery.menus(new JSONArray("[{\"tabs\":[{\"url\":\"one\"},{\"url\":\"two\"}]}]"));
        var cached = first.page();
        cached.accept("one", new JSONObject("{\"books\":[{\"bookUrl\":\"book:one\"}]}"));
        first.tab = 1;
        assertTrue(first.page().books.isEmpty());
        first.tab = 0;
        assertSame(cached, first.page());
        var second = new BookDiscovery("source-b", "version-b");
        second.menus = first.menus;
        assertTrue(second.page().books.isEmpty());
        assertFalse(first.page().books.isEmpty());
    }

    @Test public void invalidMenusAreEmptyAndOutOfRangeSelectionsRecover() throws Exception {
        assertTrue(BookDiscovery.menus(null).isEmpty());
        var state = new BookDiscovery("source", "version");
        assertNull(state.selected());
        state.menus = BookDiscovery.menus(new JSONArray("[{\"tabs\":[{\"title\":\"唯一\",\"url\":\"a\"}]}]"));
        state.menu = 99;
        state.tab = 99;
        assertEquals("a", state.selected().url());
    }

    @Test public void visitingAllBundledSourcesKeepsMenusAndSmallLists() throws Exception {
        var cache = new BookDiscovery.Cache(64, 4000);
        for (int i = 0; i < 39; i++) {
            var state = cachedSource("source-" + i);
            cache.put(state.source, state);
            cache.trim(state);
        }
        for (int i = 0; i < 39; i++) {
            var state = cache.get("source-" + i);
            assertNotNull(state);
            assertTrue(state.menusLoaded);
            assertTrue(state.page().loaded);
        }
    }

    @Test public void bookBudgetTrimsOldContentButPreservesMenusAndActiveSource() throws Exception {
        var cache = new BookDiscovery.Cache(3, 1);
        var first = cachedSource("one");
        var active = cachedSource("two");
        cache.put(first.source, first);
        cache.put(active.source, active);
        cache.trim(active);
        assertTrue(first.menusLoaded);
        assertEquals("category:one", first.selected().url());
        assertFalse(first.page().loaded);
        assertTrue(active.page().loaded);
        var updated = new BookDiscovery("one", "new-plugin-revision");
        cache.put("one", updated);
        assertSame(updated, cache.get("one"));
        assertFalse(cache.get("one").menusLoaded);
    }

    private static BookDiscovery cachedSource(String id) throws Exception {
        var state = new BookDiscovery(id, "plugin-revision");
        state.menus = BookDiscovery.menus(new JSONArray().put(new JSONObject().put("tabs", new JSONArray()
            .put(new JSONObject().put("url", "category:" + id)))));
        state.menusLoaded = true;
        state.page().accept("category:" + id, new JSONObject().put("books", new JSONArray()
            .put(new JSONObject().put("bookUrl", "book:" + id))));
        return state;
    }
}
