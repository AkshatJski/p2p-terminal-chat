package com.p2p.chat.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A bidirectional byte link that {@code SecureChannel} speaks its encrypted
 * protocol over. Anything that yields two connected streams can be a transport:
 * <ul>
 *   <li>{@link SocketTransport} - TCP/IP (LAN, WAN)</li>
 *   <li>{@link SerialTransport} - Bluetooth RFCOMM exposed as a serial/COM port</li>
 *   <li>{@link LoopbackTransport} - in-process pipes for tests</li>
 * </ul>
 * This is the seam that lets the mesh run over Bluetooth when WiFi is gone.
 */
public interface Transport extends Closeable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    /** Human readable endpoint, used as the trust-store key. */
    String id();

    default void setReadTimeout(int milliseconds) throws IOException {
        // Not every transport supports timeouts; default is a no-op.
    }
}
