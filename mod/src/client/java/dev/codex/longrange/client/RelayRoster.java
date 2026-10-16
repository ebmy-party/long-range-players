package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Room membership is independent of render distance, dimension, and pose expiry. */
final class RelayRoster {
    record Member(UUID uuid, String name) { }
    private final Map<UUID, Member> members = new HashMap<>();
    void clear() { members.clear(); }
    void put(UUID uuid, String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid member name");
        if (members.containsKey(uuid) || members.size() < 64) members.put(uuid, new Member(uuid, name));
    }
    private void put(JsonObject peer) { put(UUID.fromString(peer.get("uuid").getAsString()), peer.get("name").getAsString()); }
    void accept(JsonObject message) {
        switch (message.get("type").getAsString()) {
            case "welcome" -> {
                clear();
                if (message.has("peers")) for (var peer : message.getAsJsonArray("peers")) put(peer.getAsJsonObject());
            }
            case "join" -> put(message.getAsJsonObject("peer"));
            case "pose" -> put(message);
            case "leave" -> members.remove(UUID.fromString(message.get("uuid").getAsString()));
            case "replaced" -> clear();
            default -> { }
        }
    }
    List<Member> snapshot() {
        return members.values().stream().sorted(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Member::uuid)).toList();
    }
}
