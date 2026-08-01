package com.p2p.chat.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * A persistent X25519 identity. The key pair lives in
 * {@code ~/.p2p-chat/identity.key}; it is generated on first use and reused for
 * every connection so that {@link CryptoUtil#fingerprint} values are stable
 * across sessions (required for trust-on-first-use verification).
 */
public final class Identity {
    private static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"),
            ".p2p-chat", "identity.key");

    private final KeyPair keyPair;
    private final byte[] rawPublicKey;

    private Identity(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.rawPublicKey = CryptoUtil.getRawPublicKey(keyPair.getPublic());
    }

    public static Identity loadOrCreate() throws IOException {
        return loadOrCreate(DEFAULT_PATH);
    }

    public static Identity loadOrCreate(Path path) throws IOException {
        try {
            if (Files.exists(path)) {
                String[] parts = Files.readString(path, StandardCharsets.UTF_8).trim().split(":", 2);
                byte[] rawPriv = Base64.getDecoder().decode(parts[0]);
                byte[] rawPub = Base64.getDecoder().decode(parts[1]);
                PrivateKey priv = CryptoUtil.x25519PrivateKey(rawPriv);
                PublicKey pub = CryptoUtil.x25519PublicKey(rawPub);
                return new Identity(new KeyPair(pub, priv));
            }
            KeyPair fresh = CryptoUtil.generateX25519KeyPair();
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                    Base64.getEncoder().encodeToString(CryptoUtil.getRawPrivateKey(fresh.getPrivate()))
                            + ":" + Base64.getEncoder().encodeToString(CryptoUtil.getRawPublicKey(fresh.getPublic())),
                    StandardCharsets.UTF_8);
            return new Identity(fresh);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load identity: " + e.getMessage(), e);
        }
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public byte[] rawPublicKey() {
        return rawPublicKey;
    }
}
