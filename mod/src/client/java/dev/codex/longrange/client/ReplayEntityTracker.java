package dev.codex.longrange.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Recording lifecycle, confined to the Minecraft packet/tick thread. */
final class ReplayEntityTracker {
    private final Map<UUID, Integer> ids = new HashMap<>();
    boolean has(UUID uuid) { return ids.containsKey(uuid); }
    boolean contains(UUID uuid, int id) { return Integer.valueOf(id).equals(ids.get(uuid)); }
    void add(UUID uuid, int id) { ids.put(uuid, id); }
    void remove(UUID uuid, IntConsumer removePacket) {
        Integer id = ids.remove(uuid);
        if (id != null) removePacket.accept(id);
    }
    void retain(Set<UUID> visible, IntConsumer removePacket) {
        for (UUID uuid : Set.copyOf(ids.keySet())) if (!visible.contains(uuid)) remove(uuid, removePacket);
    }
    void removeAll(IntConsumer removePacket) { retain(Set.of(), removePacket); }
    void clear() { ids.clear(); }
}
