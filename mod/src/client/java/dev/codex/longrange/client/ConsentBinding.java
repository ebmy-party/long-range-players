package dev.codex.longrange.client;

import java.util.Map;
import java.util.UUID;

final class ConsentBinding {
    static String key(String address, String world, UUID account) {
        return RoomIdentity.hash("consent-v1\n" + ClientConfig.serverKey(address) + "\n" + world + "\n" + account);
    }
    static boolean accepted(Map<String, String> decisions, String context, String policy) {
        return context != null && policy.equals(decisions.get(context));
    }
}
