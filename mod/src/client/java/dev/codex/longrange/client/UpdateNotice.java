package dev.codex.longrange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** Optional update notice: fixed HTTPS destination, after consent, once per launch, no automatic download. */
final class UpdateNotice {
    static final String PAGE = "https://github.com/ebmy-party/long-range-players/releases/latest";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static boolean attempted, announced;
    private static volatile String latest;
    private static String requestedAccount;
    private static CompletableFuture<?> pending;
    static void tick(Minecraft client) {
        var config = LongRangePlayersClient.config();
        String account = ConsentScreen.generalKey(client);
        if (ReplayRecording.isPlayback() || !ConsentRules.maySend(config, account, null, PrivacyPolicy.current().hash())) {
            if (pending != null) { pending.cancel(true); pending = null; }
            return;
        }
        if (client.player == null) return;
        if (!attempted) {
            attempted = true; requestedAccount = account;
            String installed = FabricLoader.getInstance().getModContainer("long_range_players").orElseThrow().getMetadata().getVersion().getFriendlyString();
            var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/ebmy-party/long-range-players/releases/latest"))
                    .timeout(Duration.ofSeconds(8)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Long-Range-Players-update-check").GET().build();
            var transfer = HTTP.sendAsync(request, info -> new BoundedBody());
            pending = transfer;
            transfer.thenAccept(response -> {
                if (response.statusCode() == 200) latest = ReleaseVersion.available(response.body(), installed);
            }).exceptionally(error -> null);
        }
        if (!announced && latest != null && account.equals(requestedAccount)) {
            announced = true;
            client.player.sendSystemMessage(Component.literal("[LR-Players] 新しいバージョン " + latest + " があります。MODを更新してください：" + PAGE));
        }
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<String> getBody() { return body; }
        public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > 65536 - bytes.size()) { subscription.cancel(); body.completeExceptionally(new IllegalStateException("Release response too large")); return; }
                byte[] chunk = new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
