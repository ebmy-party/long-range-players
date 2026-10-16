package dev.codex.longrange.client;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

final class GhostPlayer extends RemotePlayer {
    // Separate from normal server IDs and ReplayMod's camera ID (-1789435).
    private static final AtomicInteger IDS = new AtomicInteger(-1_000_000_000);
    private final EnumMap<EquipmentSlot, String> equipment = new EnumMap<>(EquipmentSlot.class);
    private int lastSwing;
    private int modelParts = 127;
    private HumanoidArm mainArm = HumanoidArm.RIGHT;

    GhostPlayer(ClientLevel level, GameProfile profile, PresenceConnection.RemotePlayer peer) {
        super(level, profile);
        setId(IDS.getAndDecrement());
        setPos(peer.x(), peer.y(), peer.z()); setOldPosAndRot();
        lastSwing = (int) peer.number("swing", 0);
    }
    @Override protected void pushEntities() { /* This avatar has no physical presence. */ }
    @Override public void aiStep() { updateSwingTime(); updateBob(); }
    @Override public HumanoidArm getMainArm() { return mainArm == null ? HumanoidArm.RIGHT : mainArm; }
    @Override public boolean isModelPartShown(PlayerModelPart part) { return (modelParts & part.getMask()) != 0; }
    int swingSequence() { return lastSwing; }
    net.minecraft.network.syncher.SynchedEntityData.DataValue<Byte> recordingUseFlags(boolean stop) {
        byte flags = getEntityData().get(DATA_LIVING_ENTITY_FLAGS);
        return net.minecraft.network.syncher.SynchedEntityData.DataValue.create(DATA_LIVING_ENTITY_FLAGS,
                stop ? (byte) (flags & ~3) : flags);
    }

    void update(PresenceConnection.RemotePlayer peer) {
        // The regular player tick also gives installed animation libraries their normal lifecycle hook.
        super.tick(); setOldPosAndRot();
        double oldX = getX(), oldY = getY(), oldZ = getZ();
        boolean teleport = distanceToSqr(peer.x(), peer.y(), peer.z()) > 64 * 64;
        double amount = teleport ? 1 : 0.5;
        setPos(Mth.lerp(amount, oldX, peer.x()), Mth.lerp(amount, oldY, peer.y()), Mth.lerp(amount, oldZ, peer.z()));
        if (teleport) setOldPosAndRot();
        setDeltaMovement(getX() - oldX, getY() - oldY, getZ() - oldZ);
        setYRot(Mth.rotLerp(0.5F, getYRot(), (float) peer.number("yaw", 0)));
        setXRot((float) peer.number("pitch", 0));
        yBodyRotO = yBodyRot; yHeadRotO = yHeadRot;
        yBodyRot = Mth.rotLerp(0.5F, yBodyRot, (float) peer.number("bodyYaw", getYRot()));
        yHeadRot = Mth.rotLerp(0.5F, yHeadRot, (float) peer.number("headYaw", getYRot()));
        setShiftKeyDown(peer.flag("sneaking")); setSprinting(peer.flag("sprinting")); setSwimming(peer.flag("swimming"));
        setOnGround(peer.flag("onGround")); setInvisible(peer.flag("invisible"));
        if (peer.flag("fallFlying")) startFallFlying(); else stopFallFlying();
        try { setPose(Pose.valueOf(peer.text("stance", "STANDING"))); } catch (IllegalArgumentException ignored) { setPose(Pose.STANDING); }
        mainArm = "LEFT".equals(peer.text("mainArm", "RIGHT")) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        modelParts = (int) peer.number("modelParts", 127);
        setMainArm(mainArm);
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) modelParts);
        JsonObject incoming = peer.data().has("equipment") ? peer.data().getAsJsonObject("equipment") : new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            String id = incoming.has(slot.name()) ? incoming.get(slot.name()).getAsString() : "minecraft:air";
            if (!id.equals(equipment.get(slot))) {
                var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                setItemSlot(slot, item == null ? ItemStack.EMPTY : new ItemStack(item)); equipment.put(slot, id);
            }
        }
        InteractionHand useHand = "OFF_HAND".equals(peer.text("useHand", "MAIN_HAND")) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        syncItemUse(peer.flag("usingItem"), useHand);
        int swing = (int) peer.number("swing", 0);
        if (swing != lastSwing) { swing("OFF_HAND".equals(peer.text("swingHand", "MAIN_HAND")) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND); lastSwing = swing; }
        calculateEntityAnimation(false);
    }

    private void syncItemUse(boolean using, InteractionHand hand) {
        ItemStack held = getItemInHand(hand);
        if (!using || held.isEmpty()) {
            setLivingEntityFlag(1, false);
            setLivingEntityFlag(2, false);
            useItem = ItemStack.EMPTY;
            useItemRemaining = 0;
            return;
        }
        if (!isUsingItem() || getUsedItemHand() != hand || useItem != held) {
            // LivingEntity.startUsingItem/stopUsingItem only change these flags on a server.
            // This render-only player never receives vanilla entity-data packets, so mirror
            // them here. Set the hand first; onSyncedDataUpdated reads it when use begins.
            setLivingEntityFlag(1, false);
            setLivingEntityFlag(2, hand == InteractionHand.OFF_HAND);
            useItem = held; // Item models test reference identity against the actual held stack.
            useItemRemaining = Math.max(1, held.getUseDuration(this));
            setLivingEntityFlag(1, true);
        } else {
            // Keep a continuous animation between relay updates; do not restart every tick.
            useItemRemaining = Math.max(1, useItemRemaining);
        }
    }
}
