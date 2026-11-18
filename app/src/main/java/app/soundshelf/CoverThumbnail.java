package app.soundshelf;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

final class CoverThumbnail {
    private static final Pattern QQ_SIZE = Pattern.compile("(?<prefix>/music/photo_new/T00[12]R)(\\d+)x\\d+(?=M\\d{3})");
    private static final Pattern NETEASE_SIZE = Pattern.compile("(?<prefix>[?&]param=)\\d+(?:x|%78)\\d+(?=&|$)");
    private static final Pattern KUWO_SIZE = Pattern.compile("(?<prefix>/star/albumcover/|[?&](?:pictype|size)=)\\d+");
    private static final Pattern KUGOU_SIZE = Pattern.compile("(?<prefix>/(?:stdmusic|stdmusicHD|softhead)/)\\d+(?=/)");

    static int edge(int pixels) {
        if (pixels <= 160) return 160;
        return pixels <= 320 ? 320 : 500;
    }

    static String address(String address, int edge) {
        try {
            URI uri = URI.create(address);
            if (uri.getHost() == null || uri.getRawFragment() != null) return address;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".music.126.net")) {
                var size = NETEASE_SIZE.matcher(address);
                if (size.find()) return size.replaceFirst("${prefix}" + edge + "x" + edge);
                if (uri.getRawQuery() == null || !uri.getRawQuery().contains("param=")) {
                    return address + (uri.getRawQuery() == null ? "?" : "&") + "param=" + edge + "x" + edge;
                }
            } else if (host.equals("y.gtimg.cn")) {
                var size = QQ_SIZE.matcher(address);
                if (size.find()) {
                    int supported = edge == 160 ? 150 : edge == 320 ? 300 : 500;
                    if (Integer.parseInt(size.group(2)) > supported) return size.replaceFirst("${prefix}" + supported + "x" + supported);
                }
            } else if (host.endsWith(".kuwo.cn")) {
                int supported = edge >= 500 ? 500 : 300;
                return KUWO_SIZE.matcher(address).replaceAll("${prefix}" + supported);
            } else if (host.endsWith(".kugou.com")) {
                return KUGOU_SIZE.matcher(address).replaceAll("${prefix}" + edge);
            }
        } catch (IllegalArgumentException ignored) {}
        return address;
    }
}
