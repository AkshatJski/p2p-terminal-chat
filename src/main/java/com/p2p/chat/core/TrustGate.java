package com.p2p.chat.core;

import com.p2p.chat.crypto.SecureChannel;
import com.p2p.chat.crypto.TrustStore;
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

    /** Client side: block on the operator's confirmation for a first contact. */
    public boolean verifyClient(SecureChannel channel, String endpoint, Prompt prompt) throws IOException {
        String fingerprint = channel.getFingerprint();
        if (store.matches(endpoint, fingerprint)) {
            System.out.println("[Security] Fingerprint verified (previously trusted).");
            return true;
        }
        if (store.knows(endpoint)) {
            System.out.println("[Security] FINGERPRINT MISMATCH for " + endpoint + "!");
            System.out.println("[Security]   known  : " + store.get(endpoint));
            System.out.println("[Security]   seen   : " + fingerprint);
            System.out.println("[Security] Possible man-in-the-middle. Connection aborted.");
            return false;
        }
        System.out.println("[Security] First time connecting to " + endpoint);
        System.out.println("[Security] Fingerprint: " + fingerprint);
        System.out.println("[Security] Verify this code with the other person out-of-band.");
        if (prompt.ask("[Security] Trust this peer and save the fingerprint? (yes/no): ")) {
            store.remember(endpoint, fingerprint);
            System.out.println("[Security] Saved. This peer will be verified automatically next time.");
            return true;
        }
        System.out.println("[Security] Not trusted. Connection aborted.");
        return false;
    }

    /** Host side: refuse a known peer whose fingerprint changed; otherwise proceed. */
    public boolean verifyHost(SecureChannel channel, String endpoint) throws IOException {
        String fingerprint = channel.getFingerprint();
        if (store.matches(endpoint, fingerprint)) {
            return true;
        }
        if (store.knows(endpoint)) {
            System.out.println("[Security] FINGERPRINT MISMATCH for " + endpoint + "!");
            System.out.println("[Security]   known  : " + store.get(endpoint));
            System.out.println("[Security]   seen   : " + fingerprint);
            System.out.println("[Security] Refusing connection (possible man-in-the-middle).");
            return false;
        }
        System.out.println("[Security] New peer " + endpoint + " fingerprint: " + fingerprint);
        System.out.println("[Security] Auto-trusted (first contact).");
        store.remember(endpoint, fingerprint);
        return true;
    }
}
