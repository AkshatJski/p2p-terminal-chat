package com.p2p.chat.core;

import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.protocol.Protocol;
import java.io.IOException;

/**
 * A member of a chat room. Remote members wrap a {@link SecureChannel}; the
 * host's own console is represented by a participant whose {@code channel} is
 * {@code null} and whose {@link #send} prints to the terminal instead.
 */
public final class Participant {
    private final SecureChannel channel;
    private volatile String username;
    private volatile String room;

    public Participant(SecureChannel channel) {
        this.channel = channel;
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

    /** Delivers a protocol line: encrypted send for remote peers, console for self. */
    public void send(String line) {
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
        if (channel != null) {
            channel.close();
        }
    }
}
