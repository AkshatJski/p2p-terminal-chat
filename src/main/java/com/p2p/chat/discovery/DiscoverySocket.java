package com.p2p.chat.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.util.ArrayList;
import java.util.List;

/**
 * NIO {@link DatagramChannel} sockets for the beacon senders and scanners.
 *
 * <p>Plain {@code MulticastSocket} lets the OS choose the egress interface for
 * multicast, which on macOS happily routes onto a Tailscale/virtual device.
 * These helpers pin the interface explicitly — senders via
 * {@code IP_MULTICAST_IF}, receivers by joining the group <em>on</em> each
 * interface — so the choice is per-interface instead of whatever the routing
 * table says.
 */
public final class DiscoverySocket {
    private DiscoverySocket() {
    }

    /** A datagram socket that multicasts out of {@code iface}. Not bound; the OS picks a source. */
    public static DatagramChannel openSender(NetworkInterface iface, int ttl) throws IOException {
        DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
        try {
            ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface);
            ch.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
            ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return ch;
    }

    /** Joins {@code group} on every interface, returning the memberships that succeeded. */
    public static List<MembershipKey> joinGroup(DatagramChannel ch, InetAddress group, List<NetworkInterface> ifaces) {
        List<MembershipKey> keys = new ArrayList<>();
        for (NetworkInterface iface : ifaces) {
            try {
                keys.add(ch.join(group, iface));
            } catch (IOException ignored) {
                // That interface cannot carry multicast yet — keep trying the others.
            }
        }
        return keys;
    }
}