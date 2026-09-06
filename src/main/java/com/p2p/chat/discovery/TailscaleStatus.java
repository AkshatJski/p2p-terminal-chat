package com.p2p.chat.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads {@code tailscale status --json} so the join prompt can list online
 * tailnet hosts (and the host can print its MagicDNS JOIN line).
 *
 * <p>Parsing is tolerant: any field that is missing or malformed is skipped
 * and the caller just gets fewer (or no) peers — never an exception.
 */
public final class TailscaleStatus {
    private static final long TIMEOUT_SECONDS = 3;

    /** One tailnet host (this machine or a peer) as reported by the CLI. */
    public record Peer(String dnsName, String hostName, String ipv4, boolean online) {
    }

    /** This machine's own tailnet identity. */
    public record Self(String dnsName, String hostName, String ipv4, boolean online) {
    }

    /** A parsed snapshot of the status JSON; {@link Status#NONE} when the CLI is missing/garbage. */
    public record Status(Self self, List<Peer> peers) {
        static final Status NONE = new Status(null, List.of());
    }

    private TailscaleStatus() {
    }

    /** Parses {@code tailscale status --json} output. Anything unparseable yields {@link Status#NONE}. */
    public static Status fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Status.NONE;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return new Status(readSelf(root), readPeers(root));
        } catch (Exception e) {
            return Status.NONE;
        }
    }

    /** Runs the tailscale CLI (auto-detected) and parses the status. {@link Status#NONE} if unavailable. */
    public static Status read() {
        return read("");
    }

    /** Runs the tailscale CLI. A non-blank {@code binOverride} forces the binary path. */
    public static Status read(String binOverride) {
        for (String bin : candidateBins(binOverride)) {
            Process p = start(bin);
            if (p == null) {
                continue;
            }
            try {
                if (p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return fromJson(out);
                }
            } catch (IOException e) {
                // CLI vanished mid-run; fall through to the next candidate.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                p.destroyForcibly();
            }
        }
        return Status.NONE;
    }

    private static Self readSelf(JsonObject root) {
        JsonElement el = root.get("Self");
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject s = el.getAsJsonObject();
        return new Self(magicDns(s), text(s, "HostName"),
                ipv4(s.get("TailscaleIPs")), bool(s, "Online"));
    }

    private static List<Peer> readPeers(JsonObject root) {
        List<Peer> out = new ArrayList<>();
        JsonElement pe = root.get("Peer");
        if (pe == null || !pe.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : pe.getAsJsonObject().entrySet()) {
            JsonElement v = entry.getValue();
            if (v == null || !v.isJsonObject()) {
                continue;
            }
            JsonObject o = v.getAsJsonObject();
            if (!bool(o, "Online")) {
                continue;
            }
            String ipv4 = ipv4(o.get("TailscaleIPs"));
            if (ipv4 == null || ipv4.isEmpty()) {
                continue;
            }
            out.add(new Peer(magicDns(o), text(o, "HostName"), ipv4, true));
        }
        return out;
    }

    /** MagicDNS names come back with a trailing dot; drop it for display and JOIN lines. */
    private static String magicDns(JsonObject o) {
        String dns = text(o, "DNSName");
        return dns.endsWith(".") ? dns.substring(0, dns.length() - 1) : dns;
    }

    private static String text(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive()) {
            return "";
        }
        String s = v.getAsString();
        return s == null ? "" : s.trim();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsBoolean();
    }

    /** First plain IPv4 in the TailscaleIPs array (Tailscale peers always expose one). */
    private static String ipv4(JsonElement ips) {
        if (ips == null || !ips.isJsonArray()) {
            return "";
        }
        for (JsonElement e : ips.getAsJsonArray()) {
            if (e == null || !e.isJsonPrimitive()) {
                continue;
            }
            String s = e.getAsString();
            if (s != null && s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return s;
            }
        }
        return "";
    }

    private static List<String> candidateBins(String override) {
        List<String> bins = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            bins.add(override.trim());
        }
        if (isWindows()) {
            bins.add("C:\\Program Files\\Tailscale\\tailscale.exe");
        } else {
            bins.add("/Applications/Tailscale.app/Contents/MacOS/Tailscale");
        }
        bins.add("tailscale"); // resolvable from PATH by ProcessBuilder
        return bins;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Process start(String bin) {
        try {
            return new ProcessBuilder(bin, "status", "--json").redirectErrorStream(true).start();
        } catch (IOException e) {
            return null; // binary not present on this candidate path
        }
    }
}
