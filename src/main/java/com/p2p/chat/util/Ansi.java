package com.p2p.chat.util;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ANSI escape code helpers for colored terminal output.
 *
 * <p>On modern terminals (Windows Terminal, PowerShell 7, Linux/macOS),
 * ANSI codes are rendered as colors. If the output is piped or the terminal
 * doesn't support VTP, colors are stripped automatically.
 */
public final class Ansi {
    public static final String RESET   = "\033[0m";
    public static final String BOLD    = "\033[1m";
    public static final String DIM     = "\033[2m";
    public static final String RED     = "\033[31m";
    public static final String GREEN   = "\033[32m";
    public static final String YELLOW  = "\033[33m";
    public static final String BLUE    = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN    = "\033[36m";
    public static final String WHITE   = "\033[37m";
    public static final String BRIGHT_RED     = "\033[91m";
    public static final String BRIGHT_GREEN   = "\033[92m";
    public static final String BRIGHT_YELLOW  = "\033[93m";
    public static final String BRIGHT_BLUE    = "\033[94m";
    public static final String BRIGHT_MAGENTA = "\033[95m";
    public static final String BRIGHT_CYAN    = "\033[96m";
    public static final String BRIGHT_WHITE   = "\033[97m";

    private static final String[] USER_COLORS = {
        BRIGHT_CYAN, BRIGHT_GREEN, BRIGHT_YELLOW, BRIGHT_BLUE,
        BRIGHT_MAGENTA, CYAN, GREEN, YELLOW, BLUE, MAGENTA
    };

    private static boolean enabled = true;
    private static final Map<String, String> colorCache = new ConcurrentHashMap<>();

    private Ansi() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String color(String ansi, String text) {
        if (!enabled || text == null) {
            return text;
        }
        return ansi + text + RESET;
    }

    public static String bold(String ansi, String text) {
        if (!enabled || text == null) {
            return text;
        }
        return BOLD + ansi + text + RESET;
    }

    /** Returns a consistent color for a given username. Same name always gets the same color. */
    public static String userColor(String name) {
        if (!enabled || name == null) {
            return name;
        }
        return color(colorCache.computeIfAbsent(name, Ansi::hashColor), name);
    }

    /** Returns the raw ANSI code for a username (without wrapping text). */
    public static String userAnsi(String name) {
        if (!enabled || name == null) {
            return "";
        }
        return colorCache.computeIfAbsent(name, Ansi::hashColor);
    }

    private static String hashColor(String name) {
        int hash = 0;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash = 31 * hash + b;
        }
        return USER_COLORS[Math.abs(hash) % USER_COLORS.length];
    }

    public static void detectAndEnable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            enabled = true;
        } else {
            enabled = true;
        }
        if (System.console() == null) {
            enabled = false;
        }
    }
}
