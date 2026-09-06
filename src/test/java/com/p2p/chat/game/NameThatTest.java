package com.p2p.chat.game;

import com.p2p.chat.game.guess.HintSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Name That game: the {@link HintSource} contract (a live
 * source supplies the round when present), the built-in offline fallback, and
 * the category routing rules.
 */
class NameThatTest {

    @Test
    void fallsBackToBuiltInPacksWhenSourceIsEmpty() {
        NameThat g = new NameThat(category -> Optional.empty());
        String start = g.handle("alice", "start song");
        assertTrue(start.contains("Name That SONG"), start);
        assertTrue(start.contains("Hint 1:"), start);
        assertTrue(g.handle("alice", "quit").contains("The answer was: "));
    }

    @Test
    void usesProvidedDynamicClueAndAcceptsAnswer() {
        Clue clue = new Clue("Red Sun", new String[]{"Genre: Metal", "Released in 1999"});
        NameThat g = new NameThat(category -> Optional.of(clue));
        String start = g.handle("alice", "start song");
        assertTrue(start.contains("Hint 1: Genre: Metal"), start);
        assertTrue(g.handle("alice", "hint").contains("Released in 1999"));
        assertTrue(g.handle("alice", "Red Sun").contains("[Correct]"));
        g.handle("alice", "start song");
        assertTrue(g.handle("bob", "red sun!").contains("[Correct]"), "case/space-insensitive match");
    }

    @Test
    void gameCategoryAlwaysUsesBuiltInPack() {
        NameThat g = new NameThat(category -> Optional.of(new Clue("NeverUsed", new String[]{"X"})));
        String start = g.handle("alice", "start game");
        assertTrue(start.contains("Name That GAME"), start);
        assertFalse(start.contains("NeverUsed"), "game category must bypass the dynamic source");
        assertTrue(start.contains("Hint 1:"), start);
    }

    @Test
    void rejectsUnknownCategory() {
        NameThat g = new NameThat(category -> Optional.empty());
        assertTrue(g.handle("alice", "start banana").contains("Category must be movie, song or game"));
    }

    @Test
    void requiresStartBeforeAnswering() {
        NameThat g = new NameThat(category -> Optional.empty());
        assertTrue(g.handle("alice", "red sun").contains("Start a round first"));
    }
}