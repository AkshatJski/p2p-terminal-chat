package com.p2p.chat.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A one-shot LAN scan for chat hosts. Listens on the discovery multicast group
 * for a short window and returns the hosts that answered, de-duplicated by
 * source address + chat port.
 *
 * <p>Because the socket also receives plain unicast datagrams on the discovery
 * port, the receive/parse path is testable without any multicast support.
 */
public final class DiscoveryScanner {
    private DiscoveryScanner() {
    }

    /** Scans for {@code windowMs} and returns discovered hosts (possibly empty). */
    public static List<DiscoveryRecord> scan(int windowMs) {
        return scan(Discovery.DEFAULT_PORT, windowMs);
    }

    /** Scans on a specific discovery port for {@code windowMs} milliseconds. */
    public static List<DiscoveryRecord> scan(int discoveryPort, int windowMs) {
        Map<String, DiscoveryRecord> found = new LinkedHashMap<>();
        try (MulticastSocket socket = new MulticastSocket(discoveryPort)) {
            socket.setReuseAddress(true);
            try {
                socket.joinGroup(InetAddress.getByName(Discovery.GROUP));
            } catch (IOException ignored) {
                // No multicast route on this host; unicast beacons (and manual entry) still work.
            }
            socket.setSoTimeout(windowMs);
            byte[] buf = new byte[512];
            long deadline = System.currentTimeMillis() + windowMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    break; // timeout (expected after windowMs)
                }
                String[] f = Discovery.decode(new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8).trim());
                if (f == null) {
                    continue;
                }
                InetAddress src = packet.getAddress();
                int chatPort = Integer.parseInt(f[3]);
                String key = src.getHostAddress() + ":" + chatPort;
                found.putIfAbsent(key, new DiscoveryRecord(f[1], f[2], src, chatPort));
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }
        return new ArrayList<>(found.values());
    }
}