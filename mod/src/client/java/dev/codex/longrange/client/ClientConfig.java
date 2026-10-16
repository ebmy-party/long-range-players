package dev.codex.longrange.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("long-range-players.json"); }

    boolean enabled = false;
    static final String DEFAULT_RELAY_URL = "wss://lrp.ebmy.party/ws";
    String relayUrl = DEFAULT_RELAY_URL;
    String relayOperator = "yukugura";
    String relayContact = "配布元のDiscordスレッド";
    int relayRetentionDays = 7;
    Map<String, String> consents = new HashMap<>();
    Map<String, String> groupCodes = new HashMap<>();
    String groupCode(String address) { return groupCodes.getOrDefault(codeKey(relayUrl, address), ""); }
    static String codeKey(String url, String address) { return RoomIdentity.hash(PrivacyPolicy.normalizeUrl(url) + "\n" + serverKey(address)); }
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
            if (old.has("token") || old.has("roomKey") || old.has("roomKeys")) config.save();
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
        config.relayUrl = PrivacyPolicy.normalizeUrl(config.relayUrl);
        var fields = com.google.gson.JsonParser.parseString(contents).getAsJsonObject();
        if (!DEFAULT_RELAY_URL.equals(config.relayUrl)) {
            if (!fields.has("relayOperator")) config.relayOperator = "";
            if (!fields.has("relayContact")) config.relayContact = "";
        }
        if (config.relayOperator == null) config.relayOperator = "";
        if (config.relayContact == null) config.relayContact = "";
        config.relayOperator = config.relayOperator.trim();
        config.relayContact = config.relayContact.trim();
        if (config.relayOperator.length() > 100 || config.relayContact.length() > 512
                || config.relayOperator.chars().anyMatch(Character::isISOControl)
                || config.relayContact.chars().anyMatch(Character::isISOControl)
                || config.relayRetentionDays < 1 || config.relayRetentionDays > 30)
            throw new IllegalArgumentException("Invalid relay privacy settings");
        if (config.privacyDecision == null) config.privacyDecision = "";
        if (!config.privacyDecision.matches("(?:|(?:declined:)?[a-f0-9]{64})")) throw new IllegalArgumentException("Invalid privacy decision");
        if (config.consents == null) config.consents = new HashMap<>();
        if (config.consents.size() > 64) throw new IllegalArgumentException("Too many consent records");
        for (var entry : config.consents.entrySet())
            if (!entry.getKey().matches("[a-f0-9]{64}") || entry.getValue() == null
                    || !entry.getValue().matches("(?:declined:)?[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid consent record");
        if (config.groupCodes == null) config.groupCodes = new HashMap<>();
        if (config.groupCodes.size() > 64) throw new IllegalArgumentException("Too many group codes");
        for (var entry : config.groupCodes.entrySet())
            if (!entry.getKey().matches("[a-f0-9]{64}") || !validCode(entry.getValue()))
                throw new IllegalArgumentException("Invalid group code");
        config.minDistanceChunks = Math.max(0, Math.min(64, config.minDistanceChunks));
        return config;
    }

    boolean sameValues(ClientConfig other) {
        return sameConnection(other) && debug == other.debug
                && minDistanceChunks == other.minDistanceChunks && journeyMap == other.journeyMap;
    }

    boolean sameConnection(ClientConfig other) {
        return other != null && enabled == other.enabled
                && relayUrl.equals(other.relayUrl)
                && relayOperator.equals(other.relayOperator) && relayContact.equals(other.relayContact)
                && relayRetentionDays == other.relayRetentionDays
                && privacyDecision.equals(other.privacyDecision)
                && consents.equals(other.consents) && groupCodes.equals(other.groupCodes);
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
