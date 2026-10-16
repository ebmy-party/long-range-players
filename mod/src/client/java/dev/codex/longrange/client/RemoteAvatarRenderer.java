package dev.codex.longrange.client;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/** Render-only players: never inserted into the world's entity list, UUID lookup, or network handler. */
public final class RemoteAvatarRenderer {
    private static PresenceConnection connection;
    private static ClientLevel level;
    private static final Map<UUID, GhostPlayer> avatars = new HashMap<>();
    private static volatile Map<UUID, GhostPlayer> emoteAvatars = Map.of();
    private RemoteAvatarRenderer() { }
    static void initialize(PresenceConnection presence) { connection = presence; }
    public static void clear() { avatars.clear(); emoteAvatars = Map.of(); level = null; }
    public static net.minecraft.world.entity.Avatar emoteAvatar(UUID uuid) { return emoteAvatars.get(uuid); }
    static Map<UUID, GhostPlayer> recordingAvatars() { return Map.copyOf(avatars); }
    static boolean eligible(Minecraft client, PresenceConnection.RemotePlayer peer) {
        if (client.player == null || client.level == null || peer.uuid().equals(client.player.getUUID())) return false;
        boolean tracked = client.level.players().stream().anyMatch(player -> player.getUUID().equals(peer.uuid()));
        return RemoteVisibility.show(tracked, peer.dimension().equals(client.level.dimension().identifier().toString()),
                peer.flag("invisible"), System.currentTimeMillis() - peer.receivedAt(),
                client.player.getX(), client.player.getZ(), peer.x(), peer.z(), connection.config().minDistanceChunks);
    }
    static void tick(Minecraft client) {
        if (connection == null || client.level == null || client.player == null || !connection.config().enabled) { clear(); return; }
        if (level != client.level) { clear(); level = client.level; }
        HashSet<UUID> visible = new HashSet<>();
        for (var peer : connection.players().values()) {
            if (!eligible(client, peer)) continue;
            visible.add(peer.uuid());
            GhostPlayer avatar = avatars.computeIfAbsent(peer.uuid(), uuid -> {
                var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(uuid);
                GameProfile profile = info == null ? new GameProfile(uuid, peer.name()) : info.getProfile();
                return new GhostPlayer(client.level, profile, peer);
            });
            avatar.update(peer);
        }
        avatars.keySet().retainAll(visible);
        emoteAvatars = Map.copyOf(avatars);
    }
    public static void extract(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState output) {
        Minecraft client = Minecraft.getInstance();
        if (connection == null || client.level != level || client.player == null) return;
        var peers = connection.players();
        for (var entry : avatars.entrySet()) {
            var peer = peers.get(entry.getKey());
            GhostPlayer avatar = entry.getValue();
            // Recheck after vanilla packet handling; never draw over a newly tracked real player.
            if (peer == null || !eligible(client, peer) || !frustum.isVisible(avatar.getBoundingBox().inflate(0.5))) continue;
            var state = client.getEntityRenderDispatcher().extractEntity(avatar, delta.getGameTimeDeltaPartialTick(true));
            if (!client.level.hasChunkAt(avatar.blockPosition())) state.lightCoords = 0x00F000F0;
            output.entityRenderStates.add(state);
        }
        output.lastEntityRenderStateCount = output.entityRenderStates.size();
    }
    public static void close() { clear(); }
}
