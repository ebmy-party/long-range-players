package dev.codex.longrange.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PresenceConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger("long_range_players");
    private final Map<UUID, RemotePlayer> players = new HashMap<>();
    private final RelayRoster roster = new RelayRoster();
    private final RelayTransport transport;
    private ClientConfig config;
    private String rawConfig = "", configError = "", target = "";
    private RelayTransport.Status status = RelayTransport.Status.OFFLINE;
    private String detail = "未接続";
    private int ticks, configPollTicks, swingSequence, previousSwingTime;
    private boolean wasSwinging, announcementPending;
    PresenceConnection(ClientConfig config) {
        this.config = config; configError = ClientConfig.loadError();
        try { rawConfig = ClientConfig.readRaw(); } catch (IOException ignored) { }
        transport = new RelayTransport(action -> Minecraft.getInstance().execute(action), this::receive, this::setStatus, MojangIdentity::prove);
    }
    private void clearPeers() { players.clear(); roster.clear(); }
    void joined() { announcementPending = true; target = ""; transport.disconnect(); clearPeers(); }
    void worldChanged() {
        transport.disconnect(); clearPeers(); target = "";
        setStatus(RelayTransport.Status.OFFLINE, "ワールド移動に合わせて同期先を切り替えています");
    }
    void left() {
        announcementPending = false; transport.disconnect(); clearPeers(); target = "";
        setStatus(RelayTransport.Status.OFFLINE, "Minecraftサーバー未接続");
    }
    void tick(Minecraft client) {
        pollConfig();
        if (!configError.isBlank()) { offline("設定に問題があります。送信を停止しています"); announce(client); return; }
        if (ReplayRecording.isPlayback() || client.player == null || client.getCurrentServer() == null) { transport.disconnect(); clearPeers(); target = ""; return; }
        if (!config.enabled) { offline("MODは無効です。Mod Menuの設定で有効にできます"); announce(client); return; }
        if (!PrivacyPolicy.current().ready() || !ConsentRules.maySend(config, ConsentScreen.generalKey(client), null, PrivacyPolicy.current().hash())) {
            offline("外部送信への同意が必要です。/lrp privacy で確認できます"); announce(client); return;
        }
        String address = client.getCurrentServer().ip;
        if (address == null || address.isBlank()) { offline("Minecraftサーバーアドレスを取得できません"); announce(client); return; }
        String groupCode = config.groupCode(address);
        if (!ClientConfig.validCode(groupCode)) { offline("/lrp code でグループコードを設定してください"); announce(client); return; }
        var world = WorldIdentity.current();
        if (world == null) { offline("Minecraftのワールド情報を待っています"); return; }
        String serverId = RoomIdentity.hash(ClientConfig.serverKey(address));
        String nextTarget = PrivacyPolicy.current().url() + "|" + serverId + "|" + world.fingerprint() + "|" + client.player.getUUID();
        if (!nextTarget.equals(target)) { transport.disconnect(); clearPeers(); target = nextTarget; swingSequence = 0; wasSwinging = false; }
        transport.checkTimeout();
        if (!transport.isStarted() && transport.canRetry()) {
            JsonObject join = new JsonObject();
            join.addProperty("type", "hello"); join.addProperty("protocol", RelayTransport.PROTOCOL);
            join.addProperty("serverId", serverId); join.addProperty("world", world.fingerprint());
            join.addProperty("uuid", client.player.getUUID().toString());
            join.addProperty("name", client.getUser().getName());
            join.addProperty("clientNonce", SessionProof.nonce());
            join.addProperty("consent", PrivacyPolicy.REVISION); join.addProperty("policyHash", PrivacyPolicy.current().hash());
            join.addProperty("dimension", client.player.level().dimension().identifier().toString());
            join.addProperty("groupCode", groupCode);
            join.addProperty("relayUrl", ClientConfig.DEFAULT_RELAY_URL);
            transport.connect(PrivacyPolicy.current().url(), join);
        }
        var player = client.player;
        if (player.swinging && (!wasSwinging || player.swingTime < previousSwingTime)) swingSequence++;
        wasSwinging = player.swinging; previousSwingTime = player.swingTime;
        if (++ticks % 2 == 0 && status == RelayTransport.Status.ONLINE) sendPose(client);
        long now = System.currentTimeMillis(); players.values().removeIf(peer -> now - peer.receivedAt > 5_000);
        announce(client);
    }
    private void sendPose(Minecraft client) {
        transport.send(PoseDisclosure.message(client.player.isInvisible(), client.player.isSpectator(), () -> visiblePose(client)));
    }
    private JsonObject visiblePose(Minecraft client) {
        var p = client.player; JsonObject pose = new JsonObject();
        pose.addProperty("type", "pose"); pose.addProperty("dimension", p.level().dimension().identifier().toString());
        pose.addProperty("x", p.getX()); pose.addProperty("y", p.getY()); pose.addProperty("z", p.getZ());
        pose.addProperty("yaw", p.getYRot()); pose.addProperty("pitch", p.getXRot());
        pose.addProperty("bodyYaw", p.yBodyRot); pose.addProperty("headYaw", p.yHeadRot);
        pose.addProperty("stance", p.getPose().name());
        pose.addProperty("sneaking", p.isShiftKeyDown()); pose.addProperty("sprinting", p.isSprinting());
        pose.addProperty("swimming", p.isSwimming()); pose.addProperty("fallFlying", p.isFallFlying());
        pose.addProperty("onGround", p.onGround());
        pose.addProperty("usingItem", p.isUsingItem()); pose.addProperty("useHand", p.getUsedItemHand().name());
        pose.addProperty("mainArm", p.getMainArm().name()); pose.addProperty("swing", swingSequence);
        pose.addProperty("swingHand", (p.swingingArm == null ? InteractionHand.MAIN_HAND : p.swingingArm).name());
        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            equipment.addProperty(slot.name(), BuiltInRegistries.ITEM.getKey(p.getItemBySlot(slot).getItem()).toString());
        pose.add("equipment", equipment);
        int parts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) if (p.isModelPartShown(part)) parts |= part.getMask();
        pose.addProperty("modelParts", parts); return pose;
    }
    private void receive(JsonObject message) {
        roster.accept(message);
        if ("welcome".equals(message.get("type").getAsString())) {
            var self = Minecraft.getInstance().player;
            if (self != null) roster.put(self.getUUID(), self.getGameProfile().name());
        }
        switch (message.get("type").getAsString()) {
            case "welcome" -> { players.clear(); JsonArray peers = message.getAsJsonArray("peers"); if (peers != null) for (var peer : peers) putPeer(peer.getAsJsonObject()); }
            case "join" -> putPeer(message.getAsJsonObject("peer"));
            case "pose" -> putPeer(message);
            case "leave", "pose_stale" -> players.remove(UUID.fromString(message.get("uuid").getAsString()));
            case "replaced" -> clearPeers();
            default -> { }
        }
    }
    private void putPeer(JsonObject data) {
        if (data == null || !data.has("x")) return;
        // RelayDecoder already validated and normalized every field before any roster mutation.
        UUID uuid = UUID.fromString(data.get("uuid").getAsString()); var mc = Minecraft.getInstance();
        if (mc.player != null && uuid.equals(mc.player.getUUID())) return;
        String name = data.has("name") ? data.get("name").getAsString() : uuid.toString().substring(0, 8);
        if (players.containsKey(uuid) || players.size() < 64) players.put(uuid, new RemotePlayer(uuid, name, data.deepCopy(), System.currentTimeMillis()));
    }
    private void setStatus(RelayTransport.Status next, String reason) {
        if (next != status || !detail.equals(reason)) LOGGER.info("Relay {}: {}", next, reason);
        status = next; detail = reason;
        if (next == RelayTransport.Status.ERROR || next == RelayTransport.Status.OFFLINE) clearPeers();
    }
    private void offline(String reason) { transport.disconnect(); setStatus(RelayTransport.Status.OFFLINE, reason); }
    private void announce(Minecraft client) {
        if (!announcementPending || client.player == null || status == RelayTransport.Status.CONNECTING) return;
        announcementPending = false;
        String result = status == RelayTransport.Status.ONLINE ? "同期サーバーに接続しました。" : "同期サーバーに接続していません: " + detail;
        client.player.sendSystemMessage(Component.literal("[LR-Players] " + result + (configError.isBlank() ? "" : " / " + configError)));
    }
    Map<UUID, RemotePlayer> players() { return Map.copyOf(players); }
    boolean isOnline() { return status == RelayTransport.Status.ONLINE && transport.isStarted(); }
    java.util.List<RelayRoster.Member> members() { return roster.snapshot(); }
    ClientConfig config() { return config; }
    void reportConfigError() {
        configError = "設定ファイルを保存できません。ファイル権限を確認してください";
        LOGGER.warn("Config save failed");
        var client = Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[LR-Players] " + configError));
    }
    String statusText() { return "Long Range Players: " + status + " (" + detail + ")" + (configError.isBlank() ? "" : " / " + configError); }
    int statusColor() {
        if (!configError.isBlank()) return 0xFFFF5555;
        return switch (status) { case ONLINE -> 0xFF55FF55; case CONNECTING -> 0xFFFFFF55; case ERROR -> 0xFFFF5555; default -> 0xFFAAAAAA; };
    }
    void applyConfig(ClientConfig updated) {
        boolean reconnect = !config.sameConnection(updated); config = updated; configError = "";
        if (reconnect) { transport.disconnect(); clearPeers(); target = ""; LongRangePlayersClient.clearWorldDisplays(); setStatus(RelayTransport.Status.OFFLINE, "接続設定を変更しました"); }
        try { rawConfig = ClientConfig.readRaw(); } catch (IOException ignored) { }
    }
    private void pollConfig() {
        if (++configPollTicks < 20) return; configPollTicks = 0;
        try {
            String latest = ClientConfig.readRaw(); if (latest.equals(rawConfig) && configError.isBlank()) return;
            ClientConfig updated = ClientConfig.parse(latest);
            if (!config.sameValues(updated)) applyConfig(updated);
            rawConfig = latest; configError = "";
        } catch (IOException | RuntimeException error) {
            if (configError.isBlank()) LOGGER.warn("Config read failed ({})", error.getClass().getSimpleName());
            configError = "設定ファイルを読めません。JSON形式・ファイル権限を確認してください";
        }
    }
    record RemotePlayer(UUID uuid, String name, JsonObject data, long receivedAt) {
        String dimension() { return text("dimension", ""); }
        double x() { return number("x", 0); } double y() { return number("y", 0); } double z() { return number("z", 0); }
        String text(String key, String fallback) { return data.has(key) ? data.get(key).getAsString() : fallback; }
        double number(String key, double fallback) { return data.has(key) ? data.get(key).getAsDouble() : fallback; }
        boolean flag(String key) { return data.has(key) && data.get(key).getAsBoolean(); }
    }
}
