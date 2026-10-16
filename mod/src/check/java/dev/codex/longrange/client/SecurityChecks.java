package dev.codex.longrange.client;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, local-only reproductions of the reported resource and schema defects. */
final class SecurityChecks {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    private static boolean rejected(String text) {
        try { RelayDecoder.decode(text); return false; } catch (RuntimeException expected) { return true; }
    }
    static void run() throws Exception {
        for (String code : List.of("Abcd1234", "a1".repeat(32))) check(ClientConfig.validCode(code), "group code accepts boundary length");
        for (String code : List.of("", "Abc1234", "abcdefgh", "12345678", "a1".repeat(33), "Abc1234!", "Ａbc12345"))
            check(!ClientConfig.validCode(code), "group code rejects invalid length or characters");
        ClientConfig scoped = new ClientConfig(); scoped.groupCode = "Shared1234567890";
        check(scoped.copy().groupCode("mc.example").equals("Shared1234567890"), "startup code persists without joining a server");
        var moved = scoped.copy(); moved.groupCode = "Other12345";
        check(!scoped.sameConnection(moved), "code changes force reconnect without clearing consent");
        ClientConfig deployment = ClientConfig.parse("{\"relayUrl\":\"wss://other.example/ws\",\"relayRetentionDays\":99}");
        check(PrivacyPolicy.of(deployment).url().equals(ClientConfig.DEFAULT_RELAY_URL), "old endpoint settings cannot redirect the client");
        check(PrivacyPolicy.of(deployment).hash().equals(PrivacyPolicy.of(new ClientConfig()).hash()), "server retention is no longer client-controlled");
        check(PrivacyPolicy.of(new ClientConfig()).ready() && !new ClientConfig().enabled, "default destination requires consent");
        String migrated = "{\"groupCodes\":{\"" + "a".repeat(64) + "\":\"Shared1234567890\"}}";
        check(ClientConfig.parse(migrated).groupCode.equals("Shared1234567890"), "single legacy code migrates to startup code");
        check(ReleaseVersion.newer("v0.10.0", "0.9.0+mc.26.2"), "update versions compare numbers rather than text");
        check(!ReleaseVersion.newer("v0.9.0", "0.9.0+mc.26.2") && !ReleaseVersion.newer("v0.8.0", "0.9.0"), "same and older releases do not notify");
        check(!ReleaseVersion.newer("v1.0.0-beta", "0.9.0") && !ReleaseVersion.newer("999999999999.0.0", "0.9.0"), "unsupported versions rejected");
        String release = "{\"tag_name\":\"v0.10.0\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"name\":\"long-range-players-0.10.0+mc.26.2.jar\"}]}";
        check("0.10.0".equals(ReleaseVersion.available(release,"0.9.0")), "stable compatible release produces update notice");
        check(ReleaseVersion.available(release.replace("26.2", "27.0"),"0.9.0") == null, "different Minecraft release does not notify");
        check(ReleaseVersion.available(release.replace("false", "true"),"0.9.0") == null, "draft or prerelease does not notify");
        check(ReleaseVersion.available("{}", "0.9.0") == null && ReleaseVersion.available(" ".repeat(65537), "0.9.0") == null, "invalid or oversized release metadata ignored");
        for (String url : List.of("ws://relay.example/ws", "wss://user:pass@relay.example/ws", "wss://relay.example/ws?x=1", "wss://relay.example/%77s")) {
            boolean denied = false;
            try { PrivacyPolicy.normalizeUrl(url); } catch (IllegalArgumentException expected) { denied = true; }
            check(denied, "unsafe custom Relay URL rejected before connection");
        }
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var body = new UpdateNotice.BoundedBody();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[65537])));
        check(cancelled.get() && body.getBody().toCompletableFuture().isCompletedExceptionally(), "update response stops at 64 KiB");
        var constructed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<JsonObject> visible = () -> { constructed.incrementAndGet(); JsonObject p = new JsonObject(); p.addProperty("x", 123); return p; };
        for (boolean[] flags : List.of(new boolean[]{true, false}, new boolean[]{false, true}, new boolean[]{true, true})) {
            var hidden = PoseDisclosure.message(flags[0], flags[1], visible);
            check(hidden.size() == 1 && hidden.get("type").getAsString().equals("withdraw"), "hidden/spectator sends only reason-free withdrawal");
        }
        check(constructed.get() == 0, "hidden coordinates and equipment are never serialized");
        check(PoseDisclosure.message(false, false, visible).has("x") && constructed.get() == 1, "visible state resumes normal pose");
        JsonObject hello = new JsonObject();
        hello.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        hello.addProperty("serverId", "a".repeat(64)); hello.addProperty("world", "b".repeat(64)); hello.addProperty("clientNonce", "c".repeat(64));
        check(SessionProof.hash(hello, "d".repeat(64), "wss://relay.example/ws").equals("-6527bb1153aacf687a8060dde3a801e5af463175"), "Java and Node identity proofs use identical signed SHA-1 format");
        List<RelayTransport.Status> unsafeStatuses = new ArrayList<>();
        RelayTransport secure = new RelayTransport(Runnable::run, ignored -> {}, (state, detail) -> unsafeStatuses.add(state),
                (h,n,u) -> { throw new AssertionError("plaintext URL must not reach identity proof"); });
        secure.connect("ws://127.0.0.1/ws", hello);
        check(!secure.isStarted() && unsafeStatuses.getLast() == RelayTransport.Status.ERROR, "production client refuses plaintext even on loopback");
        String prefix = "{\"type\":\"pose\",\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"AuditPeer\",\"dimension\":\"minecraft:overworld\",";
        String valid = prefix + "\"x\":0,\"y\":70,\"z\":0,\"yaw\":0,\"pitch\":0}";
        check(rejected(prefix + "\"x\":0}"), "LRP-03 exact missing y/z report rejected before roster mutation");
        for (String key : List.of("uuid", "name", "dimension", "x", "y", "z", "yaw", "pitch")) {
            JsonObject pose = JsonParser.parseString(valid).getAsJsonObject(); pose.remove(key);
            check(rejected(pose.toString()), "required pose field: " + key);
        }
        check(rejected(valid.replace("\"y\":70", "\"y\":\"70\"")), "numeric strings cannot bypass schema");
        check(rejected(valid.replace("\"x\":0", "\"x\":1e308")), "huge coordinates rejected");
        JsonObject normalized = RelayDecoder.decode(valid.replace("\"yaw\":0", "\"yaw\":1e308").replace("\"pitch\":0", "\"pitch\":-1e308"));
        check(Float.isFinite(normalized.get("yaw").getAsFloat()) && normalized.get("pitch").getAsFloat() == -90,
                "LRP-09 finite double cannot become infinite render angle");
        check(rejected(valid.replace("\"yaw\":0", "\"yaw\":1e9999")), "non-finite number rejected");
        check(rejected(valid.replace("\"pitch\":0", "\"pitch\":0,\"swing\":1.5")), "fractional swing rejected");
        check(rejected(valid.replace("\"pitch\":0", "\"pitch\":0,\"modelParts\":256")), "out-of-range model flags rejected");
        check(rejected(valid.replace("\"pitch\":0", "\"pitch\":0,\"equipment\":{\"HEAD\":\"invalid item\"}")), "invalid equipment rejected");
        check(rejected("[".repeat(10000) + "0" + "]".repeat(10000)), "deep JSON rejected before recursive parser");
        check(rejected(" ".repeat(RelayDecoder.MAX_CHARS + 1)), "oversized JSON rejected");
        JsonObject peer = JsonParser.parseString(valid).getAsJsonObject(); peer.remove("type");
        JsonArray peers = new JsonArray(); for (int i = 0; i < 65; i++) peers.add(peer);
        JsonObject welcome = new JsonObject(); welcome.addProperty("type", "welcome"); welcome.addProperty("protocol", 5); welcome.addProperty("identityVerified", true); welcome.add("peers", peers);
        check(rejected(welcome.toString()), "welcome cannot allocate more than 64 peer records");
        check(!RelayDecoder.decode(valid.replace("\"pitch\":0", "\"pitch\":0,\"unused\":\"payload\"" )).has("unused"), "unknown payload never retained in game queue");

        ClientConfig legacy = ClientConfig.parse("{\"enabled\":true,\"token\":\"unused\",\"roomKey\":\"old-group\",\"worldIsolation\":false}");
        check(legacy.privacyDecision.isEmpty(), "LRP-01 legacy enabled=true never creates consent");
        check(legacy.sameConnection(ClientConfig.parse("{\"enabled\":true}")), "LRP-04 legacy roomKey and token are ignored");
        UUID account = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String context = ConsentBinding.key("one.example", "world-a", account);
        String global = ConsentRules.generalKey(RoomIdentity.hash("test-policy"), account);
        ClientConfig flow = new ClientConfig();
        check(!ConsentRules.decided(flow.privacyDecision, global) && !flow.enabled, "first launch requires a decision and stays disabled");
        ConsentRules.record(flow, global, null, RoomIdentity.hash("test-policy"), false);
        check(ConsentRules.decided(flow.privacyDecision, global) && !flow.enabled, "launch refusal persists and does not prompt repeatedly");
        flow.enabled = true;
        check(!ConsentRules.maySend(flow, global, context, RoomIdentity.hash("test-policy")), "config enabled alone cannot bypass refusal");
        ConsentRules.record(flow, global, null, RoomIdentity.hash("test-policy"), true);
        check(ConsentRules.maySend(flow, global, context, RoomIdentity.hash("test-policy")), "startup consent allows later server join without second prompt");
        ConsentRules.record(flow, global, context, RoomIdentity.hash("test-policy"), true);
        check(ConsentRules.maySend(flow, global, context, RoomIdentity.hash("test-policy")), "explicit startup and server consent allow sending");
        check(!ConsentRules.maySend(flow, ConsentRules.generalKey(RoomIdentity.hash("test-policy"), new UUID(0,2)), context, RoomIdentity.hash("test-policy")), "startup consent cannot cross accounts");
        check(ConsentRules.maySend(flow, global, "other-world", RoomIdentity.hash("test-policy")), "world changes do not require another consent");
        flow.groupCode = "Another123";
        check(ConsentRules.maySend(flow, global, "changed-code", RoomIdentity.hash("test-policy")), "saving group code does not require another consent");
        ConsentRules.record(flow, global, context, RoomIdentity.hash("test-policy"), false);
        check(!flow.enabled, "refusal stops transmission and clears old per-world approvals");
        ConsentRules.record(flow, global, null, RoomIdentity.hash("test-policy"), true);
        check(ConsentRules.maySend(flow, global, context, RoomIdentity.hash("test-policy")), "explicit reacceptance resumes without second world screen");
        Path file = Files.createTempFile("lrp-config-bound-", ".json");
        try {
            PrivateFiles.write(file, "{}");
            check(Files.readString(file).equals("{}"), "private atomic config write succeeds");
            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            if (acl != null) { var owner = acl.getOwner(); check(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)), "config ACL contains only its owner"); }
            Files.writeString(file, " ".repeat(65537));
            boolean blocked = false; try { ClientConfig.readBounded(file); } catch (java.io.IOException expected) { blocked = true; }
            check(blocked, "LRP-09 config read stops at 64 KiB");
        } finally { Files.deleteIfExists(file); }

        AtomicLong clock = new AtomicLong(1);
        List<String> sent = new ArrayList<>(); List<CompletableFuture<Void>> futures = new ArrayList<>();
        Queue<Runnable> callbacks = new ArrayDeque<>(); List<Throwable> errors = new ArrayList<>();
        LatestSendQueue queue = new LatestSendQueue(text -> {
            sent.add(text); var future = new CompletableFuture<Void>(); futures.add(future); return future;
        }, callbacks::add, errors::add, clock::get);
        queue.offer("join");
        for (int i = 0; i < 10000; i++) queue.offer("pose-" + i);
        check(sent.equals(List.of("join")) && callbacks.isEmpty(), "LRP-06 blocked send never starts a second send or future chain");
        futures.getFirst().complete(null); callbacks.remove().run();
        check(sent.equals(List.of("join", "pose-9999")), "only latest pending pose survives 10000 queued updates");
        clock.addAndGet(Duration.ofSeconds(11).toNanos()); check(queue.timedOut(), "blocked send has a 10-second deadline");
        queue.offer("discard-me"); queue.close(); futures.getLast().complete(null); callbacks.remove().run();
        check(sent.size() == 2 && errors.isEmpty(), "closed attempt cannot send stale buffered pose");
        ReceiveBudget receive = new ReceiveBudget(clock::get);
        for (int i = 0; i < 2048; i++) if (!receive.allow(1, true)) throw new AssertionError("receive burst too small");
        check(!receive.allow(1, true), "LRP-02 message burst is bounded");
        clock.addAndGet(Duration.ofSeconds(1).toNanos()); check(receive.allow(1000, true), "normal traffic resumes as token bucket refills");
        ReceiveBudget bytes = new ReceiveBudget(clock::get);
        check(bytes.allow(4_194_304, false) && !bytes.allow(1, false), "aggregate character budget cannot be bypassed by fragmented messages");

        ReplayProfiles profiles = new ReplayProfiles(); List<UUID> removed = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            profiles.addedSynthetic(new UUID(0, i)); profiles.retain(Set.of(), removed::add);
        }
        check(profiles.size() == 0 && removed.size() == 10000, "LRP-05 UUID churn removes every synthetic profile instead of retaining history");
        RecordingBudget recording = new RecordingBudget(clock::get, 3);
        check(recording.packet() && recording.packet() && recording.packet() && !recording.packet(), "per-recording packet ceiling bounds added replay data");
        for (int i = 0; i < 256; i++) if (!recording.spawn()) throw new AssertionError("spawn budget too small");
        check(!recording.spawn(), "rapid UUID/avatar churn is bounded");
        System.out.println("Security checks completed: " + checks + "; local-only, no game rendering.");
    }
}
