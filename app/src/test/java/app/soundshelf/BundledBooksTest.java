package app.soundshelf;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class BundledBooksTest {
    @Test public void bundledArchiveIsTheComplete57Pack() throws Exception {
        var pack = PluginArchive.read(Files.readAllBytes(Path.of("src/main/assets/book-sources/Sources-v5.7.zip")));
        assertEquals("ShunSources", pack.name());
        assertEquals("5.7", pack.version());
        assertTrue(pack.scripts().contains("apple博客"));
        assertTrue(pack.scripts().contains("cctv-live:"));
        assertTrue(pack.scripts().contains("podcast:topic:"));
    }

    @Test public void automaticUpgradeRecognizesOnlyOfficialPackAddresses() {
        assertTrue(MediaSources.officialBookAddress(MediaSources.SSTS_SOURCES));
        assertTrue(MediaSources.officialBookAddress("https://raw.githubusercontent.com/whykang/ssts--Android/main/plugins/dist/ShunSources.zip"));
        assertTrue(MediaSources.officialBookAddress("https://github.com/whykang/ssts--Android/releases/download/v5.6/Sources-v5.6.zip"));
        assertFalse(MediaSources.officialBookAddress("https://example.com/custom/ShunSources.zip"));
        assertFalse(MediaSources.officialBookAddress("https://github.com/another-user/ssts--Android/releases/download/v5.6/Sources-v5.6.zip"));
    }

    @Test public void automaticUpgradeDoesNotDowngradeNewerPacks() {
        assertTrue(SourceUpdates.older("5.7", "5.6"));
        assertFalse(SourceUpdates.older("5.7", "5.7"));
        assertFalse(SourceUpdates.older("5.7", "5.8"));
        assertFalse(SourceUpdates.older("5.7", "custom"));
    }
}
