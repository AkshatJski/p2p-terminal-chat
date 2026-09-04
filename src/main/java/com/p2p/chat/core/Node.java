package com.p2p.chat.core;

import com.p2p.chat.crypto.SecureChannel;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Common behaviour for every chat peer: owns an optional {@link SecureChannel},
 * runs a background reader thread, and dispatches incoming protocol lines.
 *
 * <p>Two concrete subclasses exist:
 * <ul>
 *   <li>{@link HostNode} - accepts many peers and relays room messages (its own
 *       console is a "self" participant).</li>
 *   <li>{@link ClientNode} - connects to one host.</li>
 * </ul>
 */
public abstract class Node implements AutoCloseable {
    protected volatile SecureChannel channel;
    protected final String username;
    protected volatile boolean running = true;
    protected volatile boolean userClosed = false;
    protected final AtomicBoolean closed = new AtomicBoolean();
    private Thread readerThread;

    protected Node(SecureChannel channel, String username) {
        this.channel = channel;
        this.username = username;
    }

    public String username() {
        return username;
    }

    public boolean isRunning() {
        return running;
    }

    /** Subclasses override to kick off their connection phase; default only starts the reader. */
    public void start() throws IOException {
        startReader();
    }

    /** Spawns the background thread that reads encrypted messages. */
    protected void startReader() {
        if (channel == null) {
            return;
        }
        readerThread = new Thread(this::readLoop, "net-listener");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        // Capture the channel reference locally — it may be reassigned during reconnect.
        SecureChannel ch = this.channel;
        try {
            while (running && ch != null) {
                String message = ch.receive();
                if (message == null) {
                    break;
                }
                handleLine(message);
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("[Error] Connection lost: " + e.getMessage());
            }
        } finally {
            running = false;
            if (!userClosed) {
                handleDisconnect();
            }
        }
    }

    /** Sends a protocol line over the encrypted channel (fire and forget). */
    protected void send(String line) {
        if (channel == null) {
            return;
        }
        try {
            channel.send(line);
        } catch (IOException e) {
            System.err.println("[Error] Failed to send: " + e.getMessage());
        }
    }

    protected void stop() {
        running = false;
    }

    /** Called by the UI for every line the user types. */
    public abstract void handleUserInput(String line);

    /** Called by the reader thread for every inbound protocol line. */
    protected abstract void handleLine(String line);

    /** Hook for when the peer disappears (not triggered by a local close). */
    protected void handleDisconnect() {
    }

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        userClosed = true;
        stop();
        if (channel != null) {
            channel.close();
        }
    }
}
