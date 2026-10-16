package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;

/** All state changes are dispatched to the game thread and bound to one connection attempt. */
final class RelayTransport {
    static final int PROTOCOL = 5;
    @FunctionalInterface interface IdentityProof { CompletionStage<Void> prove(JsonObject hello, String nonce, String relayUrl); }
    enum Status { OFFLINE, CONNECTING, ONLINE, ERROR }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Consumer<Runnable> dispatch;
    private final Consumer<JsonObject> messages;
    private final BiConsumer<Status, String> statuses;
    private volatile Attempt current;
    private long retryAfter;
    private int failures;
    private final LongSupplier clock;
    private final IdentityProof identity;
    private final boolean loopbackTest;

    RelayTransport(Consumer<Runnable> dispatch, Consumer<JsonObject> messages, BiConsumer<Status, String> statuses, IdentityProof identity) {
        this(dispatch, messages, statuses, identity, System::nanoTime, false);
    }
    // Only local test code supplies loopbackTest=true; no config/environment option exposes it.
    RelayTransport(Consumer<Runnable> dispatch, Consumer<JsonObject> messages, BiConsumer<Status, String> statuses,
                   IdentityProof identity, LongSupplier clock, boolean loopbackTest) {
        this.dispatch = dispatch; this.messages = messages; this.statuses = statuses;
        this.clock = clock; this.identity = identity; this.loopbackTest = loopbackTest;
    }
    boolean isStarted() { return current != null; }
    boolean canRetry() { return clock.getAsLong() >= retryAfter; }
    void connect(String address, JsonObject join) {
        if (current != null) return;
        try {
            URI uri = URI.create(address);
            if (!("wss".equals(uri.getScheme()) || (loopbackTest && "ws".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || !"/ws".equals(uri.getPath())) throw new IllegalArgumentException();
            Attempt attempt = new Attempt(join.deepCopy(), address);
            current = attempt;
            statuses.accept(Status.CONNECTING, "Relayに接続中");
            attempt.future = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, attempt);
            attempt.future.whenComplete((ws, error) -> {
                if (error != null) deliver(attempt, () -> fail(classify(error)));
                else if (current != attempt) ws.abort();
            });
        } catch (RuntimeException error) { fail("Relay URLが不正です。認証通信にはwss://と末尾/wsが必要です"); }
    }
    void checkTimeout() {
        Attempt attempt = current;
        if (attempt != null && attempt.outbound.timedOut()) { fail("Relayへの送信がタイムアウトしました（10秒）"); return; }
        if (attempt != null && !attempt.welcomed && clock.getAsLong() - attempt.started > Duration.ofSeconds(30).toNanos())
            fail("認証応答がタイムアウトしました（30秒）");
    }
    void send(JsonObject message) {
        Attempt attempt = current;
        if (attempt != null && attempt.socket != null && attempt.welcomed) send(attempt, message);
    }
    private void send(Attempt attempt, JsonObject message) {
        attempt.outbound.offer(message.toString());
    }
    void disconnect() {
        Attempt old = current;
        current = null; retryAfter = 0; failures = 0;
        if (old == null) return;
        old.outbound.close();
        if (old.socket != null) old.socket.abort();
        if (old.future != null && !old.future.isDone()) old.future.cancel(true);
    }
    private void fail(String reason) {
        int count = current != null && current.welcomed && clock.getAsLong() - current.started > Duration.ofMinutes(1).toNanos()
                ? 1 : Math.min(5, failures + 1);
        disconnect(); failures = count;
        long delay = Math.min(60, 5L << (count - 1));
        retryAfter = clock.getAsLong() + Duration.ofSeconds(delay).toNanos() + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        statuses.accept(Status.ERROR, reason);
    }
    private void deliver(Attempt attempt, Runnable action) { dispatch.accept(() -> { if (current == attempt) action.run(); }); }
    static String classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebSocketHandshakeException handshake)
                return "WebSocket接続拒否: HTTP " + handshake.getResponse().statusCode() + "（/ws・プロキシ設定を確認）";
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) return "DNS名前解決に失敗しました";
            if (cause instanceof SSLException) return "TLS接続に失敗しました（証明書・wss設定を確認）";
            if (cause instanceof HttpTimeoutException) return "接続がタイムアウトしました（到達性・ポートを確認）";
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof ConnectException) return "Relayへ接続できません（起動状態・ポート・ファイアウォールを確認）";
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        // Never expose raw exception messages or untrusted close reasons, which may contain secrets.
        return "通信エラー: " + error.getClass().getSimpleName();
    }
    static String closeReason(int code) {
        return switch (code) {
            case 1008 -> "認証・同意情報または入力が拒否されました [1008]";
            case 4007 -> "このUUIDはRelay管理者により接続を拒否されています [4007]";
            case 4008 -> "Relayの保存日数と同意内容が一致しません [4008]";
            case 4004 -> "グループコードを確認してください。/lrp code で設定できます [4004]";
            case 4005 -> "Minecraftアカウントの本人確認に失敗しました [4005]";
            case 4006 -> "同じアカウントの接続が既にあります。既存接続を終了してください [4006]";
            case 1013 -> "Relayまたは部屋の人数上限です [1013]";
            case 4001 -> "同じUUIDの別接続に置き換えられました [4001]";
            case 4002 -> "RelayとMODの通信バージョンが一致しません。MODを最新版へ更新してください [4002]";
            case 1001 -> "Relayが停止または再起動しました [1001]";
            case 1007 -> "Relayがメッセージ形式を拒否しました [1007]";
            case 1009 -> "メッセージサイズ上限を超えました [1009]";
            default -> "Relay接続が切断されました [" + code + "]";
        };
    }
    private final class Attempt implements WebSocket.Listener {
        final JsonObject join;
        final String address;
        final long started = clock.getAsLong();
        final StringBuilder fragments = new StringBuilder();
        final ReceiveBudget budget = new ReceiveBudget(clock);
        final AtomicBoolean terminal = new AtomicBoolean();
        final LatestSendQueue outbound = new LatestSendQueue(text -> this.socket.sendText(text, true),
                action -> deliver(this, action), error -> fail(classify(error)), clock);
        CompletableFuture<WebSocket> future;
        volatile WebSocket socket;
        volatile boolean welcomed;
        boolean challenged, proofSent;
        int strikes;
        Attempt(JsonObject join, String address) { this.join = join; this.address = address; }
        @Override public void onOpen(WebSocket ws) {
            socket = ws;
            if (current != this) { ws.abort(); return; }
            deliver(this, () -> send(this, join)); ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (current != this || terminal.get()) { ws.abort(); return null; }
            if (!budget.allow(data.length(), last) || data.length() > RelayDecoder.MAX_CHARS - fragments.length()) {
                fragments.setLength(0); stop(ws, "Relay応答がサイズ・受信量の上限を超えました"); return null;
            }
            fragments.append(data);
            if (last) {
                String text = fragments.toString(); fragments.setLength(0);
                // Parse and validate on the WebSocket callback thread; never queue raw untrusted JSON.
                JsonObject decoded;
                try { decoded = RelayDecoder.decode(text); }
                catch (RuntimeException error) { decoded = null; }
                final JsonObject message = decoded;
                deliver(this, () -> {
                    try { accept(message); }
                    finally {
                        // Backpressure: at most one completed data message awaits the game thread.
                        if (current == this && !terminal.get()) ws.request(1);
                    }
                });
                return null;
            }
            ws.request(1); return null;
        }
        private void accept(JsonObject message) {
            if (terminal.get()) return;
            String type = message == null ? "" : message.get("type").getAsString();
            if (!welcomed) {
                if ("challenge".equals(type) && !challenged && message.get("protocol").getAsInt() == PROTOCOL) {
                    challenged = true;
                    String nonce = message.get("nonce").getAsString();
                    identity.prove(join.deepCopy(), nonce, address).whenComplete((ignored, error) -> deliver(this, () -> {
                        if (error != null) { fail("Minecraftアカウントの本人確認に失敗しました。ランチャーのログイン状態を確認してください"); return; }
                        JsonObject proof = new JsonObject(); proof.addProperty("type", "prove"); proof.addProperty("nonce", nonce);
                        proofSent = true; send(this, proof);
                    }));
                    return;
                }
                if (!proofSent || !"welcome".equals(type) || message.get("protocol").getAsInt() != PROTOCOL
                        || !message.get("identityVerified").getAsBoolean()) {
                    fail("Relayの認証応答が不正です。MODとRelayを通信プロトコル5対応版に揃えてください"); return;
                }
                welcomed = true; statuses.accept(Status.ONLINE, "本人確認済み・グループに接続しました");
            } else if (message == null || "welcome".equals(type) || "challenge".equals(type)) {
                if (++strikes >= 8) fail("Relayから不正な応答が繰り返されたため切断しました");
                return; // One invalid peer message cannot mutate presence or disconnect the room.
            }
            if ("replaced".equals(type)) { fail(closeReason(4001)); return; }
            messages.accept(message);
        }
        private void stop(WebSocket ws, String reason) {
            if (terminal.compareAndSet(false, true)) { ws.abort(); deliver(this, () -> fail(reason)); }
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            stop(ws, "Relayから未対応のバイナリ応答を受信しました"); return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer data) {
            if (!budget.allow(data.remaining(), false)) { stop(ws, "Relay制御フレームの受信量を超えました"); return null; }
            return WebSocket.Listener.super.onPing(ws, data);
        }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) {
            if (!budget.allow(data.remaining(), false)) { stop(ws, "Relay制御フレームの受信量を超えました"); return null; }
            return WebSocket.Listener.super.onPong(ws, data);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { stop(ws, closeReason(code)); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { stop(ws, classify(error)); }
    }
}
