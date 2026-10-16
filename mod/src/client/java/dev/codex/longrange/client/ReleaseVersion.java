package dev.codex.longrange.client;

import com.google.gson.JsonParser;

final class ReleaseVersion {
    private static int[] parts(String version) {
        if (version == null || !version.matches("v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:\\+mc\\.26\\.2)?")) return null;
        String base = version.replaceFirst("^v", "").split("\\+", 2)[0];
        String[] values = base.split("\\.");
        return new int[] {Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2])};
    }
    static boolean newer(String latest, String installed) {
        int[] a = parts(latest), b = parts(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }
    static String available(String body, String installed) {
        if (body.length() > 65536) return null;
        try {
            RelayDecoder.boundedJson(body);
            var data = JsonParser.parseString(body).getAsJsonObject();
            if (data.get("draft").getAsBoolean() || data.get("prerelease").getAsBoolean()) return null;
            String tag = data.get("tag_name").getAsString();
            if (!newer(tag, installed)) return null;
            String version = tag.replaceFirst("^v", "");
            var assets = data.getAsJsonArray("assets");
            if (assets == null || assets.size() > 64) return null;
            for (var asset : assets) if (("long-range-players-" + version + "+mc.26.2.jar").equals(asset.getAsJsonObject().get("name").getAsString())) return version;
        } catch (RuntimeException ignored) { }
        return null;
    }
}
