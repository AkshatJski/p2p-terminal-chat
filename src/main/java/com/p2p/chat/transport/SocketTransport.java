package com.p2p.chat.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** TCP/IP transport backed by a {@link Socket}. */
public final class SocketTransport implements Transport {
    private final Socket socket;

    public SocketTransport(Socket socket) {
        this.socket = socket;
    }

    @Override
    public InputStream input() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream output() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public String id() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void setReadTimeout(int milliseconds) throws IOException {
        socket.setSoTimeout(milliseconds);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
