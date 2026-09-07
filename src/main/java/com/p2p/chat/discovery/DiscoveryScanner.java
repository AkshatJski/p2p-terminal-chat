package com.p2p.chat.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
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
        return scanResult(discoveryPort, windowMs).hosts();
    }

    /** Scans on the default group/port for {@code windowMs} and reports joined interfaces too. */
    public static ScanResult scanResult(int discoveryPort, int windowMs) {
        return scanResult(Discovery.GROUP, discoveryPort, windowMs, "");
    }

    /** Scans a specific group/port for {@code windowMs}, joining every candidate interface. */
    public static ScanResult scanResult(String group, int discoveryPort, int windowMs, String forcedInterface) {
        Map<String, DiscoveryRecord> found = new LinkedHashMap<>();
        List<String> joined = new ArrayList<>();
        InetAddress groupAddr;
        try {
            groupAddr = InetAddress.getByName(group);
        } catch (java.net.UnknownHostException e) {
            return new ScanResult(List.of(), List.of());
        }
        try (DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET)) {
            // ReuseAddress BEFORE bind so two scan windows may overlap on the port.
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ch.bind(new InetSocketAddress(discoveryPort));
            List<NetworkInterface> ifaces = DiscoveryNetworks.candidates(forcedInterface);
            for (MembershipKey key : DiscoverySocket.joinGroup(ch, groupAddr, ifaces)) {
                joined.add(label(key.networkInterface()));
            }

            ch.configureBlocking(false);
            long deadline = System.currentTimeMillis() + Math.max(100, windowMs);
            ByteBuffer buf = ByteBuffer.allocate(512);
            try (Selector sel = Selector.open()) {
                ch.register(sel, SelectionKey.OP_READ);
                while (System.currentTimeMillis() < deadline) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    sel.select(remaining);
                    Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        if (!key.isReadable()) {
                            continue;
                        }
                        InetSocketAddress src;
                        while ((src = (InetSocketAddress) ch.receive(buf)) != null) {
                            buf.flip();
                            byte[] data = new byte[buf.remaining()];
                            buf.get(data);
                            buf.clear();
                            String[] f = Discovery.decode(new String(data, StandardCharsets.UTF_8).trim());
                            if (f == null) {
                                continue; // another app sharing the group
                            }
                            int chatPort = Integer.parseInt(f[3]);
                            String dedupe = src.getAddress().getHostAddress() + ":" + chatPort;
                            found.putIfAbsent(dedupe,
                                    new DiscoveryRecord(f[1], f[2], src.getAddress(), chatPort));
                        }
                    }
                }
            }
        } catch (IOException e) {
            return new ScanResult(List.of(), joined);
        }
        return new ScanResult(new ArrayList<>(found.values()), joined);
    }

    private static String label(NetworkInterface ni) {
        return ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
    }

    /**
     * What a scan saw. {@code joinedInterfaces} lists the interfaces the group
     * was actually joined on: when empty, either no multicast-capable interface
     * exists or the network drops multicast, so a quiet result does not mean
     * "no hosts" — it means "no beacon route".
     */
    public record ScanResult(List<DiscoveryRecord> hosts, List<String> joinedInterfaces) {
        /** True when the multicast group join succeeded on at least one interface. */
        public boolean multicastUsed() {
            return !joinedInterfaces.isEmpty();
        }
    }
}