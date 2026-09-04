package com.p2p.chat.core;

import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.crypto.TrustStore;
import com.p2p.chat.util.Ansi;
import java.io.IOException;

/**
 * Trust-on-first-use gate for connection fingerprints.
 *
 * <p>Clients confirm new hosts interactively (a man-in-the-middle could be
 * impersonating the host). Hosts auto-trust new inbound peers but refuse a
 * known peer whose fingerprint suddenly changed.
 */
public final class TrustGate {
    private final TrustStore store;

    public TrustGate(TrustStore store) {
        this.store = store;
    }

    public TrustStore getStore() {
        return store;
    }

    /** Client side: block on the operator's confirmation for a first contact. */
    public boolean verifyClient(SecureChannel channel, String endpoint, Prompt prompt) throws IOException {
        String fingerprint = channel.getFingerprint();
        if (store.matches(endpoint, fingerprint)) {
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Security] Fingerprint verified (previously trusted)."));
            return true;
        }
        if (store.knows(endpoint)) {
            System.out.println(Ansi.color(Ansi.BRIGHT_RED, "[Security] FINGERPRINT MISMATCH for " + endpoint + "!"));
            System.out.println(Ansi.color(Ansi.RED, "  known  : " + store.get(endpoint)));
            System.out.println(Ansi.color(Ansi.RED, "  seen   : " + fingerprint));
            System.out.println(Ansi.color(Ansi.BRIGHT_RED, "Possible man-in-the-middle. Connection aborted."));
            return false;
        }
        System.out.println(Ansi.color(Ansi.YELLOW, "[Security] First time connecting to " + endpoint));
        System.out.println(Ansi.color(Ansi.YELLOW, "[Security] Fingerprint: " + fingerprint));
        System.out.println(Ansi.color(Ansi.YELLOW, "[Security] Verify this code with the other person out-of-band."));
        if (prompt.ask(Ansi.color(Ansi.YELLOW, "[Security] Trust this peer and save the fingerprint? (yes/no): "))) {
            store.remember(endpoint, fingerprint);
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Security] Saved. This peer will be verified automatically next time."));
            return true;
        }
        System.out.println(Ansi.color(Ansi.BRIGHT_RED, "[Security] Not trusted. Connection aborted."));
        return false;
    }

    /**
     * Host side: accepts an inbound peer. The peer's fingerprint is its true
     * identity, so acceptance is keyed on the fingerprint rather than the
     * endpoint address (two clients behind the same NAT or on loopback share
     * one address). New fingerprints are auto-trusted (TOFU); a fingerprint
     * seen before is accepted regardless of the address it arrives from.
     */
    public boolean verifyHost(SecureChannel channel, String endpoint) throws IOException {
        String fingerprint = channel.getFingerprint();
        if (store.matches(endpoint, fingerprint) || store.hasFingerprint(fingerprint)) {
            return true;
        }
        System.out.println(Ansi.color(Ansi.YELLOW, "[Security] New peer " + endpoint + " fingerprint: " + fingerprint));
        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[Security] Auto-trusted (first contact)."));
        store.rememberFingerprint(fingerprint);
        return true;
    }
}
