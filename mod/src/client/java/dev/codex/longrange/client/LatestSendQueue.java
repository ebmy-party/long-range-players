package dev.codex.longrange.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Game-thread owned: one in-flight send and one replaceable latest pose, never a future chain. */
final class LatestSendQueue {
    private final Function<String, CompletableFuture<?>> send;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> fail;
    private final LongSupplier clock;
    private String latest;
    private boolean sending, closed;
    private long started;
    LatestSendQueue(Function<String, CompletableFuture<?>> send, Consumer<Runnable> dispatch,
                    Consumer<Throwable> fail, LongSupplier clock) {
        this.send = send; this.dispatch = dispatch; this.fail = fail; this.clock = clock;
    }
    void offer(String text) {
        if (closed) return;
        if (sending) { latest = text; return; }
        sending = true; started = clock.getAsLong();
        try {
            send.apply(text).whenComplete((ignored, error) -> dispatch.accept(() -> {
                if (closed) return;
                sending = false;
                if (error != null) { close(); fail.accept(error); return; }
                String next = latest; latest = null;
                if (next != null) offer(next);
            }));
        } catch (RuntimeException error) { close(); fail.accept(error); }
    }
    boolean timedOut() { return sending && clock.getAsLong() - started > Duration.ofSeconds(10).toNanos(); }
    void close() { closed = true; latest = null; sending = false; }
}
