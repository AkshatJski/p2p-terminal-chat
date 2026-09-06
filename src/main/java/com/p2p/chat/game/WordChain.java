package com.p2p.chat.game;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multiplayer word chain. Any player starts a round with {@code @chain start};
 * the system seeds a short word, then everyone chains words that start with the
 * last letter of the previous word. A player cannot go twice in a row, so
 * others get a chance. Each word must be at least 3 letters, letters only, and
 * not repeat. {@code @chain score} shows the running tally and
 * {@code @chain quit} ends the round.
 */
public final class WordChain implements Game {
    private static final String[] SEEDS = {
            "cat", "dog", "sun", "car", "map", "bee", "fox", "key", "mix", "zip",
            "ant", "bug", "sea", "sky", "tea", "owl", "pen", "red", "ice", "gas"};

    private final Map<String, Integer> scores = new LinkedHashMap<>();
    private final Set<String> used = new java.util.HashSet<>();
    private String current;
    private String lastPlayer;
    private boolean running;

    @Override
    public String handle(String user, String args) {
        String a = args.trim().toLowerCase();
        if (a.isEmpty()) {
            return usage();
        }
        if (a.equals("start") || a.equals("new")) {
            reset();
            current = SEEDS[ThreadLocalRandom.current().nextInt(SEEDS.length)];
            used.add(current);
            running = true;
            return "Word chain started! Seed: " + current.toUpperCase() + ".\n"
                    + "Next word must start with '" + Character.toUpperCase(current.charAt(current.length() - 1))
                    + "': @chain <word>";
        }
        if (a.equals("score")) {
            return scores();
        }
        if (a.equals("quit") || a.equals("end")) {
            String top = scores();
            reset();
            return "Word chain ended.\n" + top;
        }
        if (!running) {
            return "No word chain running. Start one: @chain start";
        }
        if (user.equals(lastPlayer)) {
            return "Wait for someone else, " + user + ".";
        }
        String w = a;
        if (!w.matches("[a-z]{3,}")) {
            return "Words must be at least 3 letters, letters only.";
        }
        char need = current.charAt(current.length() - 1);
        if (w.charAt(0) != need) {
            return "Not valid: '" + w.toUpperCase() + "' must start with '"
                    + Character.toUpperCase(need) + "'.";
        }
        if (!used.add(w)) {
            return "'" + w.toUpperCase() + "' was already used.";
        }
        current = w;
        lastPlayer = user;
        scores.merge(user, 1, Integer::sum);
        return user + ": " + w.toUpperCase() + "\n"
                + "Next word must start with '"
                + Character.toUpperCase(current.charAt(current.length() - 1)) + "'.\n  " + scores();
    }

    private String scores() {
        if (scores.isEmpty()) {
            return "No words chained yet.";
        }
        StringBuilder sb = new StringBuilder("Chain scores");
        for (var e : scores.entrySet()) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void reset() {
        scores.clear();
        used.clear();
        current = null;
        lastPlayer = null;
        running = false;
    }

    private static String usage() {
        return "Word chain:\n  @chain start            begin a round (system seeds a word)\n"
                + "  @chain <word>           play a word starting with the last letter (not twice in a row)\n"
                + "  @chain score            show scores\n  @chain quit            end the round";
    }
}