package com.p2p.chat.game.guess;

import com.p2p.chat.game.Clue;
import java.util.Optional;

/**
 * Supplies a {@link Clue} for a "Name That" category without ever failing the
 * caller: every network or parse problem must be reported as
 * {@link Optional#empty()} so the game engine can silently fall back to its
 * built-in packs.
 */
public interface HintSource {
    Optional<Clue> fetch(String category);
}