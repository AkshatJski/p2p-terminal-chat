package com.p2p.chat.crypto;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Low level cryptographic helpers used by {@link SecureChannel}.
 *
 * <p>Cipher suite: X25519 (ECDH) for the key exchange, HKDF-SHA256 for key
 * derivation and AES-256-GCM for message encryption. Everything is implemented
 * with the standard {@code java.security} / {@code javax.crypto} APIs, so no
 * extra dependencies are needed.
 */
public final class CryptoUtil {
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HKDF_SALT_LEN = 32;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    public static KeyPair generateX25519KeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    /** Raw 32-byte u-coordinate of an X25519 public key (compact, no X.509 wrapper). */
    public static byte[] getRawPublicKey(PublicKey publicKey) {
        byte[] raw = new byte[32];
        byte[] big = ((XECPublicKey) publicKey).getU().toByteArray();
        if (big.length > raw.length) {
            System.arraycopy(big, big.length - raw.length, raw, 0, raw.length);
        } else {
            System.arraycopy(big, 0, raw, raw.length - big.length, big.length);
        }
        return raw;
    }

    public static PublicKey x25519PublicKey(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("X25519")
                .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, raw)));
    }

    /** Raw 32-byte scalar of an X25519 private key. */
    public static byte[] getRawPrivateKey(PrivateKey privateKey) {
        byte[] raw = new byte[32];
        byte[] big = ((XECPrivateKey) privateKey).getScalar().get();
        if (big.length > raw.length) {
            System.arraycopy(big, big.length - raw.length, raw, 0, raw.length);
        } else {
            System.arraycopy(big, 0, raw, raw.length - big.length, big.length);
        }
        return raw;
    }

    public static PrivateKey x25519PrivateKey(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("X25519")
                .generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, raw));
    }

    /**
     * Stable, mutually-computable connection fingerprint.
     *
     * <p>Both sides feed their persistent identity public keys into the hash in a
     * canonical (sorted) order, so both terminals show the SAME code. Users compare
     * this code out-of-band (over voice, QR, ...) to detect a man-in-the-middle.
     * Because it is derived from persistent identity keys - not the ephemeral
     * handshake keys - it is identical across reconnects, which is what makes
     * trust-on-first-use possible.
     */
    public static String fingerprint(byte[] identityA, byte[] identityB) throws GeneralSecurityException {
        byte[] a = identityA;
        byte[] b = identityB;
        int cmp = compareUnsigned(a, b);
        if (cmp > 0) {
            byte[] t = a;
            a = b;
            b = t;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(concat(a, b));
        HexFormat hex = HexFormat.of().withUpperCase();
        return hex.formatHex(digest, 0, 4) + "-" + hex.formatHex(digest, 4, 8); // e.g. "A1B2C3D4-E5F6A7B8"
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        for (int i = 0; i < a.length && i < b.length; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Diffie-Hellman: returns the shared 32-byte secret for {@code peerRawPub}. */
    public static byte[] ecdhe(PrivateKey privateKey, byte[] peerRawPub) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(x25519PublicKey(peerRawPub), true);
        return agreement.generateSecret();
    }

    /**
     * HKDF-SHA256. The extraction step uses a zero salt of hash-length bytes,
     * which is sufficient here because the input key material is already high
     * entropy (the ECDH shared secret).
     */
    public static byte[] hkdfSha256(byte[] inputKeyMaterial, String info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(new byte[HKDF_SALT_LEN], "HmacSHA256"));
        byte[] prk = mac.doFinal(inputKeyMaterial);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        int counter = 1;
        int offset = 0;
        while (offset < length) {
            mac.update(block);
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) counter++);
            block = mac.doFinal();
            int copy = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, okm, offset, copy);
            offset += copy;
        }
        return okm;
    }

    /** AES-256-GCM encrypt; output is {@code [12-byte nonce][ciphertext+tag]}. */
    public static byte[] encryptAesGcm(byte[] key, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] out = new byte[NONCE_LEN + ciphertext.length];
        System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
        System.arraycopy(ciphertext, 0, out, NONCE_LEN, ciphertext.length);
        return out;
    }

    /** Inverse of {@link #encryptAesGcm}; throws on tampering / wrong key. */
    public static byte[] decryptAesGcm(byte[] key, byte[] nonceAndCipher) throws GeneralSecurityException {
        if (nonceAndCipher.length < NONCE_LEN) {
            throw new GeneralSecurityException("Ciphertext shorter than nonce");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonceAndCipher, 0, NONCE_LEN));
        return cipher.doFinal(nonceAndCipher, NONCE_LEN, nonceAndCipher.length - NONCE_LEN);
    }

    /** Constant-time byte comparison, for verifying handshake proofs. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    public static String base64(byte[] data) {
        return B64.encodeToString(data);
    }

    public static byte[] decodeBase64(String encoded) {
        return B64_DEC.decode(encoded);
    }
}
