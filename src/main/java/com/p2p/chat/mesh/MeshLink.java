package com.p2p.chat.mesh;

import com.p2p.chat.crypto.SecureChannel;
import java.io.IOException;

/** One encrypted hop in the mesh. */
final class MeshLink {
    final SecureChannel channel;
    final String id;

    MeshLink(SecureChannel channel, String id) {
        this.channel = channel;
        this.id = id;
    }

    void send(String line) {
        try {
            channel.send(line);
        } catch (IOException e) {
            // Reader thread will notice and remove the link.
        }
    }
}
