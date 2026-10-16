package dev.codex.longrange.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/** Vanilla packet serialization only; no game window, world, relay or ReplayMod playback. */
public final class ReplayPacketChecks {
    private static int skinChecks;
    private static void checkSkin(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        skinChecks++;
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "ReplayCheck");
        var packet = ReplayRecording.profilePacket(registries, profile);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buffer, packet);
            var info = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer).entries().getFirst();
            if (!profile.equals(info.profile()) || info.listed() || !info.showHat() || buffer.isReadable())
                throw new AssertionError("Replay profile round trip");
            buffer.clear();
            var spawn = new ClientboundAddEntityPacket(-1_000_000_000, profile.id(), 1024.5, 80, -512,
                    30, 90, EntityTypes.PLAYER, 0, Vec3.ZERO, 90);
            ClientboundAddEntityPacket.STREAM_CODEC.encode(buffer, spawn);
            var decoded = ClientboundAddEntityPacket.STREAM_CODEC.decode(buffer);
            if (decoded.getId() != spawn.getId() || !decoded.getUUID().equals(profile.id())
                    || decoded.getType() != EntityTypes.PLAYER || decoded.getX() != 1024.5 || buffer.isReadable())
                throw new AssertionError("Replay player spawn round trip");
            buffer.clear();
            ClientboundRemoveEntitiesPacket.STREAM_CODEC.encode(buffer, new ClientboundRemoveEntitiesPacket(spawn.getId()));
            var remove = ClientboundRemoveEntitiesPacket.STREAM_CODEC.decode(buffer);
            if (remove.getEntityIds().size() != 1 || remove.getEntityIds().getInt(0) != spawn.getId() || buffer.isReadable())
                throw new AssertionError("Replay player despawn round trip");
            skinChecks(registries, buffer, profile);
            System.out.println("Replay packet checks passed: profile, negative entity ID/spawn, removal; no rendering exercised.");
        } finally { buffer.release(); }
    }

    private static void skinChecks(RegistryAccess registries, RegistryFriendlyByteBuf buffer, GameProfile empty) {
        // Synthetic fixture: verifies byte-for-byte preservation, not Mojang signature validation or a skin download.
        Property texture = new Property("textures", "test-only-texture-payload", "test-only-signature");
        GameProfile skin = new GameProfile(empty.id(), empty.name(), new PropertyMap(ImmutableMultimap.of("textures", texture)));
        buffer.clear();
        ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buffer, ReplayRecording.profilePacket(registries, skin));
        var decoded = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer).newEntries().getFirst().profile();
        checkSkin(decoded.properties().get("textures").iterator().next().equals(texture) && !buffer.isReadable(),
                "replay retains exact texture property and signature");
        checkSkin(ReplayProfiles.upgradesMissingSkin(empty, decoded), "late skin upgrades texture-less replay profile");
        checkSkin(!ReplayProfiles.upgradesMissingSkin(skin, empty), "empty update cannot downgrade known skin");
        checkSkin(!ReplayProfiles.upgradesMissingSkin(skin, skin), "existing skin does not require replacement");
        checkSkin(!ReplayProfiles.upgradesMissingSkin(null, skin) && !ReplayProfiles.upgradesMissingSkin(empty, null), "absent profiles handled without error");
        checkSkin(!ReplayProfiles.upgradesMissingSkin(new GameProfile(UUID.randomUUID(), "Other"), skin), "skin upgrade cannot cross UUIDs");

        var replayTable = new HashMap<UUID, GameProfile>();
        replayTable.put(empty.id(), empty);
        replayTable.putIfAbsent(skin.id(), skin); // The actual Minecraft ADD_PLAYER behavior.
        checkSkin(replayTable.get(empty.id()).properties().get("textures").isEmpty(), "reproduces old late-join default skin bug");
        ReplayProfiles profiles = new ReplayProfiles();
        ReplayEntityTracker entities = new ReplayEntityTracker();
        profiles.addedSynthetic(empty.id());
        entities.add(empty.id(), -1_000_000_000);
        List<String> events = new ArrayList<>();
        profiles.beforeServerAdd(skin.id(), entities, id -> events.add("despawn"), uuid -> {
            events.add("remove-profile"); replayTable.remove(uuid);
        });
        replayTable.putIfAbsent(skin.id(), decoded); events.add("server-profile");
        checkSkin(events.equals(List.of("despawn", "remove-profile", "server-profile"))
                        && replayTable.get(skin.id()).properties().get("textures").contains(texture) && !entities.has(skin.id()),
                "late join removes cached ghost and placeholder before signed server profile");
        profiles.beforeServerAdd(skin.id(), entities, id -> events.add("unexpected"), uuid -> events.add("unexpected"));
        checkSkin(events.size() == 3, "repeated profile without a ghost needs no cleanup");

        profiles.addedSynthetic(empty.id()); entities.add(empty.id(), -1_000_000_001);
        entities.clear(); events.clear(); // Dimension/entity disappearance does NOT remove player info.
        profiles.beforeServerAdd(empty.id(), entities, id -> events.add("unexpected"), uuid -> events.add("remove-profile"));
        checkSkin(events.equals(List.of("remove-profile")), "placeholder cleanup survives entity disappearance and dimension change");
        entities.add(empty.id(), -1_000_000_002); events.clear();
        profiles.beforeServerAdd(empty.id(), entities, id -> events.add("despawn"), uuid -> events.add("remove-profile"));
        checkSkin(events.equals(List.of("despawn", "remove-profile")), "tracked ghost is recreated for refreshed server profile");
        profiles.addedSynthetic(empty.id()); profiles.clear(); events.clear();
        profiles.beforeServerAdd(empty.id(), entities, id -> events.add("unexpected"), uuid -> events.add("unexpected"));
        checkSkin(events.isEmpty(), "new recording does not inherit placeholder identities");
        profiles.addedSynthetic(empty.id()); profiles.removed(empty.id());
        profiles.beforeServerAdd(empty.id(), entities, id -> events.add("unexpected"), uuid -> events.add("unexpected"));
        checkSkin(events.isEmpty(), "server profile removal clears placeholder ownership");
        profiles.addedSynthetic(empty.id()); replayTable.put(empty.id(), empty); events.clear();
        profiles.retain(java.util.Set.of(), uuid -> { replayTable.remove(uuid); events.add("remove-profile"); });
        replayTable.putIfAbsent(skin.id(), decoded);
        checkSkin(profiles.size() == 0 && events.equals(List.of("remove-profile"))
                        && replayTable.get(skin.id()).properties().get("textures").contains(texture),
                "despawn profile cleanup still permits late real skin without placeholder history");
        System.out.println("Replay skin checks completed: " + skinChecks + "; no skin network requests or rendering exercised.");
    }
}
