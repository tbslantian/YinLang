package app.soundshelf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

record PluginArchive(String name, String version, String scripts) {
    static PluginArchive read(byte[] bytes) throws Exception {
        Map<String, String> contents = new LinkedHashMap<>();
        int total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 512) throw new IllegalArgumentException("插件包文件过多");
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("/") || path.contains("../") || path.contains("/__MACOSX/") || path.startsWith("__MACOSX/")) continue;
                if (!path.endsWith(".js") && !path.endsWith("manifest.json")) continue;
                byte[] data = Http.bytes(zip, 3_000_000 - total);
                total += data.length;
                contents.put(path, new String(data, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", ""));
            }
        }
        String manifestPath = contents.keySet().stream()
            .filter(name -> name.equals("manifest.json") || name.endsWith("/manifest.json"))
            .min(java.util.Comparator.comparingInt(String::length)).orElse(null);
        JSONObject manifest = manifestPath == null ? new JSONObject() : new JSONObject(contents.get(manifestPath));
        String prefix = manifestPath == null ? "" : manifestPath.substring(0, manifestPath.length() - "manifest.json".length());
        JSONArray entriesInOrder = manifest.optJSONArray("scripts");
        var script = new StringBuilder();
        if (entriesInOrder != null) {
            for (int i = 0; i < entriesInOrder.length(); i++) {
                String name = prefix + entriesInOrder.getString(i);
                if (!name.endsWith(".js") || !contents.containsKey(name)) throw new IllegalArgumentException("插件缺少脚本 " + name);
                script.append(contents.get(name)).append("\n;\n");
            }
        } else {
            for (String name : contents.keySet().stream().filter(item -> item.endsWith(".js")).sorted().collect(java.util.stream.Collectors.toList())) {
                script.append(contents.get(name)).append("\n;\n");
            }
        }
        if (script.length() == 0) throw new IllegalArgumentException("ZIP 中没有插件脚本");
        return new PluginArchive(manifest.optString("name", "听书插件"), manifest.optString("version", ""), script.toString());
    }
}
