package com.p2p.chat.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hangman with a gamemaster. Any player starts a round with
 * {@code @hang start <word>}; the word is hidden, the starter becomes the
 * gamemaster (they don't guess), and everyone else guesses letters with
 * {@code @hang <letter>}. Six wrong letters fills the gallows.
 */
public final class Hangman implements Game {
    private static final int LIVES = 6;

    private String word;
    private String master;
    private StringBuilder shown;
    private boolean[] guessed;
    private boolean[] wrong = new boolean[26];
    private int wrongCount;
    private boolean over;

    @Override
    public String handle(String user, String args) {
        String a = args.trim();
        if (a.isEmpty()) {
            return usage();
        }
        String[] p = a.split("\\s+");
        String act = p[0].toLowerCase();

        if (act.equals("start") || act.equals("new")) {
            if (p.length < 2) {
                return "Usage: @hang start <word> (the person starting hosts the word)";
            }
            String secret = p[1].trim().toLowerCase();
            if (!secret.matches("[a-z]{3,}")) {
                return "The hidden word must be at least 3 letters, letters only.";
            }
            reset();
            word = secret;
            master = user;
            guessed = new boolean[26];
            shown = new StringBuilder(secret.replaceAll("[a-z]", "_"));
            return "Hangman started by " + user + ". Everyone guesses letters: @hang <letter>";
        }
        if (act.equals("quit") || act.equals("end")) {
            String answer = word != null ? " The word was " + word.toUpperCase() : "";
            reset();
            return "Hangman ended." + answer;
        }

        if (word == null) {
            return "No hangman running. Start one: @hang start <word>";
        }
        if (user.equals(master)) {
            return "You picked the word, " + user + " — let the others guess!";
        }
        if (over) {
            return "The word was " + word.toUpperCase() + ". New round: @hang start <word>";
        }
        if (a.length() != 1 || !Character.isLetter(a.charAt(0))) {
            return "Guess a single letter, e.g. @hang e";
        }
        char ch = Character.toLowerCase(a.charAt(0));
        int idx = ch - 'a';
        if (guessed[idx] || wrong[idx]) {
            return "Already guessed '" + ch + "'.";
        }
        boolean hit = word.indexOf(ch) >= 0;
        if (hit) {
            guessed[idx] = true;
            for (int i = 0; i < word.length(); i++) {
                if (word.charAt(i) == ch) {
                    shown.setCharAt(i, ch);
                }
            }
            if (shown.indexOf("_") < 0) {
                over = true;
                return gallows() + "\n" + user + " solved it! The word was " + word.toUpperCase();
            }
            return "Good guess, " + user + "!\n  Word: " + shown;
        }
        wrong[idx] = true;
        wrongCount++;
        if (wrongCount >= LIVES) {
            over = true;
            return gallows() + "\nThe gallows are full! The word was " + word.toUpperCase()
                    + ". New round: @hang start <word>";
        }
        return "Wrong, " + user + " (" + (LIVES - wrongCount) + " wrong left).\n"
                + gallows() + "\n  Word: " + shown + "\n  Wrong: " + wrongLetters();
    }

    private String wrongLetters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            if (wrong[i]) {
                sb.append((char) ('a' + i)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private String gallows() {
        String head = wrongCount > 0 ? "O" : " ";
        String body = wrongCount > 1 ? "|" : " ";
        String armR = wrongCount > 2 ? "/" : " ";
        String armL = wrongCount > 3 ? "\\" : " ";
        String legR = wrongCount > 4 ? "/" : " ";
        String legL = wrongCount > 5 ? "\\" : " ";
        return "+---+\n"
                + "|   |\n"
                + "|   " + head + "\n"
                + "|  " + armL + body + armR + "\n"
                + "|  " + legL + " " + legR + "\n"
                + "_|_\n/   \\";
    }

    private void reset() {
        word = null;
        master = null;
        shown = null;
        guessed = null;
        wrong = new boolean[26];
        wrongCount = 0;
        over = false;
    }

    private static String usage() {
        return "Hangman:\n  @hang start <word>  host a new round with a hidden word\n"
                + "  @hang <letter>      guess a letter\n  @hang quit          end the round";
    }
}