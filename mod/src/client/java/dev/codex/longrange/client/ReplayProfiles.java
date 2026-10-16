package dev.codex.longrange.client;

import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Profiles outlive entities and dimensions in a recording's player-info table. */
final class ReplayProfiles {
    private final Set<UUID> synthetic = new HashSet<>();
    void addedSynthetic(UUID uuid) {
        if (!synthetic.contains(uuid) && synthetic.size() >= 64) throw new IllegalStateException("Synthetic profile capacity");
        synthetic.add(uuid);
    }
    void retain(Set<UUID> visible, Consumer<UUID> removeProfile) {
        for (UUID uuid : Set.copyOf(synthetic)) if (!visible.contains(uuid)) { synthetic.remove(uuid); removeProfile.accept(uuid); }
    }
    int size() { return synthetic.size(); }
    void removed(UUID uuid) { synthetic.remove(uuid); }
    void clear() { synthetic.clear(); }
    void beforeServerAdd(UUID uuid, ReplayEntityTracker entities, IntConsumer removeEntity, Consumer<UUID> removeProfile) {
        boolean placeholder = synthetic.remove(uuid);
        if (placeholder || entities.has(uuid)) {
            // Minecraft uses putIfAbsent for ADD_PLAYER; removing only the entity is insufficient.
            entities.remove(uuid, removeEntity);
            removeProfile.accept(uuid);
        }
    }
    static boolean upgradesMissingSkin(GameProfile existing, GameProfile incoming) {
        return existing != null && incoming != null && existing.id().equals(incoming.id())
                && existing.properties().get("textures").isEmpty() && !incoming.properties().get("textures").isEmpty();
    }
}
