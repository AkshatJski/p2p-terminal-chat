package com.p2p.chat.discovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for LAN discovery: payload codec, and the scanner's receive +
 * de-dupe path (exercised by unicast on loopback, since that works on every
 * platform — the multicast group membership is a bonus when the network allows).
 */
class DiscoveryTest {

    @Test
    void encodeDecodeRoundTrip() {
        String[] f = Discovery.decode(Discovery.encode("alice", "ABCD-1234", 8080));
        assertNotNull(f);
        assertEquals("P2PC/1", f[0]);
        assertEquals("alice", f[1]);
        assertEquals("ABCD-1234", f[2]);
        assertEquals("8080", f[3]);
    }

    @Test
    void decodeRejectsGarbage() {
        assertNull(Discovery.decode(null));
        assertNull(Discovery.decode(""));
        assertNull(Discovery.decode("P2PC/2|alice|id|8080"), "wrong version");
        assertNull(Discovery.decode("OTHER|x|y|8080"), "other app on the group");
        assertNull(Discovery.decode("P2PC/1||id|8080"), "empty name");
        assertNull(Discovery.decode("P2PC/1|alice|id|70000"), "port out of range");
        assertNull(Discovery.decode("P2PC/1|alice|id|notaport"), "non-numeric port");
    }

    @Test
    void encodeScrubsUnsafeCharacters() {
        String[] f = Discovery.decode(Discovery.encode("al|ice", "abcdef12", 8080));
        assertNotNull(f);
        assertEquals("al_ice", f[1], "field separators are scrubbed so lines stay parseable");
        assertEquals("abcdef12", f[2]);
    }

    @Test
    void scanFindsUnicastBeacon() throws Exception {
        int port = 48317;
        String[] found = new String[1];
        Thread scanner = new Thread(() -> found[0] = scanPort(port, 2000));
        scanner.start();
        Thread.sleep(300);
        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] payload = Discovery.encode("bob", "EF56-7890", 7070)
                    .getBytes(StandardCharsets.UTF_8);
            sender.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getLoopbackAddress(), port));
        }
        scanner.join(4000);
        assertNotNull(found[0], "scanner should receive the beacon on loopback");
        assertTrue(found[0].contains("EF56-7890"), "host device ID appears");
        assertTrue(found[0].contains("bob"), "host name appears");
    }

    @Test
    void scanDedupesRepeatedBeacons() throws Exception {
        int port = 48318;
        String[] result = new String[1];
        Thread scanner = new Thread(() -> result[0] = scanPort(port, 1600));
        scanner.start();
        Thread.sleep(300);
        try (DatagramSocket sender = new DatagramSocket()) {
            InetAddress lo = InetAddress.getLoopbackAddress();
            byte[] a = Discovery.encode("carol", "AA11-BB22", 8080).getBytes(StandardCharsets.UTF_8);
            byte[] b = Discovery.encode("carol", "AA11-BB22", 8080).getBytes(StandardCharsets.UTF_8);
            sender.send(new DatagramPacket(a, a.length, lo, port));
            Thread.sleep(100);
            sender.send(new DatagramPacket(b, b.length, lo, port));
        }
        scanner.join(4000);
        assertNotNull(result[0], "scanner should return the host once");
        assertTrue(result[0].endsWith("cs1"), "same host announced twice still lists once: " + result[0]);
    }

    private static String scanPort(int port, int windowMs) {
        List<DiscoveryRecord> records = DiscoveryScanner.scan(port, windowMs);
        if (records.isEmpty()) {
            return null;
        }
        DiscoveryRecord r = records.get(0);
        return r.name() + "cs" + r.deviceId() + "cs" + r.port() + "cs"
                + r.address().getHostAddress() + "cs" + records.size();
    }
}