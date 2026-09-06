package com.p2p.chat.discovery;

import com.p2p.chat.discovery.TailscaleStatus.Self;
import com.p2p.chat.discovery.TailscaleStatus.Status;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for parsing {@code tailscale status --json}: MagicDNS handling,
 * the online-peer filter, and garbage tolerance.
 */
class TailscaleStatusTest {

    private static final String SAMPLE = "{"
            + "\"BackendState\":\"Running\","
            + "\"Self\":{\"DNSName\":\"air.local.tail-d1234.ts.net.\",\"HostName\":\"air\","
            + "\"TailscaleIPs\":[\"100.101.102.103\",\"fd7a:115c::103\"],\"Online\":true},"
            + "\"Peer\":{"
            + "\"p1\":{\"DNSName\":\"desk.tail-d1234.ts.net.\",\"HostName\":\"desk\","
            + "\"TailscaleIPs\":[\"100.64.0.5\",\"fd7a:115c::5\"],\"Online\":true},"
            + "\"p2\":{\"DNSName\":\"phone.tail-d1234.ts.net.\",\"HostName\":\"phone\","
            + "\"TailscaleIPs\":[\"100.64.0.6\"],\"Online\":false},"
            + "\"p3\":{\"DNSName\":\"gaming.tail-d1234.ts.net.\",\"HostName\":\"gaming\","
            + "\"TailscaleIPs\":[\"100.64.0.7\"],\"Online\":true}"
            + "}}";

    @Test
    void parsesSelfAndFiltersOfflinePeers() {
        Status st = TailscaleStatus.fromJson(SAMPLE);
        Self self = st.self();
        assertNotNull(self, "Self present");
        assertEquals("air.local.tail-d1234.ts.net", self.dnsName(), "trailing dot stripped from MagicDNS");
        assertEquals("100.101.102.103", self.ipv4(), "IPv4 preferred over IPv6");
        assertEquals(2, st.peers().size(), "offline peer excluded");
        assertTrue(st.peers().stream().anyMatch(p -> p.dnsName().equals("desk.tail-d1234.ts.net")),
                "online peer kept with stripped name");
        assertTrue(st.peers().stream().anyMatch(p -> p.ipv4().equals("100.64.0.7")), "peer carries tailnet IPv4");
        assertTrue(st.peers().stream().allMatch(p -> p.ipv4() != null && !p.ipv4().isEmpty()),
                "every kept peer has an IPv4 to connect to");
    }

    @Test
    void garbageYieldsNone() {
        assertNull(TailscaleStatus.fromJson("not json at all").self(), "garbage -> NONE");
        assertTrue(TailscaleStatus.fromJson("not json at all").peers().isEmpty());
        assertNull(TailscaleStatus.fromJson(null).self());
        assertNull(TailscaleStatus.fromJson("").self());
    }
}