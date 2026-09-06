package com.p2p.chat.core;

import com.p2p.chat.config.Config;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives files announced with {@code @FILE_START} / {@code @FILE_CHUNK} and
 * writes them chunk by chunk into the configured download directory
 * ({@code ./downloads} by default). Arrival order is guaranteed per channel, so
 * chunks are appended as they come; a transfer completes once every chunk has
 * arrived and the {@code .part} file is renamed to its final name.
 *
 * <p>The same instance is shared by the room host (its console is a member too)
 * and every client, so both can download files sent in a room.
 */
public final class FileReceiver {
    /** Default download folder, relative to the working directory. */
    public static final String DEFAULT_DIR = "downloads";

    private final Path dir;
    private final Map<String, Download> active = new ConcurrentHashMap<>();

    public FileReceiver() {
        this(Path.of(DEFAULT_DIR));
    }

    public FileReceiver(Path dir) {
        this.dir = dir;
    }

    /** Announces a new file. Returns a status line for the UI, or null. */
    public synchronized String start(String fid, String room, String filename,
                                     long totalBytes, int chunkCount, String sender) {
        try {
            Files.createDirectories(dir);
            String name = sanitize(filename);
            Path target = unique(dir.resolve(name));
            Path part = dir.resolve("." + name + "." + randomSuffix() + ".part");
            Download d = new Download(fid, room, target, part,
                    new BufferedOutputStream(Files.newOutputStream(part)), chunkCount, sender);
            active.put(fid, d);
            if (chunkCount <= 0) {
                return complete(fid);
            }
            return "[File] " + sender + " is sending " + name
                    + " (" + human(totalBytes) + ", " + chunkCount + " chunks) -> " + dir;
        } catch (IOException e) {
            active.remove(fid);
            return "[File] Could not receive " + filename + ": " + e.getMessage();
        }
    }

    /** Appends one base64-encoded chunk. Returns a status line when the file is done, or null. */
    public synchronized String chunk(String fid, int index, String b64) {
        Download d = active.get(fid);
        if (d == null) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            d.out.write(data);
            d.bytes += data.length;
            d.received++;
            if (d.received >= d.chunkCount) {
                return complete(fid);
            }
            int pct = (int) (d.received * 100.0 / d.chunkCount);
            if (pct / 25 > d.lastPct / 25) {
                d.lastPct = (pct / 25) * 25;
                return "[File] " + d.target.getFileName() + ": " + d.lastPct
                        + "% (" + d.received + "/" + d.chunkCount + " chunks)";
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return abort(fid, e.getMessage());
        }
    }

    /** Cancels an in-progress transfer and deletes its partial file. */
    public synchronized String abort(String fid, String reason) {
        Download d = active.remove(fid);
        if (d == null) {
            return null;
        }
        closeQuietly(d.out);
        try {
            Files.deleteIfExists(d.part);
        } catch (IOException ignored) {
        }
        return "[File] Transfer of " + d.target.getFileName() + " aborted"
                + (reason == null || reason.isEmpty() ? "" : ": " + reason);
    }

    /** Cancels an active transfer by its final filename. Returns a status line or an error line. */
    public synchronized String cancelByName(String filename) {
        String match = sanitize(filename);
        for (Download d : active.values()) {
            if (d.target.getFileName().toString().equals(match)) {
                return abort(d.fid, "cancelled locally");
            }
        }
        return "[File] No active download named '" + filename + "'";
    }

    /** Renames the .part file to its final name once all chunks arrived. */
    private String complete(String fid) {
        Download d = active.remove(fid);
        if (d == null) {
            return null;
        }
        closeQuietly(d.out);
        try {
            Path finalTarget = unique(d.target);
            Files.move(d.part, finalTarget, StandardCopyOption.REPLACE_EXISTING);
            return "[File] Saved " + finalTarget.getFileName() + " (" + human(d.bytes)
                    + ") to " + finalTarget.toAbsolutePath();
        } catch (IOException e) {
            return "[File] Failed to finalize " + d.target.getFileName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Sending helpers (shared by the host console and clients).
    // ------------------------------------------------------------------

    /** Chunk payload size in bytes, sized to fit well under the frame limit. */
    public static int chunkSize() {
        return Math.min(48 * 1024, Math.max(1024, Config.get().getMaxFrame() / 2));
    }

    public static int chunkCount(long size, int chunkSize) {
        return (int) ((size + chunkSize - 1) / chunkSize);
    }

    public static byte[] chunk(byte[] all, int index, int chunkSize) {
        int from = index * chunkSize;
        int to = Math.min(all.length, from + chunkSize);
        return Arrays.copyOfRange(all, from, to);
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    // ------------------------------------------------------------------

    private static String sanitize(String filename) {
        if (filename == null) {
            return "file";
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        return name;
    }

    private static Path unique(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        Path parent = target.getParent();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = parent.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(name + "-" + System.currentTimeMillis());
    }

    private static String randomSuffix() {
        return Integer.toHexString((int) (Math.random() * 0xFFFFF));
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Download {
        final String fid;
        final String room;
        final Path target;
        final Path part;
        final OutputStream out;
        final int chunkCount;
        final String sender;
        int received;
        long bytes;
        int lastPct;

        Download(String fid, String room, Path target, Path part, OutputStream out,
                 int chunkCount, String sender) {
            this.fid = fid;
            this.room = room;
            this.target = target;
            this.part = part;
            this.out = out;
            this.chunkCount = chunkCount;
            this.sender = sender;
        }
    }
}
