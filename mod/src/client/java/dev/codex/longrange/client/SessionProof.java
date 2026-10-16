package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Domain-separated, client-contributed challenge; never accept an arbitrary serverHash from Relay. */
final class SessionProof {
    private static final SecureRandom RANDOM = new SecureRandom();
    static String nonce() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    static String hash(JsonObject hello, String nonce, String url) {
        String text = String.join("\n", "long-range-players/session/v5", hello.get("clientNonce").getAsString(), nonce,
                hello.get("uuid").getAsString(), hello.get("serverId").getAsString(), hello.get("world").getAsString(), url, hello.has("groupCode") ? RoomIdentity.hash(hello.get("groupCode").getAsString()) : "");
        try { return new BigInteger(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8))).toString(16); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
