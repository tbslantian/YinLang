package app.soundshelf;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class CoverThumbnailTest {
    @Test public void neteaseCoverUsesThumbnailDimensions() {
        assertEquals("https://p1.music.126.net/top.jpg?param=160x160",
            CoverThumbnail.address("https://p1.music.126.net/top.jpg", 160));
        assertEquals("https://p1.music.126.net/top.jpg?param=320x320&token=abc",
            CoverThumbnail.address("https://p1.music.126.net/top.jpg?param=2000x2000&token=abc", 320));
    }

    @Test public void qqAndKugouOriginalsAreReducedBeforeDownload() {
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R150x150M000abc.jpg",
            CoverThumbnail.address("https://y.gtimg.cn/music/photo_new/T002R800x800M000abc.jpg", 160));
        assertEquals("https://imge.kugou.com/stdmusic/160/top.jpg",
            CoverThumbnail.address("https://imge.kugou.com/stdmusic/1000/top.jpg", 160));
    }
}
