package com.p2p.chat.mesh;

import com.p2p.chat.config.Config;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.core.Prompt;
import com.p2p.chat.core.TrustGate;
import com.p2p.chat.protocol.Protocol;
import com.p2p.chat.transport.SerialTransport;
import com.p2p.chat.transport.SocketTransport;
import com.p2p.chat.transport.Transport;
import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A store-and-forward mesh node.
 *
 * <p>Unlike the star-shaped host/room model, a mesh connects every node to any
 * number of neighbors and routes messages hop by hop (flooding with a TTL and
 * message-id deduplication). It is transport-agnostic, so it runs over TCP,
 * over Bluetooth RFCOMM serial, or over in-process pipes for testing - which is
 * exactly what lets chat keep working when there is no WiFi.
 *
 * <p>Rooms act as broadcast filters: a message floods the whole mesh and every
 * node currently in that room renders it; everyone else forwards it silently.
 */
public final class MeshNode implements Closeable {
    private static final int SEEN_CAP = 20_000;

    private final String username;
    private final Identity identity;
    private final TrustGate trustGate;
    private final Map<String, MeshLink> links = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    private volatile String currentRoom;
    private volatile boolean running = true;
    private final AtomicBoolean closed = new AtomicBoolean();
    private ServerSocket serverSocket;

    public MeshNode(String username, Identity identity, TrustGate trustGate) {
        this.username = username;
        this.identity = identity;
        this.trustGate = trustGate;
    }

    public boolean isRunning() {
        return running;
    }

    public String username() {
        return username;
    }

    /** Accepts TCP links on the given port (auto-trusts new peers, like a host). */
    public void listen(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        Thread accept = new Thread(this::acceptLoop, "mesh-accept");
        accept.setDaemon(true);
        accept.start();
        System.out.println("[Mesh] Listening for links on port " + port + ".");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                String endpoint = socket.getInetAddress().getHostAddress();
                System.out.println("[Mesh] Incoming link from " + socket.getRemoteSocketAddress());
                SecureChannel channel = new SecureChannel(new SocketTransport(socket), identity);
                if (!trustGate.verifyHost(channel, endpoint)) {
                    channel.close();
                    continue;
                }
                attach(channel, endpoint);
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Mesh] Accept error: " + e.getMessage());
                }
            }
        }
    }

    /** Dials a TCP peer and verifies its fingerprint interactively. */
    public boolean connectTo(String host, int port, Prompt prompt) throws IOException {
        String endpoint = "mesh://" + host + ":" + port;
        return connectTransport(new SocketTransport(new Socket(host, port)), endpoint, prompt);
    }

    /** Opens a Bluetooth RFCOMM / serial device as a link. */
    public boolean connectSerial(String portName, Prompt prompt) throws IOException {
        return connectTransport(new SerialTransport(portName), "serial:" + portName, prompt);
    }

    /** Attaches an already-open transport (used by tests and by serial acceptors). */
    public boolean connectTransport(Transport transport, String endpoint, Prompt prompt) throws IOException {
        SecureChannel channel = new SecureChannel(transport, identity);
        if (!trustGate.verifyClient(channel, endpoint, prompt)) {
            channel.close();
            return false;
        }
        attach(channel, endpoint);
        return true;
    }

    private void attach(SecureChannel channel, String endpoint) {
        MeshLink link = new MeshLink(channel, endpoint);
        links.put(endpoint, link);
        System.out.println("[Mesh] Link to " + endpoint + " established.");
        System.out.println("[Mesh]   fingerprint: " + channel.getFingerprint());
        Thread reader = new Thread(() -> linkLoop(link), "mesh-link-" + endpoint);
        reader.setDaemon(true);
        reader.start();
    }

    private void linkLoop(MeshLink link) {
        try {
            while (running) {
                String line = link.channel.receive();
                if (line == null) {
                    break;
                }
                route(link, line);
            }
        } catch (Exception e) {
            if (running) {
                System.out.println("[Mesh] Link " + link.id + " lost: " + e.getMessage());
            }
        } finally {
            links.remove(link.id, link);
            link.channel.close();
        }
    }

    private void route(MeshLink from, String line) {
        String[] f = line.split(String.valueOf(Protocol.SEP), 6); // MESH id ttl origin room text
        if (f.length < 6 || !f[0].equals("MESH")) {
            return;
        }
        String msgId = f[1];
        int ttl;
        try {
            ttl = Integer.parseInt(f[2]);
        } catch (NumberFormatException e) {
            return;
        }
        if (seen.putIfAbsent(msgId, Boolean.TRUE) != null) {
            return; // already seen - break floods
        }
        if (seen.size() > SEEN_CAP) {
            seen.clear();
        }
        String origin = f[3];
        String room = f[4];
        String text = f[5];
        if (room.equals(currentRoom)) {
            System.out.println("[Room " + room + "] " + origin + ": " + text);
        }
        if (ttl <= 1) {
            return;
        }
        String next = Protocol.command("MESH", msgId, String.valueOf(ttl - 1), origin, room, text);
        for (MeshLink link : links.values()) {
            if (link != from) {
                link.send(next);
            }
        }
    }

    public void sendToRoom(String text) {
        if (currentRoom == null) {
            System.out.println("[Mesh] Join a room first: @join <room>");
            return;
        }
        String msgId = UUID.randomUUID().toString();
        seen.put(msgId, Boolean.TRUE);
        System.out.println("[Room " + currentRoom + "] " + username + ": " + text);
        String line = Protocol.command("MESH", msgId, String.valueOf(Config.get().getMeshTtl()), username, currentRoom, text);
        for (MeshLink link : links.values()) {
            link.send(line);
        }
    }

    public void handleUserInput(String line) {
        String cmd = line.trim();
        if (cmd.isEmpty()) {
            return;
        }
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0].toLowerCase()) {
            case "@join" -> {
                if (parts.length < 2) {
                    System.out.println("[Usage] @join <room>");
                    return;
                }
                currentRoom = parts[1].trim();
                System.out.println("[Mesh] You are now in room " + currentRoom);
            }
            case "@leave" -> {
                currentRoom = null;
                System.out.println("[Mesh] You left the room.");
            }
            case "@links" -> {
                if (links.isEmpty()) {
                    System.out.println("[Mesh] No links yet.");
                } else {
                    links.keySet().forEach(l -> System.out.println("[Mesh]   " + l));
                }
            }
            case "@help" -> printHelp();
            default -> sendToRoom(cmd);
        }
    }

    private void printHelp() {
        System.out.println("""
                Commands:
                  @join <room>   enter a room (messages flood the mesh, only room members see them)
                  @leave         leave the current room
                  @links         list active neighbor links
                  @exit          shut down this node
                  <text>         send a message to the current room""");
    }

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (MeshLink link : links.values()) {
            link.channel.close();
        }
        links.clear();
        System.out.println("[Mesh] Node shut down.");
    }
}
