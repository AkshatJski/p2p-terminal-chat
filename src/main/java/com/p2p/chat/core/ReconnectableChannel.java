package com.p2p.chat.core;

import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.crypto.TrustStore;
import com.p2p.chat.transport.SocketTransport;
import com.p2p.chat.util.Ansi;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps connection logic with automatic exponential-backoff reconnection.
 *
 * <p>When the underlying {@link SecureChannel} drops (receive returns null),
 * this class retries the handshake with increasing delays: 1s, 2s, 4s, ...
 * up to 30s. On success it re-joins the last room. On exhausting retries
 * it gives up and reports the failure.
 */
public class ReconnectableChannel {
    private final String host;
    private final int port;
    private final Identity identity;
    private final TrustStore trustStore;
    private final AtomicInteger attempt = new AtomicInteger();
    private volatile int maxRetries = 12;
    private volatile long baseDelayMs = 1_000;
    private volatile long maxDelayMs = 30_000;
    private volatile boolean reconnecting = false;

    public ReconnectableChannel(String host, int port, Identity identity, TrustStore trustStore) {
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.trustStore = trustStore;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setBaseDelayMs(long baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    /**
     * Attempts to establish a new connection with exponential backoff.
     *
     * @param onConnect called after a successful handshake (send @NAME, @JOIN, etc.)
     * @return a connected SecureChannel, or null if all retries exhausted
     */
    public SecureChannel connect(Runnable onConnect) {
        attempt.set(0);
        return tryConnect(onConnect);
    }

    /**
     * Called when the current connection drops. Retries with backoff.
     *
     * @param onConnect called after a successful handshake
     * @return a connected SecureChannel, or null if all retries exhausted
     */
    public SecureChannel reconnect(Runnable onConnect) {
        reconnecting = true;
        attempt.incrementAndGet();
        SecureChannel channel = tryConnect(onConnect);
        reconnecting = false;
        return channel;
    }

    private SecureChannel tryConnect(Runnable onConnect) {
        while (attempt.get() <= maxRetries) {
            int attemptNum = attempt.get();
            if (attemptNum > 0) {
                long delay = Math.min(baseDelayMs * (1L << (attemptNum - 1)), maxDelayMs);
                // Add jitter: 50% to 100% of computed delay
                delay = (long) (delay * (0.5 + Math.random() * 0.5));
                System.out.println(Ansi.color(Ansi.YELLOW,
                        "[Reconnect] Attempt " + attemptNum + "/" + maxRetries
                        + " in " + (delay / 1000) + "s..."));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try {
                Socket socket = new Socket(host, port);
                SecureChannel channel = new SecureChannel(new SocketTransport(socket), identity);
                attempt.set(0);
                if (onConnect != null) {
                    onConnect.run();
                }
                if (attemptNum > 0) {
                    System.out.println(Ansi.color(Ansi.BRIGHT_GREEN,
                            "[Reconnect] Connected on attempt " + attemptNum));
                }
                return channel;
            } catch (IOException e) {
                attempt.incrementAndGet();
                if (attempt.get() > maxRetries) {
                    System.out.println(Ansi.color(Ansi.RED,
                            "[Reconnect] Failed after " + maxRetries + " attempts."));
                    return null;
                }
            }
        }
        return null;
    }
}
