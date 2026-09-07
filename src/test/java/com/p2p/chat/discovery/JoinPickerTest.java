package com.p2p.chat.discovery;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure join-picker: LAN + tailnet merging, labels, ports. */
class JoinPickerTest {

    @Test
    void mergesLanAndTailnetWithTags() throws Exception {
        DiscoveryRecord lan = new DiscoveryRecord("lan-host", "LL-0001",
                InetAddress.getByName("192.168.1.50"), 9000);
        List<TailscaleStatus.Peer> tail = List.of(
                new TailscaleStatus.Peer("desk.tail-d1234.ts.net", "desk", "100.64.0.5", true));

        List<JoinPicker.Option> options = JoinPicker.options(List.of(lan), tail, 8080);

        assertEquals(2, options.size(), "LAN + tailnet merged");
        assertTrue(options.get(0).label().startsWith("[LAN] "), "LAN option tagged");
        assertTrue(options.get(1).label().startsWith("[Tailscale] "), "tailnet option tagged");
        assertEquals(9000, options.get(0).port(), "LAN option keeps its advertised chat port");
        assertEquals(8080, options.get(1).port(), "tailnet option uses the default chat port");
        assertEquals("100.64.0.5", options.get(1).host(), "tailnet joins via tailnet IPv4");
        assertEquals(options.get(0).toString(), options.get(0).label(), "toString shows the label");
    }
}