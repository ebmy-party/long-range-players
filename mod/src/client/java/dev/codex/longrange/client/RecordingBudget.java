package dev.codex.longrange.client;

import java.util.function.LongSupplier;

/** Per-recording packet ceiling and token buckets. No UUID history is retained. */
final class RecordingBudget {
    static final int MAX_PACKETS = 2_000_000;
    private final LongSupplier clock;
    private final int ceiling;
    private long last;
    private double spawns = 256, packets = 4096;
    private int written;
    RecordingBudget(LongSupplier clock) { this(clock, MAX_PACKETS); }
    RecordingBudget(LongSupplier clock, int ceiling) { this.clock = clock; this.ceiling = ceiling; last = clock.getAsLong(); }
    private void refill() {
        long now = clock.getAsLong(); double seconds = Math.max(0, (now - last) / 1_000_000_000.0); last = now;
        spawns = Math.min(256, spawns + seconds * 256 / 60);
        packets = Math.min(4096, packets + seconds * 4096);
    }
    boolean spawn() { refill(); if (spawns < 1) return false; spawns--; return true; }
    boolean packet() {
        refill(); if (written >= ceiling || packets < 1) return false;
        packets--; written++; return true;
    }
}
