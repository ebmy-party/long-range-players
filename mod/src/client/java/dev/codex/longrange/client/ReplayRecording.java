package dev.codex.longrange.client;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional ReplayMod 26.2-2.6.27 adapter. Packets go ONLY into the recorder, never the live world or network. */
public final class ReplayRecording {
    private static final Logger LOG = LoggerFactory.getLogger("long-range-players");
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final ReplayEntityTracker tracked = new ReplayEntityTracker();
    private static final ReplayProfiles profiles = new ReplayProfiles();
    private static final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private static boolean initialized, unavailable, playback;
    private static volatile boolean boundarySeen;
    private static Object recordingModule, replayModule, recorder;
    private static Method connectionHandler, packetListener, replayHandler, save;
    private static ClientLevel level;
    private static int ticks;
    private static RecordingBudget budget = new RecordingBudget(System::nanoTime);
    private static boolean recordingLimited;
    private ReplayRecording() { }

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        if (!FabricLoader.getInstance().isModLoaded("replaymod")) { unavailable = true; return; }
        try {
            Class<?> recording = Class.forName("com.replaymod.recording.ReplayModRecording");
            Class<?> replay = Class.forName("com.replaymod.replay.ReplayModReplay");
            recordingModule = recording.getField("instance").get(null);
            replayModule = replay.getField("instance").get(null);
            connectionHandler = recording.getMethod("getConnectionEventHandler");
            packetListener = connectionHandler.getReturnType().getMethod("getPacketListener");
            save = packetListener.getReturnType().getMethod("save", Packet.class);
            replayHandler = replay.getMethod("getReplayHandler");
        } catch (ReflectiveOperationException | LinkageError error) { fail(error); }
    }
    private static void fail(Throwable error) {
        unavailable = true;
        reset();
        // Do not include arbitrary exception text or any relay configuration in logs.
        LOG.warn("ReplayMod連携を停止しました（{}）。Long Range Playersの通常表示は継続します。", error.getClass().getSimpleName());
    }
    public static void updatePlayback() {
        initialize();
        if (replayHandler == null || replayModule == null) return;
        try { playback = replayHandler.invoke(replayModule) != null; }
        catch (ReflectiveOperationException error) { if (!unavailable) fail(error); }
    }
    public static boolean isPlayback() { return playback; }
    public static boolean isRecordedAvatar(Entity entity) {
        return playback && entity instanceof RemotePlayer && entity.getId() <= -1_000_000_000;
    }
    private static void reset() {
        recorder = null; level = null; tracked.clear(); profiles.clear(); snapshots.clear(); ticks = 0;
        budget = new RecordingBudget(System::nanoTime); recordingLimited = false;
    }
    private static void write(Packet<?> packet) {
        if (recorder == null || unavailable) return;
        if (recordingLimited) return;
        if (!budget.packet()) { limitRecording(); return; }
        writeRaw(packet);
    }
    private static void writeRaw(Packet<?> packet) {
        if (recorder == null || unavailable) return;
        try { save.invoke(recorder, packet); }
        catch (ReflectiveOperationException error) { fail(error); }
    }
    private static void limitRecording() {
        if (!recordingLimited) LOG.warn("ReplayMod連携: 遠距離プレイヤーの記録量上限に達しました。この録画への追加を停止します。通常のReplayMod録画は継続します。");
        recordingLimited = true;
    }
    private static void cleanLimitedRecording() {
        // Bounded cleanup (at most 64 entities + 64 profiles), even after the recording budget is exhausted.
        tracked.removeAll(id -> writeRaw(new ClientboundRemoveEntitiesPacket(id)));
        profiles.retain(java.util.Set.of(), uuid -> writeRaw(new ClientboundPlayerInfoRemovePacket(List.of(uuid))));
        snapshots.clear();
    }
    private static void writeRemoval(Packet<?> packet) {
        if (!recordingLimited && !budget.packet()) limitRecording();
        // Removing an owned entry must reach the file even if this removal hits the limit.
        writeRaw(packet);
    }
    private static void remove(int id) { writeRemoval(new ClientboundRemoveEntitiesPacket(id)); }

    static void tick(Minecraft client) {
        if (unavailable) return;
        if (playback || client.level == null || client.getConnection() == null) { reset(); return; }
        try {
            Object current = packetListener.invoke(connectionHandler.invoke(recordingModule));
            if (current == null) { reset(); return; }
            // Do not record if an incompatible ReplayMod no longer invokes our ordering hook.
            if (!boundarySeen) {
                if (!RemoteAvatarRenderer.recordingAvatars().isEmpty()) {
                    unavailable = true;
                    LOG.warn("ReplayMod連携を停止しました（録画パケットの順序フックが見つかりません）。ReplayModの版を確認してください。");
                }
                return;
            }
            if (current != recorder) {
                reset(); recorder = current;
                LOG.info("ReplayMod連携: 遠距離プレイヤーの録画を開始しました");
            }
            if (recordingLimited) { cleanLimitedRecording(); return; }
            if (level != client.level) {
                tracked.removeAll(ReplayRecording::remove);
                profiles.retain(java.util.Set.of(), uuid -> writeRemoval(new ClientboundPlayerInfoRemovePacket(List.of(uuid))));
                snapshots.clear(); level = client.level;
            }
            Map<UUID, GhostPlayer> visible = RemoteAvatarRenderer.recordingAvatars();
            tracked.retain(visible.keySet(), ReplayRecording::remove);
            profiles.retain(visible.keySet(), uuid -> writeRemoval(new ClientboundPlayerInfoRemovePacket(List.of(uuid))));
            snapshots.keySet().retainAll(visible.keySet());
            boolean movementTick = ++ticks % 2 == 0;
            for (var entry : visible.entrySet()) {
                if (recordingLimited) break;
                UUID uuid = entry.getKey();
                GhostPlayer avatar = entry.getValue();
                boolean spawn = !tracked.contains(uuid, avatar.getId());
                if (spawn) {
                    if (!budget.spawn()) { limitRecording(); break; }
                    tracked.remove(uuid, ReplayRecording::remove);
                    // Normal tab-list profiles (including signed skin properties) are already recorded.
                    // A relay-only identity needs its own unlisted vanilla profile before spawning.
                    if (client.getConnection().getPlayerInfo(uuid) == null) {
                        write(profilePacket(client.level.registryAccess(), avatar.getGameProfile()));
                        profiles.addedSynthetic(uuid);
                    }
                    write(new ClientboundAddEntityPacket(avatar.getId(), uuid, avatar.getX(), avatar.getY(), avatar.getZ(),
                            avatar.getXRot(), avatar.getYRot(), avatar.getType(), 0, avatar.getDeltaMovement(), avatar.getYHeadRot()));
                    tracked.add(uuid, avatar.getId());
                    snapshots.put(uuid, new Snapshot(avatar.swingSequence()));
                }
                Snapshot previous = snapshots.get(uuid);
                var equipment = new ArrayList<Pair<EquipmentSlot, ItemStack>>();
                for (EquipmentSlot slot : SLOTS) {
                    ItemStack item = avatar.getItemBySlot(slot);
                    ItemStack old = previous.equipment.get(slot);
                    if (old == null || !ItemStack.matches(item, old)) {
                        equipment.add(Pair.of(slot, item.copy()));
                        previous.equipment.put(slot, item.copy());
                    }
                }
                boolean resetUse = !spawn && previous.using && (!equipment.isEmpty() || previous.hand != avatar.getUsedItemHand());
                if (resetUse) write(new ClientboundSetEntityDataPacket(avatar.getId(), List.of(avatar.recordingUseFlags(true))));
                if (!equipment.isEmpty()) write(new ClientboundSetEquipmentPacket(avatar.getId(), equipment));
                var dirty = avatar.getEntityData().packDirty();
                var data = spawn ? avatar.getEntityData().getNonDefaultValues() : dirty;
                if (data != null && !data.isEmpty()) write(new ClientboundSetEntityDataPacket(avatar.getId(), data));
                if (resetUse) write(new ClientboundSetEntityDataPacket(avatar.getId(), List.of(avatar.recordingUseFlags(false))));
                previous.using = avatar.isUsingItem();
                previous.hand = avatar.getUsedItemHand();
                if (spawn || movementTick) {
                    write(ClientboundEntityPositionSyncPacket.of(avatar));
                    write(new ClientboundSetEntityMotionPacket(avatar));
                    write(new ClientboundRotateHeadPacket(avatar, Mth.packDegrees(avatar.getYHeadRot())));
                }
                if ((spawn && avatar.swinging) || previous.swing != avatar.swingSequence()) {
                    write(new ClientboundAnimatePacket(avatar, avatar.swingingArm == InteractionHand.OFF_HAND
                            ? ClientboundAnimatePacket.SWING_OFF_HAND : ClientboundAnimatePacket.SWING_MAIN_HAND));
                    previous.swing = avatar.swingSequence();
                }
                if (unavailable) return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
    }

    static ClientboundPlayerInfoUpdatePacket profilePacket(net.minecraft.core.RegistryAccess registries, com.mojang.authlib.GameProfile profile) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT), ClientboundPlayerInfoUpdatePacket.Action.class);
            buffer.writeVarInt(1);
            buffer.writeUUID(profile.id());
            ByteBufCodecs.PLAYER_NAME.encode(buffer, profile.name());
            ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buffer, profile.properties());
            buffer.writeBoolean(true);
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }

    /** Runs before ReplayMod queues the incoming packet. Use its SAME packet queue, preserving recording order. */
    public static void beforeInbound(ChannelHandlerContext context, Object packet) {
        boundarySeen = true;
        UUID realPlayer = packet instanceof ClientboundAddEntityPacket add ? add.getUUID() : null;
        boolean respawn = packet instanceof ClientboundRespawnPacket;
        boolean resetWorld = packet instanceof ClientboundLoginPacket || packet instanceof ClientboundStartConfigurationPacket;
        var newProfiles = packet instanceof ClientboundPlayerInfoUpdatePacket info ? info.newEntries() : List.<ClientboundPlayerInfoUpdatePacket.Entry>of();
        var removedProfiles = packet instanceof ClientboundPlayerInfoRemovePacket info ? info.profileIds() : List.<UUID>of();
        if (realPlayer == null && !respawn && !resetWorld && newProfiles.isEmpty() && removedProfiles.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        Connection connection = context.pipeline().get(Connection.class);
        if (connection == null || connection.getPacketListener() == null) return;
        Runnable action = () -> {
            if (recorder == null || unavailable) return;
            if (client.getConnection() == null || client.getConnection().getConnection() != connection) return;
            if (recordingLimited) { cleanLimitedRecording(); return; }
            if (resetWorld || respawn) {
                tracked.removeAll(ReplayRecording::remove);
                profiles.retain(java.util.Set.of(), uuid -> writeRemoval(new ClientboundPlayerInfoRemovePacket(List.of(uuid))));
                snapshots.clear(); level = null;
            }
            else if (realPlayer != null) { tracked.remove(realPlayer, ReplayRecording::remove); snapshots.remove(realPlayer); }
            for (var entry : newProfiles) {
                profiles.beforeServerAdd(entry.profileId(), tracked, ReplayRecording::remove,
                        uuid -> writeRemoval(new ClientboundPlayerInfoRemovePacket(List.of(uuid))));
                snapshots.remove(entry.profileId());
            }
            for (UUID uuid : removedProfiles) profiles.removed(uuid);
        };
        if (client.isSameThread()) action.run();
        else client.packetProcessor().scheduleIfPossible(connection.getPacketListener(), new Packet<PacketListener>() {
            @Override public PacketType<? extends Packet<PacketListener>> type() { return null; }
            @Override public void handle(PacketListener listener) { action.run(); }
        });
    }

    /** Repair older recordings when a signed skin profile arrives after our texture-less placeholder. */
    public static void beforePlaybackProfiles(net.minecraft.client.multiplayer.ClientPacketListener handler,
                                              ClientboundPlayerInfoUpdatePacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) return; // Vanilla reschedules this packet onto the game thread.
        updatePlayback();
        if (!playback) return;
        for (var entry : packet.newEntries()) {
            var previous = handler.getPlayerInfo(entry.profileId());
            if (previous == null || !ReplayProfiles.upgradesMissingSkin(previous.getProfile(), entry.profile())) continue;
            handler.handlePlayerInfoRemove(new ClientboundPlayerInfoRemovePacket(List.of(entry.profileId())));
            if (client.level != null) for (var player : client.level.players()) {
                if (player instanceof RemotePlayer && player.getUUID().equals(entry.profileId()))
                    ((dev.codex.longrange.mixin.PlayerInfoAccessor) player).longRangePlayers$setPlayerInfo(null);
            }
        }
    }
    private static final class Snapshot {
        final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        int swing;
        boolean using;
        InteractionHand hand;
        Snapshot(int swing) { this.swing = swing; }
    }
}
