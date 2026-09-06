package com.p2p.chat.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A background thread that periodically broadcasts this host's presence onto
 * the discovery multicast group, so peers on the same network can find it
 * without being told an IP.
 *
 * <p>Beacons are sent out of <em>every</em> multicast-capable interface (one
 * {@link DatagramChannel} each, pinned with {@code IP_MULTICAST_IF}), which
 * fixes macOS hosts whose default egress would otherwise land on a virtual
 * Tailscale/utun device. A failed send drops just that interface.
 *
 * <p>Best-effort by design: if the network cannot carry multicast the sender
 * simply stays quiet and manual IP joining (or Tailscale) still works.
 */
public final class DiscoveryAnnouncer implements Runnable, AutoCloseable {
    private final InetAddress group;
    private final int port;
    private final byte[] beacon;
    private final long intervalMs;
    private final String forcedInterface;
    private final Consumer<String> status;
    private final InetSocketAddress target;
    private final Map<String, DatagramChannel> channels = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private volatile String activeIface = "";
    private Thread thread;

    public static DiscoveryAnnouncer forPort(String username, String deviceId, int tcpPort) {
        return new DiscoveryAnnouncer(username, deviceId, tcpPort);
    }

    public DiscoveryAnnouncer(String username, String deviceId, int tcpPort) {
        this(username, deviceId, tcpPort, Discovery.DEFAULT_PORT, 2_000L);
    }

    public DiscoveryAnnouncer(String username, String deviceId, int tcpPort, int discoveryPort, long intervalMs) {
        this(username, deviceId, tcpPort, discoveryPort, intervalMs, "", null);
    }

    public DiscoveryAnnouncer(String username, String deviceId, int tcpPort, int discoveryPort,
                              long intervalMs, String forcedInterface, Consumer<String> status) {
        this.group = multicastGroup();
        this.port = discoveryPort;
        this.beacon = Discovery.encode(username, deviceId, tcpPort).getBytes(StandardCharsets.UTF_8);
        this.intervalMs = Math.max(100, intervalMs);
        this.forcedInterface = forcedInterface;
        this.status = status;
        this.target = new InetSocketAddress(group, port);
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
        List<NetworkInterface> ifaces = DiscoveryNetworks.candidates(forcedInterface);
        if (ifaces.isEmpty()) {
            notify("No multicast-capable interface on this host — peers won't see you automatically.");
            notify(" Tell a peer one of the JOIN lines above, or connect them over Tailscale.");
            return;
        }
        notify("Beaconing on " + Discovery.GROUP + ":" + port + " via "
                + ifaces.stream().map(DiscoveryAnnouncer::label).collect(Collectors.joining(", ")));
        boolean lastOk = true;
        while (running) {
            boolean any = false;
            for (NetworkInterface iface : ifaces) {
                if (!running) {
                    break;
                }
                if (send(iface)) {
                    activeIface = label(iface);
                    any = true;
                }
            }
            if (!any && lastOk && running) {
                notify("All beacon sends failed — multicast looks blocked; peers can still join with a manual IP.");
            }
            lastOk = any;
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean send(NetworkInterface iface) {
        try {
            DatagramChannel ch = channels.computeIfAbsent(iface.getName(), k -> sender(iface));
            ch.send(ByteBuffer.wrap(beacon), target);
            return true;
        } catch (IOException | UncheckedIOException e) {
            DatagramChannel closed = channels.remove(iface.getName());
            if (closed != null) {
                try {
                    closed.close();
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }

    private static DatagramChannel sender(NetworkInterface iface) {
        try {
            return DiscoverySocket.openSender(iface, 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bonus field reporters read to know which interface we are currently beaconing on. */
    public String activeInterface() {
        return activeIface;
    }

    @Override
    public void close() {
        running = false;
        for (DatagramChannel ch : channels.values()) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        }
        channels.clear();
    }

    private void notify(String msg) {
        if (status != null) {
            status.accept(msg);
        }
    }

    private static String label(NetworkInterface ni) {
        return ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
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