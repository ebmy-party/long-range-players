package dev.codex.longrange.client;

import com.google.gson.*;
import java.util.Set;
import java.util.UUID;

/** Untrusted wire JSON is bounded and rebuilt before it can reach roster, renderer or recorder. */
final class RelayDecoder {
    static final int MAX_CHARS = 262_144;
    private static final Set<String> SLOTS = Set.of("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET");
    private RelayDecoder() { }
    static JsonObject decode(String text) {
        boundedJson(text);
        JsonObject input = JsonParser.parseString(text).getAsJsonObject();
        String type = string(input, "type", 16);
        JsonObject out = new JsonObject(); out.addProperty("type", type);
        switch (type) {
            case "challenge" -> {
                out.addProperty("protocol", integer(input, "protocol", 0, 1000));
                String nonce = string(input, "nonce", 64);
                if (!nonce.matches("[a-f0-9]{64}")) throw bad();
                out.addProperty("nonce", nonce);
                out.addProperty("retentionDays", integer(input, "retentionDays", 1, 30));
            }
            case "welcome" -> {
                out.addProperty("protocol", integer(input, "protocol", 0, 1000));
                if (!input.has("identityVerified") || !input.get("identityVerified").isJsonPrimitive()
                        || !input.get("identityVerified").getAsJsonPrimitive().isBoolean()) throw bad();
                out.addProperty("identityVerified", input.get("identityVerified").getAsBoolean());
                JsonArray peers = input.getAsJsonArray("peers");
                if (peers == null || peers.size() > 64) throw bad();
                JsonArray clean = new JsonArray();
                for (JsonElement value : peers) clean.add(peer(value.getAsJsonObject(), false));
                out.add("peers", clean);
            }
            case "join" -> out.add("peer", peer(input.getAsJsonObject("peer"), false));
            case "pose" -> {
                JsonObject pose = peer(input, true);
                for (var entry : pose.entrySet()) out.add(entry.getKey(), entry.getValue());
            }
            case "leave", "pose_stale" -> out.addProperty("uuid", uuid(input));
            case "replaced" -> { }
            default -> throw bad();
        }
        return out;
    }
    private static JsonObject peer(JsonObject in, boolean requirePose) {
        if (in == null) throw bad();
        JsonObject out = new JsonObject(); out.addProperty("uuid", uuid(in));
        String name = string(in, "name", 16);
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw bad();
        out.addProperty("name", name); out.addProperty("dimension", identifier(in, "dimension"));
        boolean hasPose = requirePose || in.has("x") || in.has("y") || in.has("z");
        if (!hasPose) return out;
        out.addProperty("x", number(in, "x", -30_000_000, 30_000_000));
        out.addProperty("y", number(in, "y", -4096, 4096));
        out.addProperty("z", number(in, "z", -30_000_000, 30_000_000));
        for (String key : new String[]{"yaw", "pitch", "bodyYaw", "headYaw"}) {
            if (!in.has(key) && !key.equals("yaw") && !key.equals("pitch")) continue;
            double angle = number(in, key, -Double.MAX_VALUE, Double.MAX_VALUE);
            out.addProperty(key, key.equals("pitch") ? Math.clamp(angle, -90, 90) : ((angle % 360) + 360) % 360);
        }
        for (String key : new String[]{"sneaking", "sprinting", "swimming", "fallFlying", "onGround", "invisible", "usingItem"}) {
            if (!in.has(key)) continue;
            JsonElement value = in.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw bad();
            out.addProperty(key, value.getAsBoolean());
        }
        choice(in, out, "stance", Set.of("STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING", "SLEEPING", "SPIN_ATTACK"));
        choice(in, out, "mainArm", Set.of("LEFT", "RIGHT"));
        choice(in, out, "useHand", Set.of("MAIN_HAND", "OFF_HAND"));
        choice(in, out, "swingHand", Set.of("MAIN_HAND", "OFF_HAND"));
        if (in.has("swing")) out.addProperty("swing", integer(in, "swing", 0, Integer.MAX_VALUE));
        if (in.has("modelParts")) out.addProperty("modelParts", integer(in, "modelParts", 0, 127));
        if (in.has("equipment")) {
            JsonObject equipment = in.getAsJsonObject("equipment"), clean = new JsonObject();
            if (equipment == null || equipment.size() > 6) throw bad();
            for (String slot : equipment.keySet()) {
                if (!SLOTS.contains(slot)) throw bad();
                clean.addProperty(slot, identifier(equipment, slot));
            }
            out.add("equipment", clean);
        }
        return out;
    }
    private static void choice(JsonObject in, JsonObject out, String key, Set<String> choices) {
        if (!in.has(key)) return;
        String value = string(in, key, 32);
        if (!choices.contains(value)) throw bad();
        out.addProperty(key, value);
    }
    private static String uuid(JsonObject in) {
        String value = string(in, "uuid", 36);
        if (!value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw bad();
        return UUID.fromString(value).toString();
    }
    private static String identifier(JsonObject in, String key) {
        String value = string(in, key, 128);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_][a-z0-9_./-]*")) throw bad();
        return value;
    }
    private static String string(JsonObject in, String key, int max) {
        JsonElement value = in.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad();
        String text = value.getAsString();
        if (text.length() > max) throw bad();
        return text;
    }
    private static double number(JsonObject in, String key, double min, double max) {
        JsonElement value = in.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad();
        double n = value.getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max) throw bad();
        return n;
    }
    private static int integer(JsonObject in, String key, int min, int max) {
        double n = number(in, key, min, max);
        if (n != Math.rint(n)) throw bad();
        return (int)n;
    }
    static void boundedJson(String text) {
        if (text.length() > MAX_CHARS) throw bad();
        int depth = 0; boolean quoted = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 8) throw bad(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw bad(); }
        }
        if (quoted || depth != 0) throw bad();
    }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Invalid relay message"); }
}
