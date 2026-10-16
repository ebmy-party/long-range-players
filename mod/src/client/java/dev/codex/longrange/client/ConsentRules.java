package dev.codex.longrange.client;

import java.util.UUID;

/** Pure decisions shared by launch UI, settings UI and the transport gate. */
final class ConsentRules {
    static String generalKey(String policy, UUID account) { return RoomIdentity.hash("launch-consent-v1\n" + policy + "\n" + account); }
    static boolean decided(String decision, String key) { return key.equals(decision) || ("declined:" + key).equals(decision); }
    static boolean maySend(ClientConfig config, String generalKey, String context, String policy) {
        return config.enabled && generalKey.equals(config.privacyDecision) && ConsentBinding.accepted(config.consents, context, policy);
    }
    static void record(ClientConfig config, String key, String context, String policy, boolean accept) {
        config.privacyDecision = accept ? key : "declined:" + key;
        config.enabled = accept;
        if (context != null) config.consents.put(context, accept ? policy : "declined:" + policy);
        if (!accept) config.consents.clear();
    }
}
