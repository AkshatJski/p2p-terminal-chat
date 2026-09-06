package com.p2p.chat.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Chooses which network interfaces are usable for multicast discovery.
 *
 * <p>On macOS, plain multicast sockets let the OS pick the egress interface
 * from the routing table — which is frequently a Tailscale/virtual {@code utun}
 * and NOT the Wi-Fi/Ethernet a LAN peer can reach. Exposing the candidate
 * interfaces up front (and later setting {@code IP_MULTICAST_IF} on each one)
 * lets the announcer and the scanner handle multi-NIC machines deterministically.
 */
public final class DiscoveryNetworks {
    /** Interface name/display patterns that are virtually never the LAN a peer can reach us on. */
    private static final String[] SKIP = {
            "utun", "tun", "tap", "awdl", "anpi", "tailscale", "bridge",
            "vmnet", "vbox", "vethernet", "hyper-v", "loopback", "docker",
            "veth", "virbr", "ppp", "gif", "stf", "lo"
    };

    private DiscoveryNetworks() {
    }

    /**
     * Multicast-capable candidate interfaces, most-LAN-ish first.
     *
     * @param forcedName an interface name (e.g. {@code en0}) or display name
     *                   (e.g. {@code Wi-Fi}); when set only that interface
     *                   qualifies, empty if it does not exist
     */
    public static List<NetworkInterface> candidates(String forcedName) {
        boolean forced = forcedName != null && !forcedName.isBlank();
        List<NetworkInterface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) {
                    continue;
                }
                if (forced) {
                    if (matches(forcedName.trim(), ni)) {
                        out.add(ni);
                    }
                } else if (!skip(ni) && hasIpv4(ni)) {
                    out.add(ni);
                }
            }
        } catch (SocketException e) {
            return out;
        }
        out.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        return out;
    }

    private static boolean skip(NetworkInterface ni) {
        String label = (name(ni) + "|" + display(ni)).toLowerCase(Locale.ROOT);
        for (String s : SKIP) {
            if (label.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String want, NetworkInterface ni) {
        return name(ni).equalsIgnoreCase(want) || display(ni).equalsIgnoreCase(want);
    }

    private static boolean hasIpv4(NetworkInterface ni) {
        try {
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Interface disappeared while enumerating; treat as unusable.
        }
        return false;
    }

    /** Rough LAN likelihood so en0/eth0/Wi-Fi come before anything else. */
    private static int rank(NetworkInterface ni) {
        String n = name(ni).toLowerCase(Locale.ROOT);
        String d = display(ni).toLowerCase(Locale.ROOT);
        int score = 0;
        if (n.startsWith("en")) {
            score += 8;
        }
        if (n.startsWith("eth") || d.contains("ethernet")) {
            score += 6;
        }
        if (n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("wl") || d.contains("wi-fi")) {
            score += 5;
        }
        return score;
    }

    private static String name(NetworkInterface ni) {
        return ni.getName() == null ? "" : ni.getName();
    }

    private static String display(NetworkInterface ni) {
        return ni.getDisplayName() == null ? "" : ni.getDisplayName();
    }
}