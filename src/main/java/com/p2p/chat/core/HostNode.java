package com.p2p.chat.core;

import com.p2p.chat.config.Config;
import com.p2p.chat.crypto.CryptoUtil;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.protocol.Protocol;
import com.p2p.chat.transport.SocketTransport;
import com.p2p.chat.util.Ansi;
import com.p2p.chat.web.WebHost;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The room host. Accepts any number of peers, each over its own
 * {@link SecureChannel}, tracks usernames, and relays room messages (and files)
 * between members. Rooms live in memory on the host.
 *
 * <p>Two hosts can be <b>linked</b> with {@code @link &lt;host&gt; [port]} so the
 * same room name is shared across networks: a message sent in room "general" on
 * one host is bridged to every linked host that also hosts "general". Relayed
 * messages carry a rid so floods cannot loop, and senders on remote hosts are
 * shown as {@code user@hostlabel}.
 *
 * <p>The host's own console is represented by a "self" {@link Participant}
 * whose {@link Participant#send} prints to the terminal, so the relay code is
 * identical for remote peers and the local operator.
 */
public final class HostNode extends Node {
    private static final int SEEN_CAP = 50_000;

    private final int port;
    private final Identity identity;
    private final TrustGate trustGate;
    private final Prompt prompt;
    private final ConcurrentMap<String, Participant> byName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<Participant>> rooms = new ConcurrentHashMap<>();
    private final List<Participant> participants = new CopyOnWriteArrayList<>();
    private final Participant self;
    private final FileReceiver fileReceiver;
    private final String hostLabel;
    private final MessageHistory history = new MessageHistory();
    private final Set<String> bannedUsers = ConcurrentHashMap.newKeySet();

    // Host <-> Host bridging state.
    private final ConcurrentMap<String, HostLink> links = new ConcurrentHashMap<>();
    private final Set<String> seenRids = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private WebHost webHost;

    public HostNode(String username, int port, Identity identity, TrustGate trustGate, Prompt prompt)
            throws IOException, GeneralSecurityException {
        super(null, username);
        this.port = port;
        this.identity = identity;
        this.trustGate = trustGate;
        this.prompt = prompt;
        this.fileReceiver = new FileReceiver(Config.get().getDownloadDir());
        this.hostLabel = shortFingerprint(
                CryptoUtil.fingerprint(identity.rawPublicKey(), identity.rawPublicKey()));
        this.self = new Participant(null);
        self.setUsername(username);
        participants.add(self);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        acceptThread = new Thread(this::acceptLoop, "accept-loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] Hosting on port " + port + ". Waiting for peers..."));
        Config cfg = Config.get();
        if (cfg.isWebEnabled()) {
            try {
                webHost = new WebHost(cfg.getWebPort(), cfg.getWebHttpPort(), this);
                webHost.start();
            } catch (Exception e) {
                System.out.println(Ansi.color(Ansi.YELLOW, "[Web] Web chat UI disabled: " + e.getMessage()));
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] Incoming connection from " + socket.getRemoteSocketAddress()));
                Thread peer = new Thread(() -> serve(socket), "peer-accept");
                peer.setDaemon(true);
                peer.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Error] Accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        Participant peer = new Participant(null);
        String endpoint = socket.getInetAddress().getHostAddress();
        boolean wasPeer = false;
        try {
            SecureChannel channel = new SecureChannel(new SocketTransport(socket), identity);

            // Trust verification — try HTTP server first, fall back to y/n prompt
            if (!verifyWithTrustServerOrPrompt(channel, endpoint)) {
                channel.close();
                return;
            }

            String first = channel.receive();
            if (first == null) {
                channel.close();
                return;
            }
            if (first.equals(Protocol.BRIDGE) || first.startsWith(Protocol.BRIDGE + Protocol.SEP)) {
                acceptLink(channel, first);
                return;
            }
            if (!first.startsWith(Protocol.NAME + Protocol.SEP)) {
                channel.send(Protocol.command(Protocol.ERR, "First message must be @NAME"));
                channel.close();
                return;
            }
            peer = new Participant(channel);

            String name = first.substring(Protocol.NAME.length() + 1).trim();
            if (!Protocol.isValidUsername(name)) {
                channel.send(Protocol.command(Protocol.ERR,
                        "Username must be 1-" + Protocol.MAX_USERNAME_LEN + " chars, no spaces"));
                channel.close();
                return;
            }
            if (bannedUsers.contains(name)) {
                channel.send(Protocol.command(Protocol.BANNED, "You are banned from this host."));
                channel.close();
                return;
            }
            if (byName.putIfAbsent(name, peer) != null) {
                channel.send(Protocol.command(Protocol.ERR, "Username already taken: " + name));
                channel.close();
                return;
            }
            peer.setUsername(name);
            participants.add(peer);
            wasPeer = true;
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] " + name + " connected from " + channel.remote()));
            broadcastAll(Protocol.command(Protocol.SYS, name + " connected"));

            while (running) {
                String line = channel.receive();
                if (line == null) {
                    break;
                }
                handlePeerLine(peer, line);
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("[System] Peer lost: " + e.getMessage());
            }
        } finally {
            if (wasPeer) {
                byName.remove(peer.username(), peer);
                participants.remove(peer);
                leaveRoom(peer, false);
                broadcastAll(Protocol.command(Protocol.SYS, peer.username() + " disconnected"));
                peer.close();
            }
        }
    }

    /** Registers a browser peer (WebSocket) into the shared room namespace. Mirrors the
     * {@code @NAME} handshake for terminal clients; returns null if the name is taken or banned. */
    public Participant registerWebPeer(String name, java.util.function.Consumer<String> sink, Runnable closer) {
        if (!Protocol.isValidUsername(name) || bannedUsers.contains(name)) {
            return null;
        }
        Participant peer = new Participant(sink, closer);
        peer.setUsername(name);
        if (byName.putIfAbsent(name, peer) != null) {
            return null;
        }
        participants.add(peer);
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] " + name + " connected (web UI)"));
        broadcastAll(Protocol.command(Protocol.SYS, name + " connected"));
        return peer;
    }

    /** Unregisters a browser peer. Idempotent: the WebSocket close and a kick/ban both call it. */
    public void removeWebPeer(Participant peer) {
        if (peer == null) {
            return;
        }
        byName.remove(peer.username(), peer);
        boolean wasRegistered = participants.remove(peer);
        if (wasRegistered) {
            leaveRoom(peer, false);
            broadcastAll(Protocol.command(Protocol.SYS, peer.username() + " disconnected"));
        }
        peer.close();
    }

    /** Routes a protocol line received from a browser peer into the normal host relay. */
    public void routeWebLine(Participant peer, String line) {
        handlePeerLine(peer, line);
    }

    /**
     * Verifies an inbound peer. New peers are auto-trusted (host-side TOFU, as
     * before); the HTTP server is started in the background purely to surface a
     * verification URL for the peer, but NEVER blocks registration.
     */
    private boolean verifyWithTrustServerOrPrompt(SecureChannel channel, String endpoint) throws IOException {
        String fingerprint = channel.getFingerprint();
        Config config = Config.get();

        // Surface an informational verification URL (non-blocking) so the
        // joining peer can confirm the fingerprint in a browser if they wish.
        if (config.isTrustServerEnabled()) {
            try {
                TrustServer trustServer = new TrustServer(fingerprint);
                int trustPort = trustServer.start(config.getTrustServerPort());
                String hostAddr = getHostAddress();
                System.out.println(Ansi.color(Ansi.DIM,
                        "[Security] Verify at http://" + hostAddr + ":" + trustPort + "/verify"));
                Thread bg = new Thread(() -> {
                    try {
                        if (trustServer.awaitConfirmation(60_000) && running) {
                            trustGate.getStore().rememberFingerprint(fingerprint);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        trustServer.stop();
                    }
                }, "trust-server-" + trustPort);
                bg.setDaemon(true);
                bg.start();
            } catch (Exception e) {
                System.out.println(Ansi.color(Ansi.YELLOW,
                        "[Security] HTTP trust server unavailable (" + e.getMessage() + ")."));
            }
        }

        // Host-side TOFU: auto-trust new peers, refuse known peers with a
        // changed fingerprint. Never blocks registration.
        return trustGate.verifyHost(channel, endpoint);
    }

    private String getHostAddress() {
        try {
            var nets = java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                var ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "localhost";
    }

    private void handlePeerLine(Participant peer, String line) {
        String[] f = Protocol.split(line);
        switch (f[0]) {
            case Protocol.JOIN -> {
                String room = f.length > 1 ? f[1].trim() : "";
                if (!Protocol.isValidRoom(room)) {
                    peer.send(Protocol.command(Protocol.ERR, "Invalid room name"));
                    return;
                }
                joinRoom(peer, room);
            }
            case Protocol.LEAVE -> leaveRoom(peer, true);
            case Protocol.LIST -> peer.send(Protocol.command(Protocol.ROOMS, roomListCsv()));
            case Protocol.USERS -> {
                String room = peer.room();
                peer.send(Protocol.command(Protocol.ROOM_USERS, room, userListCsv(room)));
            }
            case Protocol.MSG -> {
                if (f.length < 3) {
                    peer.send(Protocol.command(Protocol.ERR, "Usage: @MSG<room><text>"));
                    return;
                }
                String room = f[1];
                if (room == null || !room.equals(peer.room())) {
                    peer.send(Protocol.command(Protocol.ERR, "You are not in room " + room));
                    return;
                }
                broadcast(room, peer, Protocol.command(Protocol.FROM, room, peer.username(), "", f[2]));
            }
            case Protocol.FILE_START, Protocol.FILE_CHUNK, Protocol.FILE_ABORT -> {
                if (f.length < 3 || !f[2].equals(peer.room())) {
                    peer.send(Protocol.command(Protocol.ERR, "You are not in room "
                            + (f.length > 2 ? f[2] : "")));
                    return;
                }
                broadcast(f[2], peer, line);
            }
            case Protocol.TYPING -> {
                if (f.length < 2 || peer.room() == null) {
                    return;
                }
                String room = f[1];
                if (!room.equals(peer.room())) {
                    return;
                }
                broadcast(room, peer, Protocol.command(Protocol.TYPING, room, peer.username()));
            }
            case Protocol.TYPING_STOP -> {
                if (f.length < 2 || peer.room() == null) {
                    return;
                }
                String room = f[1];
                if (!room.equals(peer.room())) {
                    return;
                }
                broadcast(room, peer, Protocol.command(Protocol.TYPING_STOP, room, peer.username()));
            }
            case Protocol.HISTORY -> {
                String room = peer.room();
                if (room == null) {
                    peer.send(Protocol.command(Protocol.ERR, "Join a room first: @join <room>"));
                    return;
                }
                int count = 20;
                if (f.length > 2) {
                    try {
                        count = Math.max(1, Math.min(100, Integer.parseInt(f[2])));
                    } catch (NumberFormatException ignored) {
                        // keep default
                    }
                }
                for (MessageHistory.Entry e : history.recent(room, count)) {
                    peer.send(Protocol.command(Protocol.HIST_ENTRY, e.user, e.text));
                }
                peer.send(Protocol.command(Protocol.HIST_END));
            }
            default -> peer.send(Protocol.command(Protocol.ERR, "Unknown command: " + f[0]));
        }
    }

    /** Moves a participant into a room (leaving their previous one silently). */
    private void joinRoom(Participant participant, String room) {
        boolean created = rooms.putIfAbsent(room, new CopyOnWriteArrayList<>()) == null;
        CopyOnWriteArrayList<Participant> list = rooms.get(room);
        leaveRoom(participant, false);
        list.add(participant);
        participant.setRoom(room);
        broadcast(room, participant, Protocol.command(Protocol.SYS, participant.username() + " joined room " + room));
        participant.send(Protocol.command(Protocol.SYS, "You are now in room " + room));
        if (created) {
            syncLinkRooms();
        }
    }

    private void leaveRoom(Participant participant, boolean notify) {
        String room = participant.room();
        if (room == null) {
            return;
        }
        CopyOnWriteArrayList<Participant> members = rooms.get(room);
        if (members != null) {
            members.remove(participant);
            if (members.isEmpty() && rooms.remove(room, members)) {
                syncLinkRooms();
            }
        }
        participant.setRoom(null);
        if (notify) {
            broadcast(room, participant, Protocol.command(Protocol.SYS, participant.username() + " left room " + room));
            participant.send(Protocol.command(Protocol.SYS, "You left room " + room));
        }
    }

    private void broadcast(String room, Participant sender, String line) {
        CopyOnWriteArrayList<Participant> members = rooms.get(room);
        // Record chat messages in history so @history can replay them.
        String[] bf = Protocol.split(line);
        if (bf[0].equals(Protocol.FROM) && bf.length >= 5) {
            history.add(room, bf[2], bf[4]);
        }
        if (members != null) {
            for (Participant member : members) {
                if (member != sender) {
                    if (member == self) {
                        handleConsoleMessage(line);
                    } else {
                        member.send(line);
                    }
                }
            }
        }
        relayToLinks(room, line);
    }

    private void broadcastAll(String line) {
        for (Participant participant : participants) {
            participant.send(line);
        }
    }

    /** Delivers a bridged/console line to the local console, saving files as needed. */
    private void handleConsoleMessage(String line) {
        String[] f = Protocol.split(line);
        switch (f[0]) {
            case Protocol.FROM -> {
                if (f.length >= 5) {
                    System.out.println("[Room " + Ansi.color(Ansi.YELLOW, f[1]) + "] "
                            + Ansi.userColor(f[2]) + ": " + f[4]);
                } else {
                    System.out.println(Protocol.display(line));
                }
            }
            case Protocol.SYS -> System.out.println(Ansi.color(Ansi.WHITE, Protocol.display(line)));
            case Protocol.ERR -> System.out.println(Ansi.color(Ansi.RED, Protocol.display(line)));
            case Protocol.FILE_START -> {
                if (f.length < 7) {
                    return;
                }
                String msg = fileReceiver.start(f[1], f[2], f[3], parseLong(f[4]), parseInt(f[5]),
                        withHost(f[6], f.length > 7 ? f[7] : ""));
                if (msg != null) {
                    System.out.println(Ansi.color(Ansi.BRIGHT_YELLOW, msg));
                }
            }
            case Protocol.FILE_CHUNK -> {
                if (f.length < 5) {
                    return;
                }
                String msg = fileReceiver.chunk(f[1], parseInt(f[3]), f[4]);
                if (msg != null) {
                    System.out.println(Ansi.color(Ansi.BRIGHT_YELLOW, msg));
                }
            }
            case Protocol.FILE_ABORT -> {
                String msg = fileReceiver.abort(f.length > 1 ? f[1] : "", f.length > 3 ? f[3] : "");
                if (msg != null) {
                    System.out.println(Ansi.color(Ansi.RED, msg));
                }
            }
            case Protocol.TYPING -> {
                if (f.length >= 3) {
                    System.out.print("\r" + Ansi.color(Ansi.DIM, "[*] " + f[2] + " is typing...") + "   ");
                }
            }
            case Protocol.TYPING_STOP -> System.out.print("\r" + " ".repeat(60) + "\r");
            default -> System.out.println(Protocol.display(line));
        }
    }

    // ------------------------------------------------------------------
    // Host <-> Host bridging
    // ------------------------------------------------------------------

    /** Dials another host and bridges the rooms both sides host. */
    private void linkHost(String spec) {
        if (spec == null || spec.isBlank()) {
            System.out.println("[Usage] @link <host> [port]");
            return;
        }
        int space = spec.indexOf(' ');
        String host;
        int port;
        if (space < 0) {
            host = spec;
            port = Config.get().getPort();
        } else {
            host = spec.substring(0, space);
            try {
                port = Integer.parseInt(spec.substring(space + 1).trim());
            } catch (NumberFormatException e) {
                System.out.println("[Usage] @link <host> [port]");
                return;
            }
        }
        String endpoint = "link://" + host + ":" + port;
        try {
            SecureChannel channel = new SecureChannel(new SocketTransport(new Socket(host, port)), identity);
            if (!trustGate.verifyClient(channel, endpoint, prompt)) {
                channel.close();
                System.out.println(Ansi.color(Ansi.YELLOW, "[Bridge] Link to " + host + ":" + port + " not trusted."));
                return;
            }
            HostLink link = new HostLink(channel, endpoint, channel.getFingerprint());
            if (links.putIfAbsent(endpoint, link) != null) {
                channel.close();
                System.out.println("[Bridge] Already linked to " + endpoint);
                return;
            }
            link.send(Protocol.command(Protocol.BRIDGE, roomSetCsv()));
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Bridge] Linked to " + host + ":" + port
                    + " | fingerprint: " + shortFingerprint(channel.getFingerprint())));
            startLinkReader(link);
        } catch (Exception e) {
            System.out.println(Ansi.color(Ansi.RED, "[Bridge] Could not link to " + host + ":" + port + ": " + e.getMessage()));
        }
    }

    /** A remote host connected to us and introduced itself with a @BRIDGE handshake. */
    private void acceptLink(SecureChannel channel, String first) {
        String remoteRooms = first.equals(Protocol.BRIDGE) ? "" : first.substring(Protocol.BRIDGE.length() + 1);
        HostLink link = new HostLink(channel, "link://" + channel.remote(), channel.getFingerprint());
        if (links.putIfAbsent(link.endpoint, link) != null) {
            System.out.println("[Bridge] Duplicate link from " + channel.remote());
            channel.close();
            return;
        }
        updateLinkRooms(link, remoteRooms);
        link.send(Protocol.command(Protocol.BRIDGE, roomSetCsv()));
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Bridge] Host linked from " + channel.remote()
                + " | rooms: " + (remoteRooms.isEmpty() ? "-" : remoteRooms)
                + " | fingerprint: " + shortFingerprint(channel.getFingerprint())));
        startLinkReader(link);
    }

    private void startLinkReader(HostLink link) {
        Thread reader = new Thread(() -> linkLoop(link), "bridge-link-" + link.endpoint);
        reader.setDaemon(true);
        reader.start();
    }

    private void linkLoop(HostLink link) {
        try {
            while (running) {
                String line = link.channel.receive();
                if (line == null) {
                    break;
                }
                if (line.startsWith(Protocol.BRIDGE) && !line.startsWith(Protocol.BRIDGE_MSG)) {
                    String[] f = Protocol.split(line);
                    if (f[0].equals(Protocol.BRIDGE)) {
                        updateLinkRooms(link, f.length > 1 ? f[1] : "");
                    }
                } else if (line.startsWith(Protocol.BRIDGE_MSG + Protocol.SEP)) {
                    int first = line.indexOf(Protocol.SEP, Protocol.BRIDGE_MSG.length() + 1);
                    if (first > 0) {
                        handleBridgedMessage(link,
                                line.substring(Protocol.BRIDGE_MSG.length() + 1, first),
                                line.substring(first + 1));
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                System.out.println(Ansi.color(Ansi.RED, "[Bridge] Link " + link.endpoint + " lost: " + e.getMessage()));
            }
        } finally {
            links.remove(link.endpoint, link);
            System.out.println("[Bridge] Host link " + link.endpoint + " closed.");
        }
    }

    /** Dedups a bridged message, delivers it locally, and forwards it to other links. */
    private void handleBridgedMessage(HostLink origin, String rid, String inner) {
        if (!seenRids.add(rid)) {
            return;
        }
        if (seenRids.size() > SEEN_CAP) {
            seenRids.clear();
        }
        dispatchBridged(origin, rid, inner);
    }

    private void dispatchBridged(HostLink origin, String rid, String inner) {
        String[] f = Protocol.split(inner);
        String room;
        String localLine;
        switch (f[0]) {
            case Protocol.FROM -> {
                if (f.length < 5) {
                    return;
                }
                room = f[1];
                localLine = inner;
            }
            case Protocol.SYS -> {
                if (f.length < 3) {
                    return;
                }
                room = f[1];
                localLine = Protocol.command(Protocol.SYS, f[2]);
            }
            case Protocol.FILE_START, Protocol.FILE_CHUNK, Protocol.FILE_ABORT -> {
                if (f.length < 3) {
                    return;
                }
                room = f[2];
                localLine = inner;
            }
            default -> {
                return;
            }
        }
        deliverToRoom(room, localLine);
        for (HostLink link : links.values()) {
            if (link != origin && link.rooms.contains(room)) {
                link.send(Protocol.command(Protocol.BRIDGE_MSG, rid, inner));
            }
        }
    }

    private void deliverToRoom(String room, String localLine) {
        CopyOnWriteArrayList<Participant> members = rooms.get(room);
        if (members == null) {
            return;
        }
        for (Participant member : members) {
            if (member == self) {
                handleConsoleMessage(localLine);
            } else {
                member.send(localLine);
            }
        }
    }

    /** Relays a locally-originated room message to every linked host hosting that room. */
    private void relayToLinks(String room, String line) {
        List<HostLink> targets = new ArrayList<>();
        for (HostLink link : links.values()) {
            if (link.rooms.contains(room)) {
                targets.add(link);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        String rid = UUID.randomUUID().toString();
        String wrapped = Protocol.command(Protocol.BRIDGE_MSG, rid, withHostLabel(room, line));
        for (HostLink link : targets) {
            link.send(wrapped);
        }
    }

    /** Tags a locally-originated line with this host's label so remote users see user@host. */
    private String withHostLabel(String room, String line) {
        String[] f = Protocol.split(line);
        return switch (f[0]) {
            case Protocol.FROM -> f.length >= 5 && f[3].isEmpty()
                    ? Protocol.command(Protocol.FROM, f[1], f[2], hostLabel, f[4])
                    : line;
            case Protocol.SYS -> f.length >= 2
                    ? Protocol.command(Protocol.SYS, room, f[1])
                    : line;
            case Protocol.FILE_START -> f.length >= 8 && f[7].isEmpty()
                    ? Protocol.command(Protocol.FILE_START, f[1], f[2], f[3], f[4], f[5], f[6], hostLabel)
                    : line;
            default -> line;
        };
    }

    private void updateLinkRooms(HostLink link, String roomsCsv) {
        Set<String> set = new ConcurrentSkipListSet<>();
        for (String room : roomsCsv.split(",")) {
            if (!room.isEmpty()) {
                set.add(room);
            }
        }
        link.rooms.clear();
        link.rooms.addAll(set);
    }

    private void syncLinkRooms() {
        String csv = roomSetCsv();
        for (HostLink link : links.values()) {
            link.send(Protocol.command(Protocol.BRIDGE, csv));
        }
    }

    private String roomSetCsv() {
        return rooms.keySet().stream()
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.joining(","));
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return "?";
        }
        return fingerprint.substring(0, Math.min(8, fingerprint.length()));
    }

    private static String withHost(String sender, String host) {
        return host == null || host.isEmpty() ? sender : sender + "@" + host;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // File sending from the host console.
    // ------------------------------------------------------------------

    private void sendFile(String path, String room) {
        Path file = Path.of(path);
        try {
            if (!Files.isRegularFile(file)) {
                System.out.println("[File] Not a file: " + path);
                return;
            }
            byte[] all = Files.readAllBytes(file);
            String filename = file.getFileName().toString();
            String fid = UUID.randomUUID().toString();
            int chunkSize = FileReceiver.chunkSize();
            int count = FileReceiver.chunkCount(all.length, chunkSize);
            broadcast(room, self, Protocol.command(Protocol.FILE_START, fid, room, filename,
                    String.valueOf(all.length), String.valueOf(count), username, ""));
            for (int i = 0; i < count; i++) {
                broadcast(room, self, Protocol.command(Protocol.FILE_CHUNK, fid, room,
                        String.valueOf(i),
                        Base64.getEncoder().encodeToString(FileReceiver.chunk(all, i, chunkSize))));
            }
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[File] Sent " + filename + " (" + FileReceiver.human(all.length)
                    + ", " + count + " chunks) to room " + room));
        } catch (Exception e) {
            System.out.println(Ansi.color(Ansi.RED, "[File] Send failed: " + e.getMessage()));
        }
    }

    private String roomListCsv() {
        return rooms.entrySet().stream()
                .sorted((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()))
                .map(e -> e.getKey() + "(" + e.getValue().size() + ")")
                .collect(Collectors.joining(","));
    }

    private String userListCsv(String room) {
        CopyOnWriteArrayList<Participant> members = rooms.get(room);
        if (members == null || members.isEmpty()) {
            return "";
        }
        return members.stream().map(Participant::username).collect(Collectors.joining(","));
    }

    // ------------------------------------------------------------------
    // Local console (the host is also a chat participant)
    // ------------------------------------------------------------------

    @Override
    public void handleUserInput(String line) {
        String cmd = line.trim();
        if (cmd.isEmpty()) {
            return;
        }
        String[] parts = cmd.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        switch (command) {
            case "@join" -> {
                if (parts.length < 2 || !Protocol.isValidRoom(parts[1].trim())) {
                    System.out.println("[Usage] @join <room>");
                    return;
                }
                joinRoom(self, parts[1].trim());
            }
            case "@leave" -> leaveRoom(self, true);
            case "@list" -> System.out.println(Ansi.color(Ansi.CYAN, "[Rooms] " + Protocol.roomList(roomListCsv())));
            case "@users" -> System.out.println(Ansi.color(Ansi.CYAN, "[Users in "
                    + (self.room() == null ? "-" : self.room()) + "] "
                    + (self.room() == null ? "join a room first" : userListCsv(self.room()))));
            case "@link" -> linkHost(parts.length > 1 ? parts[1] : "");
            case "@kick" -> kickUser(parts.length > 1 ? parts[1].trim() : "");
            case "@ban" -> banUser(parts.length > 1 ? parts[1].trim() : "");
            case "@unban" -> {
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @unban <username>"));
                    return;
                }
                if (bannedUsers.remove(parts[1].trim())) {
                    System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Mod] Unbanned " + parts[1].trim()));
                } else {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[Mod] " + parts[1].trim() + " was not banned."));
                }
            }
            case "@history" -> showHistory(parts.length > 1 ? parts[1].trim() : "");
            case "@send" -> {
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    System.out.println("[Usage] @send <file-path>");
                    return;
                }
                if (self.room() == null) {
                    System.out.println("[System] Join a room first: @join <room>");
                    return;
                }
                sendFile(parts[1].trim(), self.room());
            }
            case "@help" -> printHelp();
            default -> {
                if (self.room() == null) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[System] Join a room first: @join <room>"));
                } else {
                    broadcast(self.room(), self,
                            Protocol.command(Protocol.FROM, self.room(), username, "", cmd));
                    System.out.println("[Room " + Ansi.color(Ansi.YELLOW, self.room()) + "] "
                            + Ansi.userColor(username) + ": " + cmd);
                }
            }
        }
    }

    private void kickUser(String name) {
        Participant target = byName.get(name);
        if (target == null || target == self) {
            System.out.println(Ansi.color(Ansi.YELLOW, "[Mod] No connected user named '" + name + "'"));
            return;
        }
        byName.remove(name, target);
        participants.remove(target);
        leaveRoom(target, false);
        try {
            target.send(Protocol.command(Protocol.ERR, "You were kicked by the host."));
        } catch (Exception ignored) {
        }
        target.close();
        broadcastAll(Protocol.command(Protocol.SYS, name + " was kicked by the host"));
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Mod] Kicked " + name));
    }

    private void banUser(String name) {
        if (name.isEmpty()) {
            System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @ban <username>"));
            return;
        }
        bannedUsers.add(name);
        kickUser(name);
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Mod] Banned " + name + ". They cannot reconnect."));
    }

    private void showHistory(String spec) {
        String room = self.room();
        int count = 20;
        if (spec != null && !spec.isEmpty()) {
            try {
                count = Math.max(1, Math.min(100, Integer.parseInt(spec)));
            } catch (NumberFormatException e) {
                room = spec; // treat as room name
            }
        }
        if (room == null) {
            System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @history [n]  (join a room first, or @history <room> [n])"));
            return;
        }
        var entries = history.recent(room, count);
        if (entries.isEmpty()) {
            System.out.println(Ansi.color(Ansi.DIM, "[History] No messages in room " + room + " yet."));
            return;
        }
        System.out.println(Ansi.color(Ansi.CYAN, "[History] Last " + entries.size() + " in " + room + ":"));
        for (MessageHistory.Entry e : entries) {
            System.out.println("  " + Ansi.userColor(e.user) + ": " + e.text);
        }
    }

    private void printHelp() {
        System.out.println(Ansi.color(Ansi.CYAN, """
                Commands:
                  @join <room>          create or join a room
                  @leave                leave the current room
                  @list                 list rooms
                  @users                list users in the current room
                  @link <host> [port]   bridge rooms with another host (same room name = same room)
                  @send <file>          send a file to the current room
                  @history [n]          show recent messages (n = count, default 20)
                  @kick <user>          disconnect a user
                  @ban <user>           kick + prevent user from reconnecting
                  @unban <user>         re-allow a banned user
                  @exit                 stop hosting
                  <text>                send a message to the current room"""));
    }

    // ------------------------------------------------------------------

    @Override
    protected void handleLine(String line) {
        // HostNode has no channel; nothing arrives here.
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
        if (webHost != null) {
            webHost.stop();
            webHost = null;
        }
        for (HostLink link : links.values()) {
            link.close();
        }
        links.clear();
        for (Participant participant : participants) {
            participant.close();
        }
        participants.clear();
        rooms.clear();
        byName.clear();
        System.out.println(Ansi.color(Ansi.DIM, "[System] Host stopped."));
    }

    /** A bridge connection to another host. */
    private static final class HostLink {
        final SecureChannel channel;
        final String endpoint;
        final Set<String> rooms = ConcurrentHashMap.newKeySet();

        HostLink(SecureChannel channel, String endpoint, String fingerprint) {
            this.channel = channel;
            this.endpoint = endpoint;
        }

        void send(String line) {
            try {
                channel.send(line);
            } catch (IOException ignored) {
                // The link loop will notice and clean up.
            }
        }

        void close() {
            channel.close();
        }
    }
}
