package com.p2p.chat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory message store, keyed by room.
 *
 * <p>Each room keeps a bounded list of recent {@link Entry} objects so the host
 * can replay history on a {@code @history} request. Entries are lost on
 * process exit (no persistence).
 */
public class MessageHistory {
    /** A single stored message. */
    public static final class Entry {
        public final String user;
        public final String text;

        Entry(String user, String text) {
            this.user = user;
            this.text = text;
        }
    }

    private static final int DEFAULT_LIMIT = 500;

    private final Map<String, CopyOnWriteArrayList<Entry>> rooms = new ConcurrentHashMap<>();
    private final int limit;

    public MessageHistory() {
        this(DEFAULT_LIMIT);
    }

    public MessageHistory(int limit) {
        this.limit = limit;
    }

    /** Stores a message in a room, trimming to the configured limit. */
    public void add(String room, String user, String text) {
        if (room == null || room.isEmpty()) {
            return;
        }
        CopyOnWriteArrayList<Entry> list = rooms.computeIfAbsent(room, k -> new CopyOnWriteArrayList<>());
        list.add(new Entry(user == null ? "" : user, text == null ? "" : text));
        while (list.size() > limit) {
            list.remove(0);
        }
    }

    /** Returns the last {@code count} entries for a room (oldest first). */
    public List<Entry> recent(String room, int count) {
        CopyOnWriteArrayList<Entry> list = rooms.get(room);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, list.size() - count);
        return list.subList(from, list.size());
    }

    /** True if the room has any stored history. */
    public boolean has(String room) {
        CopyOnWriteArrayList<Entry> list = rooms.get(room);
        return list != null && !list.isEmpty();
    }

    /** Clears history for a room. */
    public void clear(String room) {
        rooms.remove(room);
    }
}
