package dev.codex.longrange.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Locale;

final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("long-range-players.json"); }

    boolean enabled = true;
    static final String DEFAULT_RELAY_URL = "wss://lrp.ebmy.party/ws";
    String groupCode = "";
    String groupCode(String address) { return groupCode; }
    static boolean validCode(String code) { return code != null && code.matches("(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,64}"); }
    String privacyDecision = "";
    boolean debug = false;
    int minDistanceChunks = 2;
    boolean journeyMap = true;
    private static String lastLoadError = "";

    static String loadError() { return lastLoadError; }
    ClientConfig copy() { return parse(GSON.toJson(this)); }

    static ClientConfig load() {
        if (!Files.exists(path())) {
            ClientConfig config = new ClientConfig();
            try { config.save(); } catch (RuntimeException error) { lastLoadError = "設定ファイルを保存できません"; }
            return config;
        }
        try {
            String raw = readRaw();
            ClientConfig config = parse(raw);
            // Migrate away from plaintext credentials and global room overrides.
            var old = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (old.has("token") || old.has("roomKey") || old.has("roomKeys") || old.has("relayUrl") || old.has("groupCodes") || old.has("consents")) config.save();
            return config;
        } catch (IOException | RuntimeException ignored) {
            lastLoadError = "設定ファイルを読めません";
            ClientConfig fallback = new ClientConfig();
            fallback.enabled = false; // A broken existing configuration must not silently opt its owner in.
            return fallback;
        }
    }

    static String readRaw() throws IOException { return readBounded(path()); }
    static String readBounded(Path file) throws IOException {
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("Config exceeds 64 KiB");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static String serverKey(String address) { return address.trim().toLowerCase(Locale.ROOT); }

    static ClientConfig parse(String contents) {
        if (contents.length() > 65_536) throw new IllegalArgumentException("Config exceeds limit");
        RelayDecoder.boundedJson(contents);
        ClientConfig config = GSON.fromJson(contents, ClientConfig.class);
        if (config == null) throw new IllegalArgumentException("Config must contain a JSON object");
        var fields = com.google.gson.JsonParser.parseString(contents).getAsJsonObject();
        if (config.groupCode == null) config.groupCode = "";
        // Preserve a single old code; ambiguous old server-specific codes require an explicit choice.
        if (!fields.has("groupCode") && fields.has("groupCodes") && fields.get("groupCodes").isJsonObject()) {
            var unique = new java.util.HashSet<String>();
            for (var entry : fields.getAsJsonObject("groupCodes").entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString())
                    unique.add(entry.getValue().getAsString());
            }
            if (unique.size() == 1 && validCode(unique.iterator().next())) config.groupCode = unique.iterator().next();
        }
        if (!config.groupCode.isEmpty() && !validCode(config.groupCode)) throw new IllegalArgumentException("Invalid group code");
        if (config.privacyDecision == null) config.privacyDecision = "";
        if (!config.privacyDecision.matches("(?:|(?:declined:)?[a-f0-9]{64})")) throw new IllegalArgumentException("Invalid privacy decision");
        config.minDistanceChunks = Math.max(0, Math.min(64, config.minDistanceChunks));
        return config;
    }

    boolean sameValues(ClientConfig other) {
        return sameConnection(other) && debug == other.debug
                && minDistanceChunks == other.minDistanceChunks && journeyMap == other.journeyMap;
    }

    boolean sameConnection(ClientConfig other) {
        return other != null && enabled == other.enabled
                && privacyDecision.equals(other.privacyDecision)
                && groupCode.equals(other.groupCode);
    }

    void save() {
        try {
            Files.createDirectories(path().getParent());
            String text = GSON.toJson(this);
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) throw new IOException("Config exceeds limit");
            PrivateFiles.write(path(), text);
        } catch (IOException error) {
            throw new IllegalStateException("設定ファイルを保存できません。ファイル権限を確認してください");
        }
    }
}
