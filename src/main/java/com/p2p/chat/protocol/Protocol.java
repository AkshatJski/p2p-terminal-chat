package com.p2p.chat.protocol;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Application-layer protocol spoken inside the encrypted channel.
 *
 * <p>Every message is a UTF-8 string of the form {@code TYPE\0arg\0arg\0...}
 * where {@code \u0000} is the field separator and the last field is never
 * split (chat text may contain anything). The host relays room messages, so
 * rooms live on the host and clients only ever talk to their host.
 */
public final class Protocol {
    private Protocol() {
    }

    /** Field separator between the command type and its arguments. */
    public static final char SEP = '\u0000';

    // Client -> Host
    public static final String NAME = "@NAME";        // first message: NAME\0<username>
    public static final String JOIN = "@JOIN";        // JOIN\0<room>
    public static final String LEAVE = "@LEAVE";      // LEAVE
    public static final String LIST = "@LIST";        // LIST
    public static final String USERS = "@USERS";      // USERS
    public static final String MSG = "@MSG";          // MSG\0<room>\0<text>
    public static final String FILE_START = "@FILE_START"; // FILE_START\0<fid>\0<room>\0<name>\0<bytes>\0<chunks>\0<sender>\0<host>
    public static final String FILE_CHUNK = "@FILE_CHUNK"; // FILE_CHUNK\0<fid>\0<room>\0<index>\0<b64 data>
    public static final String FILE_ABORT = "@FILE_ABORT"; // FILE_ABORT\0<fid>\0<room>\0<reason>

    // Host -> Client
    public static final String FROM = "@FROM";        // FROM\0<room>\0<user>\0<host>\0<text>
    public static final String ROOMS = "@ROOMS";      // ROOMS\0<comma,separated,list>
    public static final String ROOM_USERS = "@ROOM_USERS"; // ROOM_USERS\0<room>\0<comma,list>
    public static final String ERR = "@ERR";          // ERR\0<text>
    public static final String SYS = "@SYS";          // SYS\0<text>

    // Host <-> Host (bridging)
    public static final String BRIDGE = "@BRIDGE";    // BRIDGE\0<comma,rooms> - link handshake
    public static final String BRIDGE_MSG = "@BRIDGE_MSG"; // BRIDGE_MSG\0<rid>\0<inner protocol line>

    // Typing indicator
    public static final String TYPING = "@TYPING";           // TYPING\0<room> — user started typing
    public static final String TYPING_STOP = "@TYPING_STOP"; // TYPING_STOP\0<room> — user stopped typing

    // Host moderation
    public static final String KICK = "@KICK";   // KICK\0<target-username> — disconnect a user
    public static final String BAN = "@BAN";     // BAN\0<target-username> — kick + prevent rejoining
    public static final String UNBAN = "@UNBAN"; // UNBAN\0<target-username> — remove a ban
    public static final String BANNED = "@BANNED"; // BANNED — user was refused entry (host -> client)

    // Message history
    public static final String HISTORY = "@HISTORY";       // HISTORY\0<room>\0<count> — request last N
    public static final String HIST_ENTRY = "@HIST_ENTRY"; // HIST_ENTRY\0<user>\0<text> — a history message
    public static final String HIST_END = "@HIST_END";     // HIST_END — end of history

    public static final int MAX_USERNAME_LEN = 32;
    public static final int MAX_ROOM_LEN = 64;

    /** Builds a protocol line: {@code command(MSG, "room", "hi there")} -> "MSG\0room\0hi there". */
    public static String command(String type, String... args) {
        StringBuilder line = new StringBuilder(type);
        for (String arg : args) {
            line.append(SEP).append(arg == null ? "" : arg);
        }
        return line.toString();
    }

    /** Splits a protocol line into fields, keeping trailing empty ones. */
    public static String[] split(String line) {
        return line.split(String.valueOf(SEP), -1);
    }

    /** Validates a username the way the host enforces it. */
    public static boolean isValidUsername(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_USERNAME_LEN) {
            return false;
        }
        return name.indexOf(SEP) < 0 && name.chars().noneMatch(Character::isWhitespace);
    }

    public static boolean isValidRoom(String room) {
        return room != null && !room.isEmpty() && room.length() <= MAX_ROOM_LEN && room.indexOf(SEP) < 0;
    }

    /** Human-readable rendering of a protocol line, used by the terminal UI. */
    public static String display(String line) {
        String[] f = split(line);
        return switch (f[0]) {
            case FROM -> f.length >= 5
                    ? "[Room " + f[1] + "] " + f[2] + (f[3].isEmpty() ? "" : "@" + f[3]) + ": " + f[4]
                    : line;
            case SYS -> "[System] " + (f.length > 1 ? f[1] : "");
            case ERR -> "[Error] " + (f.length > 1 ? f[1] : "");
            case ROOMS -> "[Rooms] " + (f.length > 1 && !f[1].isEmpty() ? f[1] : "none");
            case ROOM_USERS -> f.length >= 3
                    ? "[Users in " + f[1] + "] " + (f[2].isEmpty() ? "none" : f[2])
                    : line;
            case FILE_START -> f.length >= 7
                    ? "[File] " + f[6] + " is sending " + f[3] + " (" + f[4] + " bytes, " + f[5] + " chunks)"
                    : line;
            case FILE_ABORT -> "[File] Transfer aborted"
                    + (f.length > 3 && !f[3].isEmpty() ? ": " + f[3] : "");
            case TYPING -> f.length >= 3
                    ? "[*] " + f[2] + " is typing..."
                    : "";
            case TYPING_STOP -> ""; // silently consumed
            default -> line;
        };
    }

    /** "alpha(2),beta(1)" room list for {@code @ROOMS}. */
    public static String roomList(String roomsCsv) {
        return Arrays.stream(roomsCsv.split(","))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }
}
