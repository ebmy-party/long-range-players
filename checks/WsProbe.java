import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Loads the actual released transport, binds ONLY 127.0.0.1 and uses synthetic test data. */
public final class WsProbe {
    static byte[] readFrame(InputStream in) throws IOException {
        int opcode = in.read(), length = in.read();
        if (opcode < 0 || length < 0) throw new EOFException();
        boolean masked = (length & 128) != 0; length &= 127;
        if (length == 126) length = in.read() * 256 + in.read();
        if (length == 127 || length > 4096) throw new IOException("Test frame limit");
        byte[] mask = masked ? in.readNBytes(4) : new byte[0], data = in.readNBytes(length);
        if (data.length != length || (masked && mask.length != 4)) throw new EOFException();
        if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
        return data;
    }
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]); boolean expected = Boolean.parseBoolean(args[1]);
        AtomicBoolean tcp = new AtomicBoolean(), plaintextJoin = new AtomicBoolean(), plaintextPose = new AtomicBoolean();
        List<String> statuses = new CopyOnWriteArrayList<>();
        AtomicReference<Socket> accepted = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, WsProbe.class.getClassLoader())) {
            listener.setSoTimeout(3500);
            Thread worker = Thread.startVirtualThread(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.set(socket); socket.setSoTimeout(3500); tcp.set(true);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    while (header.size() < 8192) { int b = in.read(); if (b < 0) return; header.write(b);
                        if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break; }
                    String key = header.toString(StandardCharsets.US_ASCII).lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                            .findFirst().orElseThrow().split(":",2)[1].trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                    String join = new String(readFrame(in), StandardCharsets.UTF_8);
                    plaintextJoin.set(join.contains("synthetic-test-only"));
                    byte[] welcome = "{\"type\":\"welcome\",\"protocol\":2,\"peers\":[]}".getBytes(StandardCharsets.UTF_8);
                    out.write(129); out.write(welcome.length); out.write(welcome); out.flush();
                    plaintextPose.set(new String(readFrame(in),StandardCharsets.UTF_8).contains("123.25"));
                } catch (Exception ignored) { }
            });
            Class<?> type = loader.loadClass("dev.codex.longrange.client.RelayTransport");
            var constructor = Arrays.stream(type.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 3
                    || (c.getParameterCount() == 4 && c.getParameterTypes()[3].getSimpleName().equals("IdentityProof")))
                    .findFirst().orElseThrow(); constructor.setAccessible(true);
            Consumer<Runnable> dispatch = Runnable::run; Consumer<JsonObject> receive = value -> {};
            BiConsumer<Object,String> status = (value, detail) -> statuses.add(value.toString());
            Object transport = constructor.getParameterCount() == 3 ? constructor.newInstance(dispatch,receive,status)
                    : constructor.newInstance(dispatch,receive,status,null);
            var connect = type.getDeclaredMethod("connect",String.class,JsonObject.class); connect.setAccessible(true);
            var disconnect = type.getDeclaredMethod("disconnect"); disconnect.setAccessible(true);
            try {
                JsonObject join = new JsonObject(); join.addProperty("type","join"); join.addProperty("token","synthetic-test-only");
                connect.invoke(transport,"ws://127.0.0.1:" + listener.getLocalPort() + "/ws",join);
                long deadline = System.nanoTime() + Duration.ofSeconds(expected ? 4 : 1).toNanos();
                while (System.nanoTime() < deadline && !statuses.contains("ONLINE")) Thread.sleep(10);
                if (statuses.contains("ONLINE")) {
                    JsonObject pose = new JsonObject(); pose.addProperty("type","pose"); pose.addProperty("x",123.25);
                    var send = type.getDeclaredMethod("send",JsonObject.class); send.setAccessible(true); send.invoke(transport,pose);
                    worker.join(2000);
                }
                if (expected != (tcp.get() && statuses.contains("ONLINE") && plaintextJoin.get() && plaintextPose.get())) throw new AssertionError("Unexpected old transport result");
                if (!expected && (tcp.get() || !statuses.contains("ERROR"))) throw new AssertionError("New transport attempted plaintext connection");
                System.out.println("JAR=" + jar.getFileName() + " SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
                System.out.println("tcp=" + tcp + " online=" + statuses.contains("ONLINE") + " plaintextSyntheticToken=" + plaintextJoin + " plaintextSyntheticCoordinate=" + plaintextPose);
            } finally { disconnect.invoke(transport); if (accepted.get() != null) accepted.get().close(); listener.close(); worker.join(1000); }
        }
    }
}
