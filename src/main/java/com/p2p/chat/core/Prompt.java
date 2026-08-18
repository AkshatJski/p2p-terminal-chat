package com.p2p.chat.core;

/**
 * Simple yes/no interaction used when a new peer fingerprint needs operator
 * confirmation. The terminal implementation reads {@code System.in}; tests can
 * inject a fixed answer.
 */
@FunctionalInterface
public interface Prompt {
    boolean ask(String message);
}
