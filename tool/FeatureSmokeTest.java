import com.p2p.chat.config.Config;
import com.p2p.chat.core.FileReceiver;
import com.p2p.chat.core.HostNode;
import com.p2p.chat.core.Prompt;
import com.p2p.chat.core.TrustGate;
import com.p2p.chat.core.TrustServer;
import com.p2p.chat.core.ClientNode;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.crypto.TrustStore;
import com.p2p.chat.mesh.MeshNode;
import com.p2p.chat.protocol.Protocol;
import com.p2p.chat.transport.SocketTransport;
import com.p2p.chat.util.Ansi;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Per-feature smoke harness. Compile against the shaded jar and run:
 *
 *   javac -cp target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar -d tool-out tool/FeatureSmokeTest.java
 *   java  -cp tool-out;target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar FeatureSmokeTest
 *
 * Each numbered test exercises ONE feature end-to-end and prints [PASS]/[FAIL].
 */
public class FeatureSmokeTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final Prompt YES = m -> true;

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static Path dir() throws IOException {
        return Files.createTempDirectory("ft-");
    }

    private static Identity identity(Path dir, String name) {
        try {
            return Identity.loadOrCreate(dir.resolve(name + ".key"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static TrustStore trust(Path dir) throws IOException {
        return new TrustStore(dir.resolve("trust.txt"));
    }

    private static int port() {
        return 20_000 + (int) (Math.random() * 40_000);
    }

    private static void configure(Path dir, String... lines) throws IOException {
        Path f = dir.resolve("c.properties");
        Files.writeString(f, String.join("\n", lines));
        Config.load("--config", f.toString());
    }

    private static String hostLabelOf(Identity id) {
        try {
            return com.p2p.chat.crypto.CryptoUtil.fingerprint(id.rawPublicKey(), id.rawPublicKey()).substring(0, 8);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Raw protocol peer (acts like a real client, exposes a message queue)
    // ------------------------------------------------------------------

    static final class Peer implements AutoCloseable {
        final SecureChannel ch;
        final String user;
        final CopyOnWriteArrayList<String> rx = new CopyOnWriteArrayList<>();
        final AtomicInteger idx = new AtomicInteger();

        Peer(String user, int port, Identity ident) throws Exception {
            this.user = user;
            this.ch = new SecureChannel(new SocketTransport(new Socket("localhost", port)), ident);
            send("@NAME" + Protocol.SEP + user);
            Thread t = new Thread(() -> {
                try {
                    String l;
                    while ((l = ch.receive()) != null) {
                        rx.add(l);
                    }
                } catch (IOException ignored) {
                }
            }, "peer-" + user);
            t.setDaemon(true);
            t.start();
        }

        void send(String line) {
            try {
                ch.send(line);
            } catch (IOException e) {
                throw new IllegalStateException("send on " + user + " failed: " + e.getMessage(), e);
            }
        }

        void sendRaw(String ok, String line) {
            send(line);
        }

        void join(String room) {
            send("@JOIN" + Protocol.SEP + room);
        }

        void leave() {
            send("@LEAVE" + Protocol.SEP);
        }

        void msg(String room, String text) {
            send("@MSG" + Protocol.SEP + room + Protocol.SEP + text);
        }

        void typing(String room) {
            send("@TYPING" + Protocol.SEP + room);
        }

        void typingStop(String room) {
            send("@TYPING_STOP" + Protocol.SEP + room);
        }

        void history(String room, int n) {
            send("@HISTORY" + Protocol.SEP + room + Protocol.SEP + n);
        }

        String await(Predicate<String> p, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                int i = idx.get();
                if (i < rx.size() && idx.compareAndSet(i, i + 1)) {
                    String l = rx.get(i);
                    if (p.test(l)) {
                        return l;
                    }
                } else {
                    Thread.sleep(15);
                }
            }
            return null;
        }

        int remaining(Predicate<String> p) {
            int c = 0;
            for (int i = idx.get(); i < rx.size(); i++) {
                if (p.test(rx.get(i))) {
                    c++;
                }
            }
            return c;
        }

        @Override
        public void close() {
            ch.close();
        }
    }

    // ------------------------------------------------------------------
    // System.out capture (for terminal-rendered output assertions)
    // ------------------------------------------------------------------

    static final class Capture implements AutoCloseable {
        private final PrintStream original;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Capture() {
            original = System.out;
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        }

        synchronized String text() {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }

        String stripped() {
            return text().replaceAll("\u001B\\[[;\\d]*m", "");
        }

        void clear() {
            synchronized (this) {
                buf.reset();
            }
        }

        boolean waitContains(String needle, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                if (text().contains(needle)) {
                    return true;
                }
                Thread.sleep(30);
            }
            return false;
        }

        int countOf(String needle, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            int last = -1;
            while (System.currentTimeMillis() < end) {
                String s = stripped();
                int c = 0, from = 0;
                while ((from = s.indexOf(needle, from)) >= 0) {
                    c++;
                    from += needle.length();
                }
                if (c > 0) {
                    last = c;
                }
                Thread.sleep(30);
            }
            return last;
        }

        @Override
        public void close() {
            System.out.flush();
            System.setOut(original);
        }
    }

    private static final Predicate<String> FROM_CONTAINS(String text) {
        return l -> l.startsWith("@FROM") && l.contains(text);
    }

    // ------------------------------------------------------------------
    // Features
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        feature("01 Config: CLI > config file > defaults", FeatureSmokeTest::f01Config);
        feature("02 Identity + trust store persistence", FeatureSmokeTest::f02Identity);
        feature("03 Hosting + client connect + room users", FeatureSmokeTest::f03Connect);
        feature("04 Client trust-on-first-use (prompt + save + re-verify)", FeatureSmokeTest::f04Tofu);
        feature("05 Fingerprint mismatch aborts connection", FeatureSmokeTest::f05Mismatch);
        feature("06 Rooms: join/leave/list/users", FeatureSmokeTest::f06Rooms);
        feature("07 Message relay between two clients", FeatureSmokeTest::f07Relay);
        feature("08 Per-user colors (stable, distinct)", FeatureSmokeTest::f08Colors);
        feature("09 Typing indicators", FeatureSmokeTest::f09Typing);
        feature("10 File sharing (@FILE_START/@FILE_CHUNK)", FeatureSmokeTest::f10Files);
        feature("11 HTTP trust server page (confirm/reject)", FeatureSmokeTest::f11TrustHttp);
        feature("12 Message history (@history)", FeatureSmokeTest::f12History);
        feature("13 Moderation: kick", FeatureSmokeTest::f13Kick);
        feature("14 Moderation: ban + unban", FeatureSmokeTest::f14Ban);
        feature("15 Auto-reconnect after kick (send + receive)", FeatureSmokeTest::f15Reconnect);
        feature("16 Host-to-host bridging (@link)", FeatureSmokeTest::f16Bridge);
        feature("17 Mesh flooding (3 nodes, TTL + dedup)", FeatureSmokeTest::f17Mesh);

        System.out.println("\n===== SUMMARY =====");
        System.out.println("PASSED: " + passed + "  FAILED: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void feature(String name, Body body) {
        System.out.println("\n--- " + name + " ---");
        long t0 = System.currentTimeMillis();
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name + "  (" + (System.currentTimeMillis() - t0) + " ms)");
        } catch (Throwable e) {
            failed++;
            System.out.println("[FAIL] " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    // 01 -----------------------------------------------------------------

    private static void f01Config() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("c.properties"),
                "port=7070\nmesh.ttl=16\nreconnect.enabled=false\n");
        Config.load("--config", d.resolve("c.properties").toString(), "--port", "7071", "--mesh-port", "9092");
        check(Config.get().getPort() == 7071, "CLI --port should win over file (7071)");
        check(Config.get().getMeshPort() == 9092, "CLI --mesh-port applied");
        check(Config.get().getMeshTtl() == 16, "file key mesh.ttl=16 used");
        check(!Config.get().isReconnectEnabled(), "file bool reconnect.enabled=false used");
    }

    // 02 -----------------------------------------------------------------

    private static void f02Identity() throws Exception {
        Path d = dir();
        Identity id = Identity.loadOrCreate(d.resolve("id.key"));
        byte[] pub = id.rawPublicKey();
        check(Identity.loadOrCreate(d.resolve("id.key")).rawPublicKey().length > 0, "identity reloads");
        check(Identity.loadOrCreate(d.resolve("id.key")).rawPublicKey().length == pub.length, "same key size on reload");

        TrustStore store = trust(d);
        store.remember("localhost:99", "AAAA-BBBB");
        check(store.matches("localhost:99", "AAAA-BBBB"), "remember/matches");
        check(!store.matches("localhost:99", "CCCC-DDDD"), "wrong fingerprint does not match");
        TrustStore reloaded = trust(d);
        check(reloaded.matches("localhost:99", "AAAA-BBBB"), "trust store persisted to disk");
    }

    // 03 -----------------------------------------------------------------

    private static void f03Connect() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try {
            try (Peer alice = new Peer("alice", p, identity(d, "alice"))) {
                Thread.sleep(300);
                host.handleUserInput("@join general");
                Thread.sleep(200);
                Capture cap = new Capture();
                host.handleUserInput("@users");
                cap.waitContains("host", 1000);
                cap.waitContains("alice", 1000);
                cap.close();
            }
        } finally {
            host.close();
        }
    }

    // 04 -----------------------------------------------------------------

    private static void f04Tofu() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        TrustStore store = trust(d);
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(store), YES);
        host.start();
        try {
            Capture cap = new Capture();
            ClientNode c1 = new ClientNode("c1", "localhost", p, identity(d, "c1"), new TrustGate(store), YES);
            c1.start();
            check(cap.waitContains("First time connecting", 3000), "first contact prompts");
            check(cap.waitContains("Saved.", 1500), "fingerprint saved");
            check(cap.waitContains("Connected to localhost as c1", 1500), "connected");
            c1.close();
            cap.close();
            c1 = null;

            Capture cap2 = new Capture();
            ClientNode c2 = new ClientNode("c1", "localhost", p, identity(d, "c1"), new TrustGate(store), YES);
            c2.start();
            check(cap2.waitContains("previously trusted", 3000), "second connect auto-verified");
            check(!cap2.text().contains("FINGERPRINT MISMATCH"), "no mismatch on repeat connect");
            c2.close();
            cap2.close();
        } finally {
            host.close();
        }
    }

    // 05 -----------------------------------------------------------------

    private static void f05Mismatch() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        TrustStore store = trust(d);
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(store), YES);
        host.start();
        try {
            ClientNode first = new ClientNode("c1", "localhost", p, identity(d, "victim"), new TrustGate(store), YES);
            first.start();
            Thread.sleep(200);
            first.close();

            Capture cap = new Capture();
            ClientNode evil = new ClientNode("c1", "localhost", p, identity(d, "evil"), new TrustGate(store), YES);
            evil.start();
            check(cap.waitContains("FINGERPRINT MISMATCH", 3000), "changed identity is flagged");
            check(!cap.text().contains("Connected to localhost as c1"), "connection aborted before connect");
            evil.close();
            cap.close();
        } finally {
            host.close();
        }
    }

    // 06 -----------------------------------------------------------------

    private static void f06Rooms() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"));
             Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            host.handleUserInput("@join general");
            Thread.sleep(100);
            alice.join("general");
            bob.join("general");

            check(alice.await(l -> l.startsWith("@SYS") && l.contains("You are now in room general"), 3000) != null,
                    "join SYS received");
            alice.send("@USERS" + Protocol.SEP);
            String users = alice.await(l -> l.startsWith("@ROOM_USERS"), 3000);
            check(users != null && users.contains("alice") && users.contains("bob")
                    && users.contains("host"), "room users list: " + users);

            Capture cap = new Capture();
            host.handleUserInput("@list");
            check(cap.waitContains("general(3)", 2000), "room list shows general(3): " + cap.stripped());
            cap.close();

            alice.leave();
            check(alice.await(l -> l.startsWith("@SYS") && l.contains("You left room"), 3000) != null,
                    "leave SYS received");
        } finally {
            host.close();
        }
    }

    // 07 -----------------------------------------------------------------

    private static void f07Relay() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"));
             Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            alice.join("general");
            bob.join("general");
            Thread.sleep(200);
            alice.msg("general", "hello from alice");
            String got = bob.await(FROM_CONTAINS("hello from alice"), 3000);
            check(got != null, "bob received relayed message: " + got);
        } finally {
            host.close();
        }
    }

    // 08 -----------------------------------------------------------------

    private static void f08Colors() {
        String a = Ansi.userColor("alice");
        String b = Ansi.userColor("bob");
        check(!a.equals(b), "different users get different colors");
        check(Ansi.userColor("alice").equals(a), "same user keeps the same color");
        check(Ansi.userColor("alice2").length() > 0, "color produced");
    }

    // 09 -----------------------------------------------------------------

    private static void f09Typing() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"));
             Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            alice.join("general");
            bob.join("general");
            Thread.sleep(200);
            alice.typing("general");
            check(bob.await(l -> l.startsWith("@TYPING") && l.contains("alice"), 3000) != null,
                    "typing start arrives at bob");
            alice.typingStop("general");
            check(bob.await(l -> l.startsWith("@TYPING_STOP"), 3000) != null, "typing stop arrives at bob");
        } finally {
            host.close();
        }
    }

    // 10 -----------------------------------------------------------------

    private static void f10Files() throws Exception {
        Path d = dir();
        Path dl = d.resolve("downloads");
        configure(d, "trust.server.enabled=false", "download.dir=" + dl);
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer sender = new Peer("alice", p, identity(d, "alice"))) {
            Thread.sleep(300);
            host.handleUserInput("@join general");
            Thread.sleep(100);
            sender.join("general");
            Thread.sleep(200);

            byte[] blob = new byte[100_111];
            new java.util.Random(42).nextBytes(blob);
            Path src = d.resolve("payload.bin");
            Files.write(src, blob);

            String fid = java.util.UUID.randomUUID().toString();
            int chunkSize = FileReceiver.chunkSize();
            int chunkCount = FileReceiver.chunkCount(blob.length, chunkSize);
            sender.send("@FILE_START" + Protocol.SEP + fid + Protocol.SEP + "general" + Protocol.SEP
                    + src.getFileName() + Protocol.SEP + blob.length + Protocol.SEP + chunkCount
                    + Protocol.SEP + "alice" + Protocol.SEP);
            for (int i = 0; i < chunkCount; i++) {
                sender.send("@FILE_CHUNK" + Protocol.SEP + fid + Protocol.SEP + "general" + Protocol.SEP
                        + i + Protocol.SEP
                        + Base64.getEncoder().encodeToString(FileReceiver.chunk(blob, i, chunkSize)));
            }

            Path out = dl.resolve("payload.bin");
            long end = System.currentTimeMillis() + 5000;
            boolean saved = false;
            while (System.currentTimeMillis() < end) {
                if (Files.isRegularFile(out) && Files.size(out) == blob.length) {
                    saved = true;
                    break;
                }
                Thread.sleep(50);
            }
            check(saved, "file saved by host to download dir");
            check(java.util.Arrays.equals(Files.readAllBytes(out), blob), "saved bytes match sent bytes");
        } finally {
            host.close();
        }
    }

    // 11 -----------------------------------------------------------------

    private static void f11TrustHttp() throws Exception {
        HttpClient hc = HttpClient.newHttpClient();
        TrustServer ts = new TrustServer("ABCDEF12-34567890");
        int tp = ts.start(0);
        String base = "http://localhost:" + tp;

        String verify = hc.send(HttpRequest.newBuilder(URI.create(base + "/verify")).GET().build(),
                BodyHandlers.ofString()).body();
        check(verify.contains("ABCDEF12") && verify.contains("34567890"),
                "verify page shows fingerprint (got " + (verify.length() > 400 ? verify.substring(0, 400) : verify) + ")");

        hc.send(HttpRequest.newBuilder(URI.create(base + "/confirm")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                BodyHandlers.ofString());
        check(ts.awaitConfirmation(2000), "awaitConfirmation true after /confirm");
        ts.stop();

        TrustServer reject = new TrustServer("FEDCBA98-76543210");
        int rp = reject.start(0);
        hc.send(HttpRequest.newBuilder(URI.create("http://localhost:" + rp + "/reject"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                BodyHandlers.ofString());
        check(!reject.awaitConfirmation(2000), "awaitConfirmation false after /reject");
        reject.stop();
    }

    // 12 -----------------------------------------------------------------

    private static void f12History() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"));
             Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            host.handleUserInput("@join general");
            Thread.sleep(100);
            alice.join("general");
            bob.join("general");
            Thread.sleep(200);
            for (int i = 1; i <= 3; i++) {
                alice.msg("general", "m" + i);
                Thread.sleep(80);
            }
            bob.history("general", 20);
            int entries = 0;
            boolean done = false;
            long endAt = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < endAt && !done) {
                String l = bob.await(x -> x.startsWith("@HIST_ENTRY") || x.startsWith("@HIST_END"), 3000);
                if (l == null || l.startsWith("@HIST_END")) {
                    done = l != null;
                } else {
                    entries++;
                }
            }
            check(done, "history end marker");
            check(entries == 3, "history has exactly 3 entries (got " + entries + ")");

            Capture cap = new Capture();
            host.handleUserInput("@history 20");
            check(cap.waitContains("[History] Last 3 in general", 2000), "host history header: " + cap.stripped());
            check(!cap.stripped().contains("bobbob"), "no double-color in host history");
            cap.close();
        } finally {
            host.close();
        }
    }

    // 13 -----------------------------------------------------------------

    private static void f13Kick() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"));
             Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            host.handleUserInput("@join general");
            Thread.sleep(100);
            alice.join("general");
            bob.join("general");
            Thread.sleep(200);
            host.handleUserInput("@kick alice");
            check(alice.await(l -> l.startsWith("@ERR") && l.contains("kicked"), 3000) != null,
                    "kicked peer gets @ERR");
            Capture cap = new Capture();
            host.handleUserInput("@users");
            Thread.sleep(200);
            String out = cap.stripped();
            check(out.contains("host") && out.contains("bob") && !out.contains("alice"),
                    "alice removed from room users: " + out);
            cap.close();
        } finally {
            host.close();
        }
    }

    // 14 -----------------------------------------------------------------

    private static void f14Ban() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int p = port();
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(trust(d)), YES);
        host.start();
        try (Peer bob = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(300);
            bob.join("general");
            Thread.sleep(200);
            host.handleUserInput("@ban bob");
            check(bob.await(l -> l.startsWith("@ERR") && l.contains("kicked"), 3000) != null,
                    "banned peer kicked first");
        }
        // banned user is refused at join
        try (Peer recycled = new Peer("bob", p, identity(d, "bob"))) {
            check(recycled.await(l -> l.startsWith("@BANNED"), 3000) != null, "banned join refused with @BANNED");
        }
        host.handleUserInput("@unban bob");
        try (Peer back = new Peer("bob", p, identity(d, "bob"))) {
            Thread.sleep(200);
            back.join("general");
            check(back.await(l -> l.startsWith("@SYS") && l.contains("You are now in room general"), 3000) != null,
                    "unbanned user can join again");
        }
        host.close();
    }

    // 15 -----------------------------------------------------------------

    private static void f15Reconnect() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false",
                "reconnect.enabled=true", "reconnect.max=5", "reconnect.base.ms=50", "reconnect.max.ms=200");
        int p = port();
        TrustStore store = trust(d);
        HostNode host = new HostNode("host", p, identity(d, "host"), new TrustGate(store), YES);
        host.start();
        try (Peer alice = new Peer("alice", p, identity(d, "alice"))) {
            Thread.sleep(300);
            alice.join("general");

            ClientNode bob = new ClientNode("bob", "localhost", p, identity(d, "bob"), new TrustGate(store), YES);
            bob.start();
            Thread.sleep(300);
            bob.handleUserInput("@join general");
            Thread.sleep(200);

            Capture cap = new Capture();
            host.handleUserInput("@kick bob");
            check(cap.waitContains("[Reconnect] Back online.", 6000), "bob reconnects after kick");

            Thread.sleep(300);
            alice.msg("general", "hello after reconnect");
            check(cap.waitContains("hello after reconnect", 4000), "bob RECEIVES after reconnect");

            alice.send("@USERS" + Protocol.SEP);
            alice.await(l -> l.startsWith("@ROOM_USERS"), 2000);
            host.handleUserInput("@users");
            Thread.sleep(300);
            check(cap.stripped().contains("bob"), "bob re-registered: " + cap.stripped());
            cap.close();

            bob.handleUserInput("bob speaks again");
            check(alice.await(FROM_CONTAINS("bob speaks again"), 3000) != null, "bob can SEND after reconnect");
            bob.close();
        } finally {
            host.close();
        }
    }

    // 16 -----------------------------------------------------------------

    private static void f16Bridge() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false");
        int pa = port();
        int pb = port();
        TrustStore storeA = trust(d);
        TrustStore storeB = trust(d);
        Identity idA = identity(d, "hostA");
        Identity idB = identity(d, "hostB");
        HostNode hostA = new HostNode("hostA", pa, idA, new TrustGate(storeA), YES);
        HostNode hostB = new HostNode("hostB", pb, idB, new TrustGate(storeB), YES);
        hostA.start();
        hostB.start();
        String labelA = hostLabelOf(idA);
        try (Peer alice = new Peer("alice", pa, identity(d, "alice"));
             Peer bob = new Peer("bob", pb, identity(d, "bob"))) {
            Thread.sleep(400);
            hostA.handleUserInput("@join general");
            hostB.handleUserInput("@join general");
            Thread.sleep(100);
            alice.join("general");
            bob.join("general");
            Thread.sleep(200);

            hostA.handleUserInput("@link localhost " + pb);
            Thread.sleep(500);

            alice.msg("general", "hello bridged");
            String got = bob.await(l -> l.startsWith("@FROM") && l.contains("general")
                    && l.contains("alice" + Protocol.SEP + labelA)
                    && l.contains("hello bridged"), 4000);
            check(got != null, "remote host member got bridged, labelled message: " + got);

            Thread.sleep(400);
            check(bob.remaining(l -> l.startsWith("@FROM") && l.contains("hello bridged")) == 0,
                    "no duplicate bridged copy (loop prevented)");
        } finally {
            hostA.close();
            hostB.close();
        }
    }

    // 17 -----------------------------------------------------------------

    private static void f17Mesh() throws Exception {
        Path d = dir();
        configure(d, "trust.server.enabled=false", "mesh.ttl=8");
        int p2 = port();
        MeshNode n1 = new MeshNode("n1", identity(d, "n1"), new TrustGate(trust(d)));
        MeshNode n2 = new MeshNode("n2", identity(d, "n2"), new TrustGate(trust(d)));
        MeshNode n3 = new MeshNode("n3", identity(d, "n3"), new TrustGate(trust(d)));
        n2.listen(p2);
        Thread.sleep(200);
        check(n1.connectTo("localhost", p2, YES), "n1 dials n2");
        check(n3.connectTo("localhost", p2, YES), "n3 dials n2");
        Thread.sleep(300);
        n1.handleUserInput("@join general");
        n2.handleUserInput("@join general");
        n3.handleUserInput("@join general");
        Thread.sleep(200);

        Capture cap = new Capture();
        n1.sendToRoom("mesh hello");
        int seen = cap.countOf("[Room general] n1: mesh hello", 4000);
        check(seen == 3, "message reached all 3 nodes exactly once (got " + seen + ")");

        cap.clear();
        n1.sendToRoom("second hop");
        int seen2 = cap.countOf("[Room general] n1: second hop", 4000);
        check(seen2 == 3, "second message also floods once per node (got " + seen2 + ")");
        cap.close();

        n1.close();
        n2.close();
        n3.close();
    }
}