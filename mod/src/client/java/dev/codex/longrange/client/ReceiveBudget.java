package dev.codex.longrange.client;

import java.util.function.LongSupplier;

/** Token buckets bound callback CPU and text parsing even when the game thread drains quickly. */
final class ReceiveBudget {
    private final LongSupplier clock;
    private long last;
    private double frames = 4096, chars = 4_194_304, messages = 2048;
    ReceiveBudget(LongSupplier clock) { this.clock = clock; last = clock.getAsLong(); }
    synchronized boolean allow(int length, boolean complete) {
        long now = clock.getAsLong();
        double elapsed = Math.max(0, (now - last) / 1_000_000_000.0); last = now;
        frames = Math.min(4096, frames + elapsed * 4096);
        chars = Math.min(4_194_304, chars + elapsed * 2_097_152);
        messages = Math.min(2048, messages + elapsed * 1024);
        if (frames < 1 || chars < length || (complete && messages < 1)) return false;
        frames--; chars -= length; if (complete) messages--;
        return true;
    }
}
