package dev.codex.longrange.client;

/** Kept independent of the rendering engine so distance and overlap rules can be regression tested. */
final class RemoteVisibility {
    static boolean show(boolean tracked, boolean sameDimension, boolean invisible, long ageMs,
                        double localX, double localZ, double remoteX, double remoteZ, int radius) {
        if (tracked || !sameDimension || invisible || ageMs > 5_000 || ageMs < 0) return false;
        int dx = Math.abs((int) Math.floor(localX / 16) - (int) Math.floor(remoteX / 16));
        int dz = Math.abs((int) Math.floor(localZ / 16) - (int) Math.floor(remoteZ / 16));
        return Math.max(dx, dz) > radius;
    }
}
