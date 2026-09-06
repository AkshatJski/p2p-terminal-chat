package com.p2p.chat.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

/**
 * A background thread that periodically broadcasts this host's presence onto
 * the discovery multicast group, so peers on the same network can find it
 * without being told an IP.
 *
 * <p>Best-effort by design: if the network can't carry multicast the sender
 * simply stays quiet and manual IP joining still works.
 */
public final class DiscoveryAnnouncer implements Runnable, AutoCloseable {
    private final InetAddress group;
    private final int port;
    private final String beacon;
    private final long intervalMs;
    private volatile boolean running = true;
    private MulticastSocket socket;
    private Thread thread;

    public static DiscoveryAnnouncer forPort(String username, String deviceId, int tcpPort) {
        return new DiscoveryAnnouncer(username, deviceId, tcpPort);
    }

    public DiscoveryAnnouncer(String username, String deviceId, int tcpPort) {
        this(username, deviceId, tcpPort, Discovery.DEFAULT_PORT, 2_000L);
    }

    public DiscoveryAnnouncer(String username, String deviceId, int tcpPort, int discoveryPort, long intervalMs) {
        this.group = multicastGroup();
        this.port = discoveryPort;
        this.beacon = Discovery.encode(username, deviceId, tcpPort);
        this.intervalMs = Math.max(100, intervalMs);
    }

    /** Starts broadcasting in the background. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this, "discovery-announcer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try (MulticastSocket ms = new MulticastSocket()) {
            ms.setTimeToLive(1);
            this.socket = ms;
            byte[] bytes = beacon.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, group, port);
            while (running) {
                try {
                    ms.send(packet);
                } catch (IOException ignored) {
                    // Network dropped the packet (AP isolation, firewall, ...) — keep trying.
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            // Cannot open a multicast socket at all — discovery silently unavailable.
        }
    }

    @Override
    public void close() {
        running = false;
        MulticastSocket sock = socket;
        if (sock != null) {
            sock.close();
        }
    }

    private static InetAddress multicastGroup() {
        try {
            return InetAddress.getByName(Discovery.GROUP);
        } catch (java.net.UnknownHostException e) {
            // The literal string is a valid IPv4; this cannot realistically happen.
            throw new IllegalStateException(e);
        }
    }
}