package com.p2p.chat.game;

/** A single room game. Each instance holds one session and handles one command. */
public interface Game {
    /**
     * @param user     the username sending the command
     * @param argLine  everything typed after the game command (may be empty)
     * @return display text for the whole room (may be multi-line)
     */
    String handle(String user, String argLine);
}