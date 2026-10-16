package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

final class MojangIdentity {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static CompletionStage<Void> prove(JsonObject hello, String nonce, String relayUrl) {
        var user = Minecraft.getInstance().getUser();
        if (!user.getProfileId().toString().equals(hello.get("uuid").getAsString()) || !BUSY.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("Identity unavailable"));
        try {
            JsonObject request = new JsonObject();
            // Access token is sent ONLY to this literal official HTTPS endpoint. Never to Relay, disk or logs.
            request.addProperty("accessToken", user.getAccessToken());
            request.addProperty("selectedProfile", user.getProfileId().toString().replace("-", ""));
            request.addProperty("serverId", SessionProof.hash(hello, nonce, relayUrl));
            HttpRequest http = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                    .timeout(Duration.ofSeconds(7)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            return HTTP.sendAsync(http, HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
                if (response.statusCode() != 204) throw new CompletionException(new IllegalStateException("Identity rejected"));
                return (Void)null;
            }).whenComplete((ignored, error) -> BUSY.set(false));
        } catch (RuntimeException error) { BUSY.set(false); return CompletableFuture.failedFuture(new IllegalStateException("Identity unavailable")); }
    }
}
