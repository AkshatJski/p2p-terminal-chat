package com.p2p.chat.core;

import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.protocol.Protocol;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * A member of a chat room. Remote members wrap a {@link SecureChannel}; the
 * host's own console is represented by a participant whose {@code channel} is
 * {@code null} and whose {@link #send} prints to the terminal instead. Browser
 * members wrap a WebSocket sink and closer instead of a channel.
 */
public final class Participant {
    private final SecureChannel channel;
    private final Consumer<String> webSink;
    private final Runnable webCloser;
    private volatile String username;
    private volatile String room;

    public Participant(SecureChannel channel) {
        this(channel, null, null);
    }

    /** A browser participant: {@code send} delivers to the WebSocket, {@code close} drops it. */
    public Participant(Consumer<String> webSink, Runnable webCloser) {
        this(null, webSink, webCloser);
    }

    private Participant(SecureChannel channel, Consumer<String> webSink, Runnable webCloser) {
        this.channel = channel;
        this.webSink = webSink;
        this.webCloser = webCloser;
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String room() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public boolean isRemote() {
        return channel != null;
    }

    /** Delivers a protocol line: encrypted send for remote, WebSocket for browser, console for self. */
    public void send(String line) {
        if (webSink != null) {
            try {
                webSink.accept(line);
            } catch (Exception ignored) {
                // The WebSocket is gone; onClose will clean up.
            }
            return;
        }
        if (channel != null) {
            try {
                channel.send(line);
            } catch (IOException e) {
                // Peer is gone; the serve loop will notice and clean up.
            }
        } else {
            System.out.println(Protocol.display(line));
        }
    }

    public void close() {
        if (webCloser != null) {
            try {
                webCloser.run();
            } catch (Exception ignored) {
            }
            return;
        }
        if (channel != null) {
            channel.close();
        }
    }
}
