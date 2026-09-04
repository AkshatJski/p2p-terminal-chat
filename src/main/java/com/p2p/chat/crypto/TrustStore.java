package com.p2p.chat.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Trust-on-first-use (TOFU) store of verified connection fingerprints.
 *
 * <p>Keyed by the endpoint address and persisted to
 * {@code ~/.p2p-chat/trusted.txt} as {@code endpoint fingerprint} lines. The
 * first time a peer is seen its fingerprint is recorded (if the operator
 * confirms it); on later connections a mismatch is a red flag for a
 * man-in-the-middle and the connection is refused.
 */
public final class TrustStore {
    private static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"),
            ".p2p-chat", "trusted.txt");

    private final Path file;
    private final ConcurrentMap<String, String> byEndpoint = new ConcurrentHashMap<>();

    public TrustStore() throws IOException {
        this(DEFAULT_PATH);
    }

    public TrustStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length == 2) {
                    byEndpoint.put(parts[0], parts[1]);
                }
            }
        }
    }

    public boolean knows(String endpoint) {
        return byEndpoint.containsKey(endpoint);
    }

    public String get(String endpoint) {
        return byEndpoint.get(endpoint);
    }

    /** True when the endpoint was seen before with exactly this fingerprint. */
    public boolean matches(String endpoint, String fingerprint) {
        String known = byEndpoint.get(endpoint);
        return known != null && known.equalsIgnoreCase(fingerprint);
    }

    /** True when this fingerprint was ever recorded (under any endpoint). */
    public boolean hasFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        for (String known : byEndpoint.values()) {
            if (fingerprint.equalsIgnoreCase(known)) {
                return true;
            }
        }
        return false;
    }

    public void remember(String endpoint, String fingerprint) throws IOException {
        byEndpoint.put(endpoint, fingerprint);
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        byEndpoint.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Records a peer identity keyed by its fingerprint itself, so that inbound
     * peers sharing one address (loopback, NAT) do not overwrite each other.
     */
    public void rememberFingerprint(String fingerprint) throws IOException {
        remember(prefix(fingerprint), fingerprint);
    }

    private static String prefix(String fingerprint) {
        return "fp:" + fingerprint;
    }
}
