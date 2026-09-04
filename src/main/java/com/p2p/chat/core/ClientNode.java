package com.p2p.chat.core;

import com.p2p.chat.config.Config;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.crypto.TrustStore;
import com.p2p.chat.protocol.Protocol;
import com.p2p.chat.util.Ansi;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A peer that joins an existing host. Holds one encrypted connection, verifies
 * the host's fingerprint (trust-on-first-use), tracks the current room so plain
 * typed text can be addressed, and renders inbound messages on the terminal.
 * Files sent in a room are received into the download folder ({@code ./downloads}).
 *
 * <p>Supports automatic reconnection with exponential backoff on connection loss.
 */
public final class ClientNode extends Node {
    private final String host;
    private final int port;
    private final TrustGate trustGate;
    private final Prompt prompt;
    private final FileReceiver fileReceiver;
    private final ReconnectableChannel reconnector;
    private final Config config;
    private final AtomicInteger reconnectChurn = new AtomicInteger();
    private volatile long lastOnlineAt = System.currentTimeMillis();
    private volatile String currentRoom;

    public ClientNode(String username, String host, int port,
                      Identity identity, TrustGate trustGate, Prompt prompt) throws IOException {
        super(new SecureChannel(new Socket(host, port), identity), username);
        this.host = host;
        this.port = port;
        this.trustGate = trustGate;
        this.prompt = prompt;
        this.fileReceiver = new FileReceiver(Config.get().getDownloadDir());
        this.config = Config.get();
        this.reconnector = new ReconnectableChannel(host, port, identity, trustGate.getStore());
        this.reconnector.setMaxRetries(config.getReconnectMaxRetries());
        this.reconnector.setBaseDelayMs(config.getReconnectBaseDelayMs());
        this.reconnector.setMaxDelayMs(config.getReconnectMaxDelayMs());
    }

    public void start() {
        String endpoint = host + ":" + port;
        try {
            if (!trustGate.verifyClient(channel, endpoint, prompt)) {
                userClosed = true;
                close();
                return;
            }
        } catch (IOException e) {
            System.err.println(Ansi.color(Ansi.RED, "[Error] Verification failed: " + e.getMessage()));
            userClosed = true;
            close();
            return;
        }
        sendInitialCommands();
        startReader();
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] Connected to " + host + " as " + username
                + ". Type @join <room> to enter a room."));
    }

    private void sendInitialCommands() {
        send(Protocol.command(Protocol.NAME, username));
        if (currentRoom != null) {
            send(Protocol.command(Protocol.JOIN, currentRoom));
        }
    }

    @Override
    public void handleLine(String line) {
        String[] f = Protocol.split(line);
        switch (f[0]) {
            case Protocol.ROOMS -> System.out.println(Ansi.color(Ansi.CYAN, "[Rooms] " + Protocol.roomList(f[1])));
            case Protocol.ROOM_USERS -> System.out.println(Ansi.color(Ansi.CYAN, "[Users in " + f[1] + "] "
                    + (f[2].isEmpty() ? "none" : f[2])));
            case Protocol.SYS -> System.out.println(Ansi.color(Ansi.WHITE, Protocol.display(line)));
            case Protocol.ERR -> System.out.println(Ansi.color(Ansi.RED, Protocol.display(line)));
            case Protocol.FROM -> {
                if (f.length >= 5) {
                    System.out.println("[Room " + Ansi.color(Ansi.YELLOW, f[1]) + "] "
                            + Ansi.userColor(f[2]) + ": " + f[4]);
                } else {
                    System.out.println(Protocol.display(line));
                }
            }
            case Protocol.TYPING -> {
                if (f.length >= 3) {
                    System.out.print("\r" + Ansi.color(Ansi.DIM, "[*] " + f[2] + " is typing...") + "   ");
                }
            }
            case Protocol.TYPING_STOP -> System.out.print("\r" + " ".repeat(60) + "\r");
            case Protocol.FILE_START -> {
                if (f.length < 7) return;
                String msg = fileReceiver.start(f[1], f[2], f[3], parseLong(f[4]), parseInt(f[5]),
                        withHost(f[6], f.length > 7 ? f[7] : ""));
                if (msg != null) System.out.println(Ansi.color(Ansi.BRIGHT_YELLOW, msg));
            }
            case Protocol.FILE_CHUNK -> {
                if (f.length < 5) return;
                String msg = fileReceiver.chunk(f[1], parseInt(f[3]), f[4]);
                if (msg != null) System.out.println(Ansi.color(Ansi.BRIGHT_YELLOW, msg));
            }
            case Protocol.FILE_ABORT -> {
                String msg = fileReceiver.abort(f.length > 1 ? f[1] : "", f.length > 3 ? f[3] : "");
                if (msg != null) System.out.println(Ansi.color(Ansi.RED, msg));
            }
            case Protocol.HIST_ENTRY -> {
                if (f.length >= 3) {
                    System.out.println(Ansi.color(Ansi.DIM, "  [prev] ") + Ansi.userColor(f[1]) + ": " + f[2]);
                }
            }
            case Protocol.HIST_END -> System.out.println(Ansi.color(Ansi.CYAN, "[History] End of history."));
            case Protocol.BANNED -> System.out.println(Ansi.color(Ansi.BRIGHT_RED, "[Mod] " + (f.length > 1 ? f[1] : "You are banned.")));
            default -> System.out.println(Ansi.color(Ansi.DIM, "[System] " + line));
        }
    }

    private static String withHost(String sender, String host) {
        return host == null || host.isEmpty() ? sender : sender + "@" + host;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    @Override
    protected void handleDisconnect() {
        System.out.println("\n" + Ansi.color(Ansi.YELLOW, "[System] Disconnected from host."));
        if (userClosed || !config.isReconnectEnabled()) {
            return;
        }
        // Guard against a reconnect loop: if we keep coming back online only to
        // drop again within seconds, stop retrying instead of churning forever.
        int churn = reconnectChurn.incrementAndGet();
        if (churn > config.getReconnectMaxRetries()
                && System.currentTimeMillis() - lastOnlineAt < 3_000) {
            System.out.println(Ansi.color(Ansi.RED,
                    "[Reconnect] Connection did not stabilize; stopping reconnect attempts."));
            return;
        }
        attemptReconnect();
    }

    private void attemptReconnect() {
        System.out.println(Ansi.color(Ansi.YELLOW, "[Reconnect] Attempting to reconnect..."));
        SecureChannel newChannel = reconnector.reconnect(null);
        if (newChannel != null) {
            // Install the fresh channel first so @NAME/@JOIN go out over it,
            // then re-join the room and restart the reader on the new socket.
            this.channel = newChannel;
            sendInitialCommands();
            running = true;
            startReader();
            reconnectChurn.set(0);
            lastOnlineAt = System.currentTimeMillis();
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Reconnect] Back online."));
        } else {
            System.out.println(Ansi.color(Ansi.RED, "[Reconnect] Could not reconnect. Run again to try."));
        }
    }

    @Override
    public void handleUserInput(String line) {
        String cmd = line.trim();
        if (cmd.isEmpty()) return;
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0].toLowerCase()) {
            case "@join" -> {
                if (parts.length < 2 || !Protocol.isValidRoom(parts[1].trim())) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @join <room>"));
                    return;
                }
                currentRoom = parts[1].trim();
                send(Protocol.command(Protocol.JOIN, currentRoom));
            }
            case "@leave" -> {
                currentRoom = null;
                send(Protocol.command(Protocol.LEAVE));
            }
            case "@list" -> send(Protocol.command(Protocol.LIST));
            case "@users" -> send(Protocol.command(Protocol.USERS));
            case "@history" -> {
                if (currentRoom == null) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[History] Join a room first: @join <room>"));
                    return;
                }
                int count = 20;
                if (parts.length > 1) {
                    try {
                        count = Math.max(1, Math.min(100, Integer.parseInt(parts[1].trim())));
                    } catch (NumberFormatException e) {
                        System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @history [n]"));
                        return;
                    }
                }
                send(Protocol.command(Protocol.HISTORY, currentRoom, String.valueOf(count)));
            }
            case "@help" -> printHelp();
            case "@send" -> {
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[Usage] @send <file-path>"));
                    return;
                }
                if (currentRoom == null) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[System] Join a room first: @join <room>"));
                    return;
                }
                sendFile(parts[1].trim(), currentRoom);
            }
            case "@exit" -> close();
            default -> {
                if (currentRoom == null) {
                    System.out.println(Ansi.color(Ansi.YELLOW, "[System] Join a room first: @join <room>"));
                } else {
                    send(Protocol.command(Protocol.MSG, currentRoom, cmd));
                    System.out.println("[Room " + Ansi.color(Ansi.YELLOW, currentRoom) + "] "
                            + Ansi.userColor(username) + ": " + cmd);
                }
            }
        }
    }

    private void sendFile(String path, String room) {
        Path file = Path.of(path);
        try {
            if (!Files.isRegularFile(file)) {
                System.out.println(Ansi.color(Ansi.RED, "[File] Not a file: " + path));
                return;
            }
            byte[] all = Files.readAllBytes(file);
            String filename = file.getFileName().toString();
            String fid = UUID.randomUUID().toString();
            int chunkSize = FileReceiver.chunkSize();
            int count = FileReceiver.chunkCount(all.length, chunkSize);
            send(Protocol.command(Protocol.FILE_START, fid, room, filename,
                    String.valueOf(all.length), String.valueOf(count), username, ""));
            for (int i = 0; i < count; i++) {
                send(Protocol.command(Protocol.FILE_CHUNK, fid, room, String.valueOf(i),
                        Base64.getEncoder().encodeToString(FileReceiver.chunk(all, i, chunkSize))));
            }
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[File] Sent " + filename + " (" + FileReceiver.human(all.length)
                    + ", " + count + " chunks) to room " + room));
        } catch (Exception e) {
            System.out.println(Ansi.color(Ansi.RED, "[File] Send failed: " + e.getMessage()));
        }
    }

    private void printHelp() {
        System.out.println(Ansi.color(Ansi.CYAN, """
                Commands:
                  @join <room>   create or join a room
                  @leave         leave the current room
                  @list          list rooms
                  @users         list users in the current room
                  @history [n]   show recent messages in the current room
                  @send <file>   send a file to the current room
                  @exit          disconnect
                  <text>         send a message to the current room"""));
    }
}
