package com.p2p.chat.config;

import java.io.IOException;
import java.io.InputStream;
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

    // Built-in defaults.
    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MESH_PORT = 8081;
    public static final int DEFAULT_MAX_FRAME = 1 << 20; // 1 MiB
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_MESH_TTL = 8;

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
            try (InputStream in = Files.newInputStream(c.configFile)) {
                c.props.load(in);
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

    private int bounded(String key, int def, int min, int max) {
        int v = getInt(key, def);
        if (v < min || v > max) {
            System.err.println("[Config] Value for " + key + " out of range [" + min + ".." + max + "] (using " + def + ")");
            return def;
        }
        return v;
    }
}
