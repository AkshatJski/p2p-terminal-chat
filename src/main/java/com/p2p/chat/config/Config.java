package com.p2p.chat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Central configuration. Everything the app can tune lives here and is
 * resolved with the precedence {@code CLI arguments &gt; config file &gt; defaults}.
 *
 * <p>Settings are read from one of three places (first match wins):
 * <ol>
 *   <li>the file given by {@code --config &lt;path&gt;},</li>
 *   <li>a {@code config.properties} file in the working directory,</li>
 *   <li>{@code ~/.p2p-chat/config.properties}.</li>
 * </ol>
 * If none exists the built-in defaults are used. Every option can also be
 * overridden on the command line, e.g. {@code java -jar app.jar --port 9090}.
 *
 * <p>See {@code CONFIGURATION.md} in the project root for the full list of
 * options and how to change them.
 */
public final class Config {
    // Property keys (used in config.properties and on the command line).
    public static final String KEY_PORT = "port";
    public static final String KEY_MESH_PORT = "mesh.port";
    public static final String KEY_IDENTITY_FILE = "identity.file";
    public static final String KEY_TRUST_FILE = "trust.file";
    public static final String KEY_MAX_FRAME = "frame.max.size";
    public static final String KEY_HANDSHAKE_TIMEOUT = "handshake.timeout.ms";
    public static final String KEY_MESH_TTL = "mesh.ttl";
    public static final String KEY_DOWNLOAD_DIR = "download.dir";
    public static final String KEY_RECONNECT_ENABLED = "reconnect.enabled";
    public static final String KEY_RECONNECT_MAX = "reconnect.max";
    public static final String KEY_RECONNECT_BASE_MS = "reconnect.base.ms";
    public static final String KEY_RECONNECT_MAX_MS = "reconnect.max.ms";
    public static final String KEY_TRUST_SERVER_ENABLED = "trust.server.enabled";
    public static final String KEY_TRUST_SERVER_PORT = "trust.server.port";
    public static final String KEY_GUESS_DYNAMIC_ENABLED = "guess.dynamic.enabled";
    public static final String KEY_GUESS_DYNAMIC_TIMEOUT_MS = "guess.dynamic.timeout.ms";
    public static final String KEY_GUESS_DYNAMIC_BASE_URL = "guess.dynamic.base.url";
    public static final String KEY_DISCOVERY_ENABLED = "discovery.enabled";
    public static final String KEY_DISCOVERY_PORT = "discovery.port";
    public static final String KEY_DISCOVERY_INTERVAL_MS = "discovery.interval.ms";
    public static final String KEY_DISCOVERY_SCAN_MS = "discovery.scan.ms";
    public static final String KEY_DISCOVERY_INTERFACE = "discovery.interface";
    public static final String KEY_DISCOVERY_TAILSCALE_ENABLED = "discovery.tailscale.enabled";
    public static final String KEY_DISCOVERY_TAILSCALE_BIN = "discovery.tailscale.bin";

    // Built-in defaults.
    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MESH_PORT = 8081;
    public static final int DEFAULT_MAX_FRAME = 1 << 20; // 1 MiB
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_MESH_TTL = 8;
    public static final boolean DEFAULT_RECONNECT_ENABLED = true;
    public static final int DEFAULT_RECONNECT_MAX = 12;
    public static final long DEFAULT_RECONNECT_BASE_MS = 1_000;
    public static final long DEFAULT_RECONNECT_MAX_MS = 30_000;
    public static final boolean DEFAULT_TRUST_SERVER_ENABLED = true;
    public static final int DEFAULT_TRUST_SERVER_PORT = 0; // auto-pick
    public static final boolean DEFAULT_GUESS_DYNAMIC_ENABLED = true;
    public static final int DEFAULT_GUESS_DYNAMIC_TIMEOUT_MS = 2_500;
    public static final boolean DEFAULT_DISCOVERY_ENABLED = true;
    public static final int DEFAULT_DISCOVERY_PORT = 8082;
    public static final long DEFAULT_DISCOVERY_INTERVAL_MS = 2_000;
    public static final long DEFAULT_DISCOVERY_SCAN_MS = 3_000;
    public static final String DEFAULT_DISCOVERY_INTERFACE = "";
    public static final boolean DEFAULT_DISCOVERY_TAILSCALE_ENABLED = true;
    public static final String DEFAULT_DISCOVERY_TAILSCALE_BIN = "";

    public static final Path DEFAULT_DIR = Path.of(System.getProperty("user.home"), ".p2p-chat");
    public static final Path DEFAULT_CONFIG_FILE = DEFAULT_DIR.resolve("config.properties");
    public static final Path DEFAULT_IDENTITY_FILE = DEFAULT_DIR.resolve("identity.key");
    public static final Path DEFAULT_TRUST_FILE = DEFAULT_DIR.resolve("trusted.txt");
    public static final Path DEFAULT_DOWNLOAD_DIR = Path.of("downloads");

    // Command-line option names.
    public static final String OPTION_CONFIG = "--config";
    public static final String OPTION_PORT = "--port";
    public static final String OPTION_PORT_SHORT = "-p";
    public static final String OPTION_MESH_PORT = "--mesh-port";
    public static final String OPTION_MESH_PORT_SHORT = "-m";
    public static final String OPTION_IDENTITY = "--identity";
    public static final String OPTION_TRUST = "--trust";

    private static final Set<String> OPTIONS_WITH_VALUE = Set.of(
            OPTION_CONFIG, OPTION_PORT, OPTION_PORT_SHORT,
            OPTION_MESH_PORT, OPTION_MESH_PORT_SHORT, OPTION_IDENTITY, OPTION_TRUST);

    private static volatile Config instance;

    private final Properties props = new Properties();
    private Path configFile;

    private Config() {
    }

    /**
     * Loads configuration from the command line and any config file, and makes
     * it the process-wide instance returned by {@link #get()}. Call this once at
     * startup, before any component touches its settings.
     */
    public static Config load(String... args) {
        Config c = new Config();
        c.configFile = c.findConfigFile(args);
        if (c.configFile != null && Files.isRegularFile(c.configFile)) {
            try {
                loadProperties(c.props, Files.readAllLines(c.configFile));
                System.out.println("[Config] Loaded " + c.configFile);
            } catch (IOException e) {
                System.err.println("[Config] Could not read " + c.configFile + ": " + e.getMessage());
            }
        } else {
            System.out.println("[Config] No config file found; using defaults (see CONFIGURATION.md).");
        }
        c.applyCliOverrides(args);
        instance = c;
        return c;
    }

    /**
     * Reads simple {@code key=value} lines without the Java properties escape
     * rules. This keeps Windows paths such as {@code C:\Users\...} intact,
     * which {@link Properties#load} would silently mangle.
     */
    private static void loadProperties(java.util.Properties props, List<String> lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            int colon = line.indexOf(':');
            int cut = Math.min(eq < 0 ? Integer.MAX_VALUE : eq, colon < 0 ? Integer.MAX_VALUE : colon);
            String key = cut == Integer.MAX_VALUE ? line : line.substring(0, cut);
            String value = cut == Integer.MAX_VALUE ? "" : line.substring(cut + 1);
            key = key.trim();
            if (key.isEmpty()) {
                continue;
            }
            props.setProperty(key, value.trim());
        }
    }

    /** The process-wide config; lazily loads the defaults if {@link #load} was not called. */
    public static Config get() {
        if (instance == null) {
            instance = new Config();
            instance.configFile = instance.findConfigFile(new String[0]);
            instance.applyCliOverrides(new String[0]);
        }
        return instance;
    }

    /** CLI arguments that are not options (e.g. the {@code tcp:host:port} link specs). */
    public static List<String> positional(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                continue;
            }
            if (i > 0 && OPTIONS_WITH_VALUE.contains(args[i - 1])) {
                continue; // value of the previous option
            }
            out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Option accessors.
    // ------------------------------------------------------------------

    /** TCP port the chat host listens on and join connects to. */
    public int getPort() {
        return bounded(KEY_PORT, DEFAULT_PORT, 1, 65535);
    }

    /** TCP port the mesh Listen mode binds to. */
    public int getMeshPort() {
        return bounded(KEY_MESH_PORT, DEFAULT_MESH_PORT, 1, 65535);
    }

    /** Where the persistent X25519 identity key is stored. */
    public Path getIdentityFile() {
        return path(KEY_IDENTITY_FILE, DEFAULT_IDENTITY_FILE);
    }

    /** Where the trusted-fingerprint store lives. */
    public Path getTrustFile() {
        return path(KEY_TRUST_FILE, DEFAULT_TRUST_FILE);
    }

    /** Maximum accepted encrypted frame size in bytes. */
    public int getMaxFrame() {
        return bounded(KEY_MAX_FRAME, DEFAULT_MAX_FRAME, 1024, Integer.MAX_VALUE);
    }

    /** Handshake timeout in milliseconds. */
    public int getHandshakeTimeoutMs() {
        return bounded(KEY_HANDSHAKE_TIMEOUT, DEFAULT_HANDSHAKE_TIMEOUT_MS, 1_000, 120_000);
    }

    /** Mesh flooding time-to-live (maximum hop count). */
    public int getMeshTtl() {
        return bounded(KEY_MESH_TTL, DEFAULT_MESH_TTL, 1, 64);
    }

    /** Where files received in a room are saved (relative to the working dir). */
    public Path getDownloadDir() {
        return path(KEY_DOWNLOAD_DIR, DEFAULT_DOWNLOAD_DIR);
    }

    /** Whether auto-reconnect is enabled. */
    public boolean isReconnectEnabled() {
        return getBool(KEY_RECONNECT_ENABLED, DEFAULT_RECONNECT_ENABLED);
    }

    /** Max reconnect attempts before giving up. */
    public int getReconnectMaxRetries() {
        return bounded(KEY_RECONNECT_MAX, DEFAULT_RECONNECT_MAX, 1, 100);
    }

    /** Base delay between reconnect attempts in milliseconds. */
    public long getReconnectBaseDelayMs() {
        return getLong(KEY_RECONNECT_BASE_MS, DEFAULT_RECONNECT_BASE_MS);
    }

    /** Maximum delay cap between reconnect attempts in milliseconds. */
    public long getReconnectMaxDelayMs() {
        return getLong(KEY_RECONNECT_MAX_MS, DEFAULT_RECONNECT_MAX_MS);
    }

    /** Whether the HTTP trust verification server is enabled. */
    public boolean isTrustServerEnabled() {
        return getBool(KEY_TRUST_SERVER_ENABLED, DEFAULT_TRUST_SERVER_ENABLED);
    }

    /** Port for the HTTP trust server (0 = auto-pick a free port). */
    public int getTrustServerPort() {
        return bounded(KEY_TRUST_SERVER_PORT, DEFAULT_TRUST_SERVER_PORT, 0, 65535);
    }

    /** Whether the Name That game fetches live keyless hints (iTunes/Apple charts). */
    public boolean isGuessDynamicEnabled() {
        return getBool(KEY_GUESS_DYNAMIC_ENABLED, DEFAULT_GUESS_DYNAMIC_ENABLED);
    }

    /** Per-request timeout for the dynamic hint fetch, in milliseconds. */
    public int getGuessDynamicTimeoutMs() {
        return bounded(KEY_GUESS_DYNAMIC_TIMEOUT_MS, DEFAULT_GUESS_DYNAMIC_TIMEOUT_MS, 500, 15_000);
    }

    /**
     * Optional override for the dynamic hint endpoints (both the chart and the
     * lookup host) — intended for self-hosted mirrors and tests. Empty means
     * the default Apple hosts are used.
     */
    public String getGuessDynamicBaseUrl() {
        String v = props.getProperty(KEY_GUESS_DYNAMIC_BASE_URL);
        return v == null ? "" : v.trim();
    }

    /** Whether hosts announce themselves (and joiners scan) on the LAN. */
    public boolean isDiscoveryEnabled() {
        return getBool(KEY_DISCOVERY_ENABLED, DEFAULT_DISCOVERY_ENABLED);
    }

    /** UDP port used for LAN beacon discovery. */
    public int getDiscoveryPort() {
        return bounded(KEY_DISCOVERY_PORT, DEFAULT_DISCOVERY_PORT, 1, 65535);
    }

    /** Milliseconds between host beacon broadcasts. */
    public long getDiscoveryIntervalMs() {
        return getLong(KEY_DISCOVERY_INTERVAL_MS, DEFAULT_DISCOVERY_INTERVAL_MS);
    }

    /** How long a joiner listens for beacons before giving up, in milliseconds. */
    public long getDiscoveryScanMs() {
        return getLong(KEY_DISCOVERY_SCAN_MS, DEFAULT_DISCOVERY_SCAN_MS);
    }

    /**
     * Optional network interface to force for beacons and scans (an interface
     * name such as {@code en0}, or its display name like {@code Wi-Fi}). Empty
     * means "pick the best interface automatically".
     */
    public String getDiscoveryInterface() {
        String v = props.getProperty(KEY_DISCOVERY_INTERFACE);
        return v == null ? DEFAULT_DISCOVERY_INTERFACE : v.trim();
    }

    /** Whether the join prompt also lists online hosts from the Tailscale tailnet. */
    public boolean isTailscaleDiscoveryEnabled() {
        return getBool(KEY_DISCOVERY_TAILSCALE_ENABLED, DEFAULT_DISCOVERY_TAILSCALE_ENABLED);
    }

    /** Optional path to the {@code tailscale} CLI. Empty = auto-detect. */
    public String getTailscaleBin() {
        String v = props.getProperty(KEY_DISCOVERY_TAILSCALE_BIN);
        return v == null ? DEFAULT_DISCOVERY_TAILSCALE_BIN : v.trim();
    }

    /** The config file that was used, or the default path when none existed. */
    public Path getConfigFile() {
        return configFile;
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    private Path findConfigFile(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(OPTION_CONFIG) && !args[i + 1].startsWith("--")) {
                return Path.of(args[i + 1]);
            }
        }
        Path local = Path.of("config.properties");
        if (Files.isRegularFile(local)) {
            return local;
        }
        return DEFAULT_CONFIG_FILE;
    }

    private void applyCliOverrides(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String key = null;
            switch (args[i]) {
                case OPTION_PORT, OPTION_PORT_SHORT -> key = KEY_PORT;
                case OPTION_MESH_PORT, OPTION_MESH_PORT_SHORT -> key = KEY_MESH_PORT;
                case OPTION_IDENTITY -> key = KEY_IDENTITY_FILE;
                case OPTION_TRUST -> key = KEY_TRUST_FILE;
                case OPTION_CONFIG -> { /* consumed by findConfigFile */ }
                default -> { /* positional spec or unknown option; ignored here */ }
            }
            if (key != null) {
                if (i + 1 < args.length) {
                    props.setProperty(key, args[++i]);
                } else {
                    System.err.println("[Config] Missing value for " + args[i]);
                }
            }
        }
    }

    private Path path(String key, Path def) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        v = v.trim();
        if (v.startsWith("${user.home}")) {
            v = System.getProperty("user.home") + v.substring("${user.home}".length());
        }
        return Path.of(v);
    }

    private int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[Config] Invalid value for " + key + ": " + v + " (using " + def + ")");
            return def;
        }
    }

    private long getLong(String key, long def) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[Config] Invalid value for " + key + ": " + v + " (using " + def + ")");
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return v.trim().equalsIgnoreCase("true") || v.trim().equals("1");
    }

    private int bounded(String key, int def, int min, int max) {
        int v = getInt(key, def);
        if (v < min || v > max) {
            System.err.println("[Config] Value for " + key + " out of range [" + min + ".." + max + "] (using " + def + ")");
            return def;
        }
        return v;
    }
}
