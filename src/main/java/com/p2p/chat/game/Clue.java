package com.p2p.chat.game;

/**
 * A single "guess the title" puzzle: the hidden {@code title} plus the ladder
 * of hints revealed one at a time. Null or empty hints are skipped by the game
 * engine when advancing.
 */
public record Clue(String title, String[] hints) {
}