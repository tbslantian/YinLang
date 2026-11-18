package app.soundshelf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Discovers DLNA MediaRenderers. All network control methods on Session must run off the UI thread. */
final class DlnaController implements AutoCloseable {
    record Device(String id, String name, URI location, URI avTransport, String avServiceType,
                  URI renderingControl, String renderingServiceType) {
        String host() { return location.getHost(); }
    }
    record Media(String url, String title, String artist, String album, String artwork, String mime, long durationMs) {}
    record Position(long positionMs, long durationMs, String state) {}

    private static final String RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";
    private static final String RENDERING = "urn:schemas-upnp-org:service:RenderingControl:";
    private static final int XML_LIMIT = 256 * 1024;
    private final Context context;
    private final BiConsumer<List<Device>, String> changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "YinLang DLNA discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Device> found = new LinkedHashMap<>();
    private volatile MulticastSocket socket;
    private volatile boolean started, closed;

    DlnaController(Context context, BiConsumer<List<Device>, String> changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
    }

    void start() {
        if (closed || started) return;
        started = true;
        worker.execute(this::discover);
    }

    private void discover() {
        WifiManager.MulticastLock multicast = null;
        try {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("yinlang-dlna-discovery");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
            MulticastSocket channel = new MulticastSocket(null);
            socket = channel;
            channel.setReuseAddress(true);
            channel.bind(new InetSocketAddress(0));
            channel.setTimeToLive(2);
            channel.setSoTimeout(450);
            bindLocalNetwork(channel);
            InetAddress destination = InetAddress.getByName("239.255.255.250");
            for (String target : List.of(RENDERER, "ssdp:all")) {
                byte[] message = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                channel.send(new DatagramPacket(message, message.length, destination, 1900));
            }
            long deadline = System.nanoTime() + 4_500_000_000L;
            byte[] packetBytes = new byte[8192];
            while (!closed && System.nanoTime() < deadline) {
                DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length);
                try { channel.receive(packet); }
                catch (java.net.SocketTimeoutException ignored) { continue; }
                String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.ISO_8859_1);
                String address = header(response, "location");
                if (address.isEmpty()) continue;
                try {
                    URI location = new URI(address);
                    if (!localEndpoint(location, packet.getAddress())) continue;
                    String key = location.toString();
                    synchronized (found) { if (found.values().stream().anyMatch(device -> device.location().toString().equals(key))) continue; }
                    Device device = inspect(location);
                    if (device == null) continue;
                    synchronized (found) { found.put(device.id(), device); }
                    announce("");
                } catch (Exception ignored) { /* A malformed or unrelated SSDP responder is not a renderer. */ }
            }
            if (!closed) announce("");
        } catch (Exception failure) {
            if (!closed) announce("搜索投送设备失败，请确认手机和设备在同一局域网");
        } finally {
            MulticastSocket channel = socket;
            socket = null;
            if (channel != null) channel.close();
            if (multicast != null && multicast.isHeld()) multicast.release();
        }
    }

    private void bindLocalNetwork(MulticastSocket channel) {
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        if (networks == null) return;
        for (Network network : networks.getAllNetworks()) {
            NetworkCapabilities capabilities = networks.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            try { network.bindSocket(channel); return; }
            catch (IOException ignored) { /* The OS may already have the right LAN route. */ }
        }
    }

    private void announce(String error) {
        List<Device> devices;
        synchronized (found) { devices = List.copyOf(found.values()); }
        main.post(() -> { if (!closed) changed.accept(devices, error); });
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return "";
    }

    private static boolean localEndpoint(URI location, InetAddress sender) throws Exception {
        if (!"http".equalsIgnoreCase(location.getScheme()) || location.getHost() == null
            || location.getRawUserInfo() != null || location.getRawFragment() != null) return false;
        InetAddress address = InetAddress.getByName(location.getHost());
        return address.equals(sender) && !address.isLoopbackAddress()
            && (address.isSiteLocalAddress() || address.isLinkLocalAddress());
    }

    private static Device inspect(URI location) throws Exception {
        Document xml = parse(fetch(location, null, null, null));
        Element root = xml.getDocumentElement();
        String baseValue = childText(root, "URLBase");
        URI base = baseValue.isBlank() ? location : location.resolve(baseValue);
        Element renderer = null;
        for (Element device : descendants(root, "device")) {
            if (childText(device, "deviceType").startsWith("urn:schemas-upnp-org:device:MediaRenderer:")) {
                renderer = device;
                break;
            }
        }
        if (renderer == null) return null;
        String name = childText(renderer, "friendlyName");
        String id = childText(renderer, "UDN");
        URI transport = null, rendering = null;
        String transportType = "", renderingType = "";
        for (Element service : descendants(renderer, "service")) {
            String type = childText(service, "serviceType");
            String control = childText(service, "controlURL");
            if (control.isBlank()) continue;
            URI resolved = base.resolve(control);
            if (!sameEndpoint(location, resolved)) continue;
            if (type.startsWith(AV_TRANSPORT)) { transport = resolved; transportType = type; }
            else if (type.startsWith(RENDERING)) { rendering = resolved; renderingType = type; }
        }
        if (transport == null) return null;
        if (name.isBlank()) name = location.getHost();
        if (id.isBlank()) id = transport.toString();
        return new Device(id, name, location, transport, transportType, rendering, renderingType);
    }

    private static boolean sameEndpoint(URI description, URI target) {
        return "http".equalsIgnoreCase(target.getScheme()) && target.getRawUserInfo() == null
            && description.getHost().equalsIgnoreCase(target.getHost());
    }

    private static byte[] fetch(URI url, String action, String serviceType, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
        try {
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(4_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (var output = connection.getOutputStream()) { output.write(payload); }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] result;
            try (stream) { result = stream == null ? new byte[0] : bounded(stream, XML_LIMIT); }
            if (code < 200 || code >= 300) throw new IOException("设备返回 HTTP " + code + soapError(result));
            return result;
        } finally { connection.disconnect(); }
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (bytes.size() + count > limit) throw new IOException("设备响应过大");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static String soapError(byte[] result) {
        try {
            String description = firstText(parse(result), "errorDescription");
            String code = firstText(parse(result), "errorCode");
            if (!description.isBlank()) return " · " + description;
            if (!code.isBlank()) return " · UPnP " + code;
        } catch (Exception ignored) {}
        return "";
    }

    private static Document parse(byte[] data) throws Exception {
        if (new String(data, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT).contains("<!DOCTYPE"))
            throw new IOException("设备 XML 包含不支持的文档类型");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        for (String feature : List.of("http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
            try { factory.setFeature(feature, false); } catch (Exception ignored) {}
        }
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("外部实体不可用"); });
        return builder.parse(new ByteArrayInputStream(data));
    }

    private static List<Element> descendants(Element root, String name) {
        List<Element> result = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) if (all.item(i) instanceof Element element && named(element, name)) result.add(element);
        return result;
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && named(element, name)) return element.getTextContent().trim();
        }
        return "";
    }

    private static boolean named(Element element, String name) {
        return name.equals(element.getLocalName()) || name.equals(element.getNodeName());
    }

    private static String firstText(Document document, String name) {
        Element root = document.getDocumentElement();
        for (Element element : descendants(root, name)) return element.getTextContent().trim();
        return "";
    }

    private static String xml(String value) {
        return String.valueOf(value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static long parseClock(String value) {
        if (value == null || value.isBlank() || value.equals("NOT_IMPLEMENTED")) return 0;
        try {
            String[] fields = value.split(":");
            if (fields.length != 3) return 0;
            return (Long.parseLong(fields[0]) * 3600 + Long.parseLong(fields[1]) * 60
                + Math.round(Double.parseDouble(fields[2]))) * 1000;
        } catch (NumberFormatException ignored) { return 0; }
    }

    @Override public void close() {
        closed = true;
        MulticastSocket channel = socket;
        if (channel != null) channel.close();
        worker.shutdownNow();
    }

    /** Blocking UPnP control client. The caller owns the playback state and executes methods on an I/O thread. */
    static final class Session {
        private final Device device;

        Session(Device device) { this.device = device; }
        Device device() { return device; }

        void setMedia(Media media) throws IOException {
            URI uri;
            try { uri = Http.url(media.url()); }
            catch (IllegalArgumentException failure) { throw new IOException("投送地址无效", failure); }
            if (uri.getRawUserInfo() != null) throw new IOException("投送地址不能包含账户密码");
            String metadata = didl(media);
            try { stop(); } catch (IOException ignored) { /* Stop can fail when the renderer is idle. */ }
            try { transport("SetAVTransportURI", "<CurrentURI>" + xml(uri.toString()) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + xml(metadata) + "</CurrentURIMetaData>"); }
            catch (IOException failure) {
                // Some older renderers reject otherwise valid DIDL metadata.
                transport("SetAVTransportURI", "<CurrentURI>" + xml(uri.toString()) + "</CurrentURI>"
                    + "<CurrentURIMetaData></CurrentURIMetaData>");
            }
        }

        private static String didl(Media media) {
            String mime = media.mime() == null || media.mime().isBlank() ? "audio/mpeg" : media.mime().trim();
            StringBuilder item = new StringBuilder("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>")
                .append(xml(media.title())).append("</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>");
            if (media.artist() != null && !media.artist().isBlank()) item.append("<dc:creator>").append(xml(media.artist())).append("</dc:creator>");
            if (media.album() != null && !media.album().isBlank()) item.append("<upnp:album>").append(xml(media.album())).append("</upnp:album>");
            if (media.artwork() != null && !media.artwork().isBlank()) item.append("<upnp:albumArtURI>").append(xml(media.artwork())).append("</upnp:albumArtURI>");
            item.append("<res protocolInfo=\"http-get:*:").append(xml(mime)).append(":*\"");
            if (media.durationMs() > 0) item.append(" duration=\"").append(clock(media.durationMs())).append("\"");
            return item.append('>').append(xml(media.url())).append("</res></item></DIDL-Lite>").toString();
        }

        void play() throws IOException { transport("Play", "<Speed>1</Speed>"); }
        void pause() throws IOException { transport("Pause", ""); }
        void stop() throws IOException { transport("Stop", ""); }
        void seek(long positionMs) throws IOException {
            transport("Seek", "<Unit>REL_TIME</Unit><Target>" + clock(positionMs) + "</Target>");
        }
        void setVolume(int percent) throws IOException {
            if (device.renderingControl() == null) throw new IOException("此设备不支持远程调节音量");
            call(device.renderingControl(), device.renderingServiceType(), "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>"
                    + Math.max(0, Math.min(100, percent)) + "</DesiredVolume>");
        }
        Position position() throws IOException {
            Document info = parseResponse(transport("GetPositionInfo", ""));
            long position = parseClock(firstText(info, "RelTime"));
            long duration = parseClock(firstText(info, "TrackDuration"));
            String state = "";
            try { state = firstText(parseResponse(transport("GetTransportInfo", "")), "CurrentTransportState"); }
            catch (IOException ignored) {}
            return new Position(position, duration, state);
        }

        private byte[] transport(String action, String arguments) throws IOException {
            return call(device.avTransport(), device.avServiceType(), action, "<InstanceID>0</InstanceID>" + arguments);
        }

        private static byte[] call(URI control, String serviceType, String action, String arguments) throws IOException {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
                + action + " xmlns:u=\"" + xml(serviceType) + "\">" + arguments + "</u:"
                + action + "></s:Body></s:Envelope>";
            return fetch(control, action, serviceType, body);
        }

        private static Document parseResponse(byte[] data) throws IOException {
            try { return parse(data); }
            catch (Exception failure) { throw new IOException("设备返回了无效的投送状态", failure); }
        }
    }
}
