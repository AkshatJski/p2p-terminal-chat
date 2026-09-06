package com.p2p.chat.discovery;

/**
 * Wire format and shared constants for LAN beacon discovery.
 *
 * <p>Hosts advertise themselves by dropping small UDP datagrams onto a private
 * multicast group. Peers joining a room listen on that group for a moment and
 * list whoever responded, so no one has to phone a friend to find out the
 * host's IP.
 *
 * <p>The payload is a single UTF-8 line:
 * <pre>P2PC/1|username|deviceId|tcpPort</pre>
 *
 * <p>The connect {@code tcpPort} is the peer-to-peer chat port (default 8080),
 * NOT this discovery port.
 */
public final class Discovery {
    /** Multicast group used for LAN chat-host discovery. */
    public static final String GROUP = "239.255.77.7";
    /** Default UDP port beacons are sent to / discovered on. */
    public static final int DEFAULT_PORT = 8082;
    /** Magic + protocol version prefix of every beacon payload. */
    public static final String MAGIC = "P2PC";
    public static final int VERSION = 1;
    static final String SEP = "|";

    private Discovery() {
    }

    /** Builds a beacon line. Unsafe characters are scrubbed to keep the line parseable. */
    public static String encode(String username, String deviceId, int tcpPort) {
        return MAGIC + "/" + VERSION + SEP + scrub(username) + SEP + scrub(deviceId) + SEP + tcpPort;
    }

    /**
     * Parses a beacon line into fields, or returns {@code null} if it is not a
     * valid {@code P2PC/1} beacon (e.g. a different app on the same group).
     */
    public static String[] decode(String line) {
        if (line == null) {
            return null;
        }
        String[] f = line.split("\\|", -1);
        if (f.length != 4
                || !f[0].equals(MAGIC + "/" + VERSION)
                || f[1].isEmpty()
                || f[2].isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(f[3]);
            if (port < 1 || port > 65535) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return f;
    }

    private static String scrub(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(SEP, "_").replace('\u0000', '_').trim();
    }
}