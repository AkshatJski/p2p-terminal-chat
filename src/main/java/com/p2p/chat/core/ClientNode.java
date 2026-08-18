package com.p2p.chat.core;

import com.p2p.chat.config.Config;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.protocol.Protocol;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

/**
 * A peer that joins an existing host. Holds one encrypted connection, verifies
 * the host's fingerprint (trust-on-first-use), tracks the current room so plain
 * typed text can be addressed, and renders inbound messages on the terminal.
 * Files sent in a room are received into the download folder ({@code ./downloads}).
 */
public final class ClientNode extends Node {
    private final String host;
    private final int port;
    private final TrustGate trustGate;
    private final Prompt prompt;
    private final FileReceiver fileReceiver;
    private volatile String currentRoom;

    public ClientNode(String username, String host, int port,
                      Identity identity, TrustGate trustGate, Prompt prompt) throws IOException {
        super(new SecureChannel(new Socket(host, port), identity), username);
        this.host = host;
        this.port = port;
        this.trustGate = trustGate;
        this.prompt = prompt;
        this.fileReceiver = new FileReceiver(Config.get().getDownloadDir());
    }

    public void start() {
        String endpoint = host + ":" + port;
        try {
            if (!trustGate.verifyClient(channel, endpoint, prompt)) {
                close();
                return;
            }
        } catch (IOException e) {
            System.err.println("[Error] Verification failed: " + e.getMessage());
            close();
            return;
        }
        send(Protocol.command(Protocol.NAME, username));
        startReader();
        System.out.println("[System] Connected to " + host + " as " + username
                + ". Type @join <room> to enter a room.");
    }

    @Override
    public void handleLine(String line) {
        String[] f = Protocol.split(line);
        switch (f[0]) {
            case Protocol.ROOMS -> System.out.println("[Rooms] " + Protocol.roomList(f[1]));
            case Protocol.ROOM_USERS -> System.out.println("[Users in " + f[1] + "] "
                    + (f[2].isEmpty() ? "none" : f[2]));
            case Protocol.SYS, Protocol.ERR, Protocol.FROM -> System.out.println(Protocol.display(line));
            case Protocol.FILE_START -> {
                if (f.length < 7) {
                    return;
                }
                String msg = fileReceiver.start(f[1], f[2], f[3], parseLong(f[4]), parseInt(f[5]),
                        withHost(f[6], f.length > 7 ? f[7] : ""));
                if (msg != null) {
                    System.out.println(msg);
                }
            }
            case Protocol.FILE_CHUNK -> {
                if (f.length < 5) {
                    return;
                }
                String msg = fileReceiver.chunk(f[1], parseInt(f[3]), f[4]);
                if (msg != null) {
                    System.out.println(msg);
                }
            }
            case Protocol.FILE_ABORT -> {
                String msg = fileReceiver.abort(f.length > 1 ? f[1] : "", f.length > 3 ? f[3] : "");
                if (msg != null) {
                    System.out.println(msg);
                }
            }
            default -> System.out.println("[System] " + line);
        }
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

    @Override
    protected void handleDisconnect() {
        System.out.println("\n[System] Disconnected from host.");
    }

    @Override
    public void handleUserInput(String line) {
        String cmd = line.trim();
        if (cmd.isEmpty()) {
            return;
        }
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0].toLowerCase()) {
            case "@join" -> {
                if (parts.length < 2 || !Protocol.isValidRoom(parts[1].trim())) {
                    System.out.println("[Usage] @join <room>");
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
            case "@help" -> printHelp();
            case "@send" -> {
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    System.out.println("[Usage] @send <file-path>");
                    return;
                }
                if (currentRoom == null) {
                    System.out.println("[System] Join a room first: @join <room>");
                    return;
                }
                sendFile(parts[1].trim(), currentRoom);
            }
            case "@exit" -> close();
            default -> {
                if (currentRoom == null) {
                    System.out.println("[System] Join a room first: @join <room>");
                } else {
                    send(Protocol.command(Protocol.MSG, currentRoom, cmd));
                    System.out.println("[Room " + currentRoom + "] " + username + ": " + cmd);
                }
            }
        }
    }

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
            send(Protocol.command(Protocol.FILE_START, fid, room, filename,
                    String.valueOf(all.length), String.valueOf(count), username, ""));
            for (int i = 0; i < count; i++) {
                send(Protocol.command(Protocol.FILE_CHUNK, fid, room, String.valueOf(i),
                        Base64.getEncoder().encodeToString(FileReceiver.chunk(all, i, chunkSize))));
            }
            System.out.println("[File] Sent " + filename + " (" + FileReceiver.human(all.length)
                    + ", " + count + " chunks) to room " + room);
        } catch (Exception e) {
            System.out.println("[File] Send failed: " + e.getMessage());
        }
    }

    private void printHelp() {
        System.out.println("""
                Commands:
                  @join <room>   create or join a room
                  @leave         leave the current room
                  @list          list rooms
                  @users         list users in the current room
                  @send <file>   send a file to the current room
                  @exit          disconnect
                  <text>         send a message to the current room""");
    }
}
