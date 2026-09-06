package com.p2p.chat.discovery;

import java.net.InetAddress;

/**
 * A chat host discovered on the local network.
 *
 * @param deviceId stable device identifier shown to users (also used for TOFU)
 * @param name     the display name the host chose at startup
 * @param address  the source address the beacon arrived from (what to connect to)
 * @param port     the TCP chat port to connect to
 */
public record DiscoveryRecord(String name, String deviceId, InetAddress address, int port) {

    @Override
    public String toString() {
        return name + "  (" + deviceId + ")  " + address.getHostAddress() + ":" + port;
    }
}