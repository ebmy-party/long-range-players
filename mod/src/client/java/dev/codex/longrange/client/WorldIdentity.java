package dev.codex.longrange.client;

/** JourneyMap 6.0.10 Multi World Support uses this same login/respawn seed field.
 * It is a server-supplied world discriminator, not a guaranteed backend ID or authentication. */
public final class WorldIdentity {
    private static Snapshot current;
    private WorldIdentity() { }

    public static boolean capture(long seed, String dimension) {
        Snapshot next = new Snapshot(RoomIdentity.hash("lrp-world-v1:" + seed), dimension, seed == 0);
        boolean changed = !next.equals(current);
        current = next;
        return changed;
    }
    public static Snapshot current() { return current; }
    public static void clear() { current = null; }
    public record Snapshot(String fingerprint, String dimension, boolean zero) {
        public String shortId() { return fingerprint.substring(0, 24); }
    }
}
