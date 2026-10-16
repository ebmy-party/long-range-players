package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Standalone loopback integration checks: real JDK WebSocket, no Minecraft launch or external services. */
public final class RegressionChecks {
    private static int checks;
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private static final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private static void pump() { for (Runnable action; (action = tasks.poll()) != null;) action.run(); }
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) { pump(); Thread.sleep(10); }
        pump(); if (!condition.getAsBoolean()) throw new AssertionError("Timed out waiting for local test server");
    }
    public static void main(String[] args) throws Exception {
        check(!RemoteVisibility.show(true, true, false, 0, 0, 0, 1000, 0, 3), "real tracked player always wins");
        check(!RemoteVisibility.show(false, false, false, 0, 0, 0, 1000, 0, 3), "other dimension excluded");
        check(!RemoteVisibility.show(false, true, true, 0, 0, 0, 1000, 0, 3), "invisible player excluded");
        check(!RemoteVisibility.show(false, true, false, 5001, 0, 0, 1000, 0, 3), "stale player excluded");
        check(!RemoteVisibility.show(false, true, false, 0, 0, 0, 63.9, 0, 3), "inside manual chunk radius excluded");
        check(RemoteVisibility.show(false, true, false, 0, 0, 0, 64, 0, 3), "outside chunk radius visible");
        check(!RemoteVisibility.show(false, true, false, 0, -1, -1, -64, -64, 3), "negative chunk coordinates use floor");
        ClientConfig defaults = ClientConfig.parse("{\"enabled\":true,\"token\":\"test-only\"}");
        check(!defaults.debug && defaults.minDistanceChunks == 2 && defaults.journeyMap, "missing distance defaults to two chunks");
        check(ClientConfig.parse("{\"minDistanceChunks\":3}").minDistanceChunks == 3, "existing explicit distance remains unchanged");
        check(PrivacyPolicy.of(defaults).url().equals("wss://lrp.ebmy.party/ws") && defaults.privacyDecision.isEmpty(), "requested default WSS endpoint restored without granting consent");
        check(ClientConfig.parse("{}").enabled && !ClientConfig.parse("{\"enabled\":false}").enabled, "enabled by default; existing explicit disable preserved");
        ClientConfig oldAuto = ClientConfig.parse("{\"autoDistance\":true,\"minDistanceChunks\":5}");
        check(oldAuto.minDistanceChunks == 5 && oldAuto.sameValues(ClientConfig.parse("{\"minDistanceChunks\":5}")), "obsolete auto-distance setting ignored; manual value preserved");
        ClientConfig copy = defaults.copy(); copy.debug = true;
        check(defaults.sameConnection(copy) && !defaults.sameValues(copy) && !defaults.debug, "debug change does not reconnect or mutate original config");
        check(ClientConfig.parse("{\"minDistanceChunks\":-1}").minDistanceChunks == 0, "distance bounded");
        check(RelayTransport.classify(new UnknownHostException("DO_NOT_PRINT")).contains("DNS"), "DNS error categorized");
        check(!RelayTransport.classify(new IOException("DO_NOT_PRINT")).contains("DO_NOT_PRINT"), "exception text is redacted");
        rosterChecks();
        replayLifecycleChecks();
        worldIdentityChecks();
        SecurityChecks.run();
        List<JsonObject> received = new ArrayList<>();
        List<RelayTransport.Status> statuses = new ArrayList<>();
        List<String> details = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1);
        RelayTransport transport = new RelayTransport(tasks::add, received::add, (status, detail) -> { statuses.add(status); details.add(detail); }, (hello, nonce, url) -> CompletableFuture.completedFuture(null), clock::get, true);
        JsonObject join = new JsonObject(); join.addProperty("type", "hello"); join.addProperty("retentionDays", 7);
        try (Server server = new Server()) {
            for (int i = 0; i < 8; i++) transport.connect(server.url(), join);
            until(() -> server.accepted.get() >= 1); Thread.sleep(150);
            check(server.accepted.get() == 1, "eight connect calls create one pending TCP connection");
            server.gate.countDown(); until(() -> server.clients.size() == 1 && server.clients.getFirst().messages.get() == 1);
            Client first = server.clients.getFirst();
            String welcome = "{\"type\":\"welcome\",\"protocol\":5,\"identityVerified\":true,\"peers\":[]}";
            challenge(first);
            first.frame(1, false, welcome.substring(0, 17)); Thread.sleep(80); pump();
            check(!statuses.contains(RelayTransport.Status.ONLINE), "incomplete text cannot mark ONLINE");
            first.frame(0, true, welcome.substring(17)); until(() -> statuses.getLast() == RelayTransport.Status.ONLINE);
            check(received.size() == 1, "fragmented welcome reassembled once");
            JsonObject pose = new JsonObject(); pose.addProperty("type", "pose");
            for (int i = 0; i < 50; i++) transport.send(pose);
            until(() -> first.messages.get() == 4); pump();
            check(statuses.getLast() == RelayTransport.Status.ONLINE, "consecutive sends are serialized and coalesced to latest pose");
            String invalidPose = "{\"type\":\"pose\",\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"AuditPeer\",\"dimension\":\"minecraft:overworld\",\"x\":0}";
            String validLeave = "{\"type\":\"leave\",\"uuid\":\"11111111-1111-1111-1111-111111111111\"}";
            first.frame(1, true, invalidPose); first.frame(1, true, validLeave);
            until(() -> received.size() == 2);
            check(statuses.getLast() == RelayTransport.Status.ONLINE && server.accepted.get() == 1,
                    "LRP-03 missing y/z dropped without reconnect; subsequent valid message delivered");
            // Stop pumping the game thread while a local relay sends a burst.
            pump();
            for (int i = 0; i < 100; i++) first.frame(1, true, validLeave);
            Thread.sleep(200);
            check(tasks.size() == 1, "LRP-02 100-message burst creates only one pending game task");
            until(() -> received.size() == 102);
            check(statuses.getLast() == RelayTransport.Status.ONLINE, "backpressured traffic drains without losing valid messages");
            first.frame(1, true, "{\"type\":\"pose\"}");
            long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (tasks.isEmpty() && System.nanoTime() < end) Thread.sleep(5);
            check(!tasks.isEmpty(), "old connection event queued before reconnect");
            transport.disconnect(); transport.connect(server.url(), join); pump();
            check(received.size() == 102, "queued obsolete message ignored even with same URL");
            until(() -> server.clients.size() == 2 && server.clients.get(1).messages.get() == 1);
            Client second = server.clients.get(1); challenge(second); second.frame(1, true, welcome);
            until(() -> statuses.getLast() == RelayTransport.Status.ONLINE);
            second.closeFrame(1008, "DO_NOT_PRINT"); until(() -> statuses.getLast() == RelayTransport.Status.ERROR);
            check(details.getLast().contains("1008") && !details.getLast().contains("DO_NOT_PRINT"), "close code retained and untrusted reason redacted");
            check(!transport.canRetry(), "retry delay enforced"); clock.addAndGet(Duration.ofSeconds(6).toNanos());
            check(transport.canRetry(), "retry allowed after delay");
            transport.connect(server.url(), join); until(() -> server.clients.size() == 3 && server.clients.get(2).messages.get() == 1);
            server.clients.get(2).frame(1, true, "{\"type\":\"welcome\",\"peers\":[]}");
            until(() -> statuses.getLast() == RelayTransport.Status.ERROR);
            check(!transport.isStarted(), "old relay protocol rejected");
            transport.disconnect(); transport.connect(server.url(), join);
            until(() -> server.clients.size() == 4 && server.clients.get(3).messages.get() == 1);
            clock.addAndGet(Duration.ofSeconds(31).toNanos()); transport.checkTimeout();
            check(statuses.getLast() == RelayTransport.Status.ERROR && details.getLast().contains("30秒"), "authentication deadline enforced");
            transport.disconnect(); transport.connect(server.url(), join);
            until(() -> server.clients.size() == 5 && server.clients.get(4).messages.get() == 1);
            Client fifth = server.clients.get(4); challenge(fifth); fifth.frame(1, true, welcome);
            until(() -> statuses.getLast() == RelayTransport.Status.ONLINE);
            int beforeInvalid = received.size();
            fifth.frame(1, true, welcome); // Duplicate welcome must not clear the roster.
            for (int i = 0; i < 6; i++) fifth.frame(1, true, invalidPose);
            fifth.frame(1, true, validLeave); until(() -> received.size() == beforeInvalid + 1);
            check(statuses.getLast() == RelayTransport.Status.ONLINE, "duplicate welcome and invalid poses cannot mutate accepted state");
            fifth.frame(1, true, invalidPose); until(() -> statuses.getLast() == RelayTransport.Status.ERROR);
            check(!transport.isStarted(), "eight invalid messages terminate abusive session");
            clock.addAndGet(Duration.ofSeconds(6).toNanos());
            transport.connect(server.url(), join);
            until(() -> server.clients.size() == 6 && server.clients.get(5).messages.get() == 1);
            Client sixth = server.clients.get(5); challenge(sixth); sixth.frame(1, true, welcome);
            until(() -> statuses.getLast() == RelayTransport.Status.ONLINE);
            for (int i = 0; i < 8; i++) sixth.frame(1, true, invalidPose);
            until(() -> statuses.getLast() == RelayTransport.Status.ERROR);
            clock.addAndGet(Duration.ofSeconds(6).toNanos());
            check(!transport.canRetry(), "short successful handshakes cannot reset exponential failure backoff");
            clock.addAndGet(Duration.ofSeconds(5).toNanos());
            check(transport.canRetry(), "second failure retries after bounded jittered 10-second delay");
            transport.disconnect(); transport.connect("ws://secret@example.invalid/ws", join);
            check(statuses.getLast() == RelayTransport.Status.ERROR && !details.getLast().contains("secret"), "invalid URL rejected without leaking credentials");
        } finally { transport.disconnect(); }
        System.out.println("Regression checks completed: " + checks + "; Minecraft rendering was not exercised.");
    }

    private static void challenge(Client client) throws Exception {
        client.frame(1, true, "{\"type\":\"challenge\",\"retentionDays\":7,\"protocol\":5,\"nonce\":\"" + "a".repeat(64) + "\"}");
        until(() -> client.messages.get() == 2);
    }

    private static void worldIdentityChecks() {
        WorldIdentity.clear(); check(WorldIdentity.current() == null, "no world before login");
        check(WorldIdentity.capture(12345L, "minecraft:overworld"), "first login sets world scope");
        var main = WorldIdentity.current();
        check(!WorldIdentity.capture(12345L, "minecraft:overworld"), "same world respawn retains scope");
        check(WorldIdentity.capture(54321L, "minecraft:overworld"), "proxy transfer changes scope");
        check(!WorldIdentity.current().fingerprint().equals(main.fingerprint()), "main/farm different discriminator separates worlds");
        var farm = WorldIdentity.current();
        check(WorldIdentity.capture(54321L, "minecraft:the_nether"), "dimension transfer clears old displays");
        check(WorldIdentity.current().fingerprint().equals(farm.fingerprint()), "dimension is separately filtered");
        WorldIdentity.capture(12345L, "minecraft:overworld");
        check(WorldIdentity.current().equals(main), "world identity consistent across reconnects");
        WorldIdentity.capture(0, "minecraft:overworld"); check(WorldIdentity.current().zero(), "zero world discriminator is exposed");
        WorldIdentity.clear(); check(WorldIdentity.current() == null, "disconnect clears world");
        check(ClientConfig.parse("{}").sameConnection(ClientConfig.parse("{\"worldIsolation\":false}")), "legacy separation opt-out is ignored");
    }
    private static void replayLifecycleChecks() {
        ReplayEntityTracker tracker = new ReplayEntityTracker();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Integer> removals = new ArrayList<>();
        tracker.add(first, -1_000_000_000);
        tracker.add(second, -1_000_000_001);
        tracker.retain(Set.of(first, second), removals::add);
        check(removals.isEmpty() && tracker.contains(first, -1_000_000_000), "replay keeps visible avatar without duplicate spawn");
        tracker.retain(Set.of(first), removals::add);
        check(removals.equals(List.of(-1_000_000_001)), "replay removes expired or hidden avatar only");
        List<String> packets = new ArrayList<>();
        tracker.remove(first, id -> packets.add("remove:" + id));
        packets.add("real-spawn");
        check(packets.equals(List.of("remove:-1000000000", "real-spawn")) && !tracker.contains(first, -1_000_000_000), "replay handoff removes ghost before real player spawn");
        tracker.remove(first, removals::add);
        check(removals.size() == 1, "replay handoff followed by tick does not remove twice");
        tracker.add(first, -1_000_000_002);
        check(!tracker.contains(first, -1_000_000_000) && tracker.contains(first, -1_000_000_002), "replay respawns returning far player with new entity ID");
        tracker.removeAll(removals::add);
        check(removals.equals(List.of(-1_000_000_001, -1_000_000_002)), "replay respawn removes all previous world avatars");
        tracker.add(first, -1_000_000_003);
        tracker.clear();
        tracker.removeAll(removals::add);
        check(!tracker.contains(first, -1_000_000_003) && removals.size() == 2, "new recording forgets old IDs without writing to new file");
    }
    private static void rosterChecks() {
        RelayRoster roster = new RelayRoster();
        String first = "00000000-0000-0000-0000-000000000001", second = "00000000-0000-0000-0000-000000000002";
        roster.accept(com.google.gson.JsonParser.parseString("{\"type\":\"welcome\",\"peers\":[{\"uuid\":\"" + first + "\",\"name\":\"Zebra\"}]}").getAsJsonObject());
        check(roster.snapshot().size() == 1, "roster includes member before first pose");
        roster.put(UUID.fromString(second), "Alpha");
        check(roster.snapshot().getFirst().name().equals("Alpha"), "roster includes self and sorts names");
        roster.accept(com.google.gson.JsonParser.parseString("{\"type\":\"pose_stale\",\"uuid\":\"" + first + "\"}").getAsJsonObject());
        check(roster.snapshot().size() == 2, "pose expiry does not remove connected member");
        roster.accept(com.google.gson.JsonParser.parseString("{\"type\":\"pose\",\"uuid\":\"" + first + "\",\"name\":\"Zebra\",\"invisible\":true,\"dimension\":\"minecraft:the_nether\"}").getAsJsonObject());
        check(roster.snapshot().size() == 2, "roster independent of dimension and invisibility without duplicates");
        roster.accept(com.google.gson.JsonParser.parseString("{\"type\":\"leave\",\"uuid\":\"" + first + "\"}").getAsJsonObject());
        check(roster.snapshot().size() == 1 && roster.snapshot().getFirst().uuid().equals(UUID.fromString(second)), "leave removes correct member");
        roster.accept(com.google.gson.JsonParser.parseString("{\"type\":\"welcome\",\"peers\":[]}").getAsJsonObject());
        check(roster.snapshot().isEmpty(), "reconnect welcome removes previous room roster");
    }

    private static final class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger accepted = new AtomicInteger();
        final CountDownLatch gate = new CountDownLatch(1);
        final List<Client> clients = new CopyOnWriteArrayList<>();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        Server() throws IOException {
            Thread.startVirtualThread(() -> {
                try { while (!listener.isClosed()) { Socket socket = listener.accept(); sockets.add(socket); accepted.incrementAndGet(); Thread.startVirtualThread(() -> serve(socket)); } }
                catch (IOException ignored) { }
            });
        }
        String url() { return "ws://127.0.0.1:" + listener.getLocalPort() + "/ws"; }
        void serve(Socket socket) {
            try {
                gate.await(); InputStream in = socket.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                while (header.size() < 8192) { int b = in.read(); if (b < 0) return; header.write(b); if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break; }
                String key = header.toString(StandardCharsets.US_ASCII).lines().filter(s -> s.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                        .findFirst().orElseThrow().split(":", 2)[1].trim();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush(); Client client = new Client(socket); clients.add(client);
                while (true) {
                    int op = in.read(), b = in.read(); if (op < 0 || b < 0) break;
                    int length = b & 127;
                    if (length == 126) length = in.read() * 256 + in.read();
                    if (length == 127) throw new IOException("Unexpected client payload size");
                    byte[] mask = (b & 128) != 0 ? in.readNBytes(4) : new byte[0];
                    byte[] payload = in.readNBytes(length); if (payload.length != length) break;
                    if ((op & 15) == 1) client.messages.incrementAndGet();
                    if ((op & 15) == 8) break;
                }
            } catch (Exception ignored) { } finally { try { socket.close(); } catch (IOException ignored) { } }
        }
        public void close() throws IOException { gate.countDown(); listener.close(); for (Socket socket : sockets) socket.close(); }
    }
    private static final class Client {
        final Socket socket; final AtomicInteger messages = new AtomicInteger();
        Client(Socket socket) { this.socket = socket; }
        void frame(int opcode, boolean fin, String text) throws IOException { frame(opcode, fin, text.getBytes(StandardCharsets.UTF_8)); }
        synchronized void frame(int opcode, boolean fin, byte[] bytes) throws IOException {
            OutputStream out = socket.getOutputStream(); out.write((fin ? 128 : 0) | opcode);
            if (bytes.length < 126) out.write(bytes.length); else { out.write(126); out.write(bytes.length >> 8); out.write(bytes.length & 255); }
            out.write(bytes); out.flush();
        }
        void closeFrame(int code, String reason) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream(); payload.write(code >> 8); payload.write(code & 255); payload.write(reason.getBytes(StandardCharsets.UTF_8)); frame(8, true, payload.toByteArray());
        }
    }
}
