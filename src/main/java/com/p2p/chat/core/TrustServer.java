package com.p2p.chat.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny HTTP server for trust verification.
 *
 * <p>Serves a polished web page where a remote user can see the connection
 * fingerprint and click "Confirm" or "Reject". Used instead of (or as a
 * fallback to) the terminal y/n trust prompt.
 *
 * <p>Requires {@code com.sun.net.httpserver.HttpServer} which is part of the JDK.
 */
public class TrustServer {
    private final String fingerprint;
    private final AtomicBoolean confirmed = new AtomicBoolean(false);
    private final AtomicBoolean rejected = new AtomicBoolean(false);
    private HttpServer server;
    private int port;

    public TrustServer(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int start(int preferredPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(preferredPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/verify", new VerifyHandler());
        server.createContext("/confirm", new ConfirmHandler());
        server.createContext("/reject", new RejectHandler());
        server.start();
        port = server.getAddress().getPort();
        return port;
    }

    /**
     * Blocks until the user confirms or rejects, or the timeout expires.
     *
     * @param timeoutMs max wait time (0 = wait forever)
     * @return true if confirmed, false if rejected or timed out
     */
    public boolean awaitConfirmation(int timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (!confirmed.get() && !rejected.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return confirmed.get();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isConfirmed() {
        return confirmed.get();
    }

    private static String html(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s</title>
                    <style>
                        :root {
                            --bg: #0f172a;
                            --card: #1e293b;
                            --muted: #94a3b8;
                            --accent: #38bdf8;
                            --success: #34d399;
                            --danger: #f87171;
                            --text: #e2e8f0;
                        }
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            background: var(--bg);
                            color: var(--text);
                            min-height: 100vh;
                            display: flex; justify-content: center; align-items: center;
                            padding: 24px;
                        }
                        .card {
                            background: var(--card);
                            border-radius: 20px;
                            padding: 48px 40px;
                            max-width: 520px; width: 100%%;
                            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
                            border: 1px solid rgba(255, 255, 255, 0.06);
                            text-align: center;
                            animation: fadeIn 0.4s ease;
                        }
                        @keyframes fadeIn {
                            from { opacity: 0; transform: translateY(12px); }
                            to   { opacity: 1; transform: translateY(0); }
                        }
                        .shield {
                            width: 72px; height: 72px; margin: 0 auto 20px;
                            border-radius: 50%%;
                            display: flex; align-items: center; justify-content: center;
                            font-size: 34px;
                            background: rgba(56, 189, 248, 0.12);
                            border: 2px solid rgba(56, 189, 248, 0.35);
                            animation: pulse 2s infinite;
                        }
                        @keyframes pulse {
                            0%%   { box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.35); }
                            70%%  { box-shadow: 0 0 0 14px rgba(56, 189, 248, 0); }
                            100%% { box-shadow: 0 0 0 0 rgba(56, 189, 248, 0); }
                        }
                        .shield-success { background: rgba(52, 211, 153, 0.12); border-color: rgba(52, 211, 153, 0.4); animation: none; }
                        .shield-danger  { background: rgba(248, 113, 113, 0.12); border-color: rgba(248, 113, 113, 0.4); animation: none; }
                        h1 { font-size: 22px; font-weight: 700; margin-bottom: 6px; }
                        .subtitle { color: var(--muted); font-size: 14px; margin-bottom: 32px; }
                        .fp-label { font-size: 11px; text-transform: uppercase; letter-spacing: 1.5px; color: var(--muted); margin-bottom: 8px; }
                        .fingerprint {
                            background: #0b1526;
                            border: 1px solid rgba(56, 189, 248, 0.15);
                            border-radius: 12px;
                            padding: 18px 20px;
                            font-family: 'SF Mono', 'JetBrains Mono', 'Fira Code', Consolas, monospace;
                            font-size: 16px; letter-spacing: 1.5px;
                            color: var(--accent);
                            word-break: break-all;
                            line-height: 1.7;
                            margin-bottom: 28px;
                            user-select: all;
                        }
                        .message {
                            padding: 12px 16px; border-radius: 10px;
                            font-size: 14px; font-weight: 500;
                            margin-bottom: 28px;
                            color: var(--text);
                        }
                        .message-info { background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.2); }
                        .message-success { background: rgba(52, 211, 153, 0.1); border: 1px solid rgba(52, 211, 153, 0.3); color: var(--success); }
                        .message-danger { background: rgba(248, 113, 113, 0.1); border: 1px solid rgba(248, 113, 113, 0.3); color: var(--danger); }
                        .buttons { display: flex; justify-content: center; gap: 14px; flex-wrap: wrap; }
                        .btn {
                            padding: 14px 32px; border-radius: 12px;
                            font-size: 15px; font-weight: 600; border: none; cursor: pointer;
                            text-decoration: none; transition: transform 0.12s, box-shadow 0.2s, opacity 0.2s;
                            display: inline-flex; align-items: center; gap: 8px;
                        }
                        .btn:hover { transform: translateY(-2px); }
                        .btn:active { transform: translateY(0); }
                        .btn-confirm {
                            background: var(--success); color: #052e16;
                            box-shadow: 0 6px 20px rgba(52, 211, 153, 0.3);
                        }
                        .btn-reject {
                            background: rgba(248, 113, 113, 0.12); color: var(--danger);
                            border: 1px solid rgba(248, 113, 113, 0.3);
                        }
                        .btn:disabled { opacity: 0.5; cursor: not-allowed; transform: none; box-shadow: none; }
                        .hint { margin-top: 24px; font-size: 12px; color: var(--muted); }
                        .gauge {
                            height: 4px; width: 100%%; background: rgba(255,255,255,0.08);
                            border-radius: 2px; overflow: hidden; margin-top: 28px;
                        }
                        .gauge-fill {
                            height: 100%%; background: var(--accent);
                            animation: shrink 60s linear forwards;
                        }
                        @keyframes shrink { from { width: 100%%; } to { width: 0; } }
                    </style>
                </head>
                <body>
                    %s
                </body>
                </html>
                """.formatted(title, body);
    }

    private String groupFingerprint(String fp) {
        return fp.replaceAll("-", "-\n");
    }

    private String renderVerify() {
        boolean done = confirmed.get() || rejected.get();
        boolean ok = confirmed.get();
        String shieldCls = done ? (ok ? "shield-success" : "shield-danger") : "";
        String shield = done ? (ok ? "✅" : "✖") : "🛡️";
        String messageClass = done ? (ok ? "message-success" : "message-danger") : "message-info";
        String message = done ? (ok ? "Fingerprint confirmed. This peer can now connect securely."
                                        : "You rejected this connection. The peer will be refused.")
                              : "Verify this fingerprint with the other person, then confirm to proceed.";
        String title = done ? (ok ? "P2P Chat — Confirmed" : "P2P Chat — Rejected") : "P2P Chat — Trust Verification";
        String buttons = done
                ? "<div class=\"buttons\"><a href=\"/verify\" class=\"btn btn-confirm\" disabled>Done</a></div>"
                : "<div class=\"buttons\">" +
                  "<a href=\"/confirm\" class=\"btn btn-confirm\">✓ Confirm Trust</a>" +
                  "<a href=\"/reject\" class=\"btn btn-reject\">✕ Reject</a>" +
                  "</div>";
        String body = """
                <div class="card">
                    <div class="shield %s">%s</div>
                    <h1>P2P Chat</h1>
                    <div class="subtitle">Secure Connection · Trust Verification</div>
                    <div class="fp-label">Connection Fingerprint</div>
                    <div class="fingerprint">%s</div>
                    <div class="message %s">%s</div>
                    %s
                    <div class="hint">%s</div>
                    <div class="gauge"><div class="gauge-fill"></div></div>
                </div>
                """.formatted(shieldCls, shield, groupFingerprint(fingerprint),
                messageClass, message, buttons,
                done ? "You can close this window now." : "This page expires in 60 seconds.");
        return html(title, body);
    }

    private class VerifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            respond(exchange, 200, renderVerify());
        }
    }

    private class ConfirmHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            confirmed.set(true);
            respond(exchange, 200, renderVerify());
        }
    }

    private class RejectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            rejected.set(true);
            respond(exchange, 200, renderVerify());
        }
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
