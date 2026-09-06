package com.p2p.chat.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the numbered list the join prompt shows, merging LAN discoveries and
 * online Tailscale peers into a single labelled picker. Pure function — no I/O.
 */
public final class JoinPicker {
    /** A join target with a human label for the picker. */
    public record Option(String label, String host, int port) {
        @Override
        public String toString() {
            return label;
        }
    }

    private JoinPicker() {
    }

    /**
     * @param lan      hosts found by the LAN multicast scan (may be empty)
     * @param tail     online tailnet peers that already answered on the chat port (may be empty)
     * @param tailPort default chat port to use for tailnet peers (they don't advertise one)
     */
    public static List<Option> options(List<DiscoveryRecord> lan, List<TailscaleStatus.Peer> tail, int tailPort) {
        List<Option> out = new ArrayList<>();
        for (DiscoveryRecord r : lan) {
            out.add(new Option("[LAN] " + r.name() + "  (" + r.deviceId() + ")  "
                    + r.address().getHostAddress() + ":" + r.port(),
                    r.address().getHostAddress(), r.port()));
        }
        for (TailscaleStatus.Peer p : tail) {
            String who = p.dnsName() != null && !p.dnsName().isEmpty() ? p.dnsName() : p.hostName();
            out.add(new Option("[Tailscale] " + who + "  (" + p.ipv4() + ")", p.ipv4(), tailPort));
        }
        return out;
    }
}