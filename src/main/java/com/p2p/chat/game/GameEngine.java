package com.p2p.chat.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-side coordinator for the in-room terminal games. One engine per host; it
 * keeps per-room game state and turns typed commands into display lines that the
 * host relays to everyone in the room.
 *
 * <p>Friendly REPL commands ({@code @ttt}, {@code @chain}, {@code @hang},
 * {@code @guess}) are mapped either by the host console directly or by a client
 * into the protocol line {@code @GAME\0<gameId> [args]}.
 */
public final class GameEngine {
    private final Map<String, RoomGames> rooms = new ConcurrentHashMap<>();

    /** Routes a game command for a room. Returns display lines (multi-line ok). */
    public String command(String room, String user, String gameId, String argLine) {
        if (room == null || room.isEmpty()) {
            return "[Game] Join a room first: @join <room>";
        }
        Game game = rooms.computeIfAbsent(room, r -> new RoomGames()).byId(gameId);
        if (game == null) {
            return "[Game] Unknown game '" + gameId + "'. Try: ttt, chain, hang, guess";
        }
        return game.handle(user, argLine);
    }

    /** Drops all state for a room (called when the room empties). */
    public void cleanup(String room) {
        rooms.remove(room);
    }

    private static final class RoomGames {
        private final Game ttt = new TicTacToe();
        private final Game chain = new WordChain();
        private final Game hang = new Hangman();
        private final Game guess = new NameThat();

        Game byId(String id) {
            return switch (id.toLowerCase()) {
                case "ttt", "tictactoe" -> ttt;
                case "chain", "wordchain" -> chain;
                case "hang", "hangman" -> hang;
                case "guess", "name", "namethat" -> guess;
                default -> null;
            };
        }
    }
}