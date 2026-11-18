package app.soundshelf;

import java.net.URI;

/** Editable server address components used by the Navidrome login form. */
record LibraryConnection(String host, String port, String path, boolean https) {
    static LibraryConnection parse(String value) {
        if (value == null || value.isBlank()) return new LibraryConnection("", "", "", true);
        try {
            URI uri = Http.url(value);
            return new LibraryConnection(uri.getHost(), uri.getPort() < 0 ? "" : "" + uri.getPort(),
                uri.getPath() == null ? "" : uri.getPath(), "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) { return new LibraryConnection(value, "", "", true); }
    }
    String url() {
        String name = host.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请填写主机地址");
        String number = port.trim();
        int endpoint = -1;
        if (!number.isEmpty()) {
            try { endpoint = Integer.parseInt(number); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("端口须为 1–65535 的数字"); }
            if (endpoint < 1 || endpoint > 65535) throw new IllegalArgumentException("端口须为 1–65535 的数字");
        }
        if (name.contains("://")) throw new IllegalArgumentException("主机地址只填写域名或 IP，协议请用 HTTPS 开关选择");
        String prefix = path.trim();
        if (prefix.contains("?") || prefix.contains("#")) throw new IllegalArgumentException("路径不能包含查询参数或片段");
        if (!prefix.isEmpty() && !prefix.startsWith("/")) prefix = "/" + prefix;
        try { return Http.base(new URI(https ? "https" : "http", null, name, endpoint, prefix, null, null).toASCIIString()); }
        catch (Exception error) { throw new IllegalArgumentException("请检查主机地址、端口及路径"); }
    }
}
