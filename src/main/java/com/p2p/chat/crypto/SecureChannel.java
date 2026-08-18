package com.p2p.chat.crypto;

import com.p2p.chat.config.Config;
import com.p2p.chat.transport.SocketTransport;
import com.p2p.chat.transport.Transport;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;

/**
 * A transport wrapper that establishes an end-to-end encrypted channel and then
 * exchanges length-prefixed, AES-GCM encrypted UTF-8 messages.
 *
 * <p>Wire protocol:
 * <ul>
 *   <li><b>Handshake (plaintext, line based):</b> both sides exchange a fresh
 *       ephemeral X25519 key (forward secrecy) plus a persistent identity key
 *       (stable fingerprint), derive a shared AES key, then prove possession
 *       with a challenge/response so a mis-derived key is detected.</li>
 *   <li><b>Data (encrypted, framed):</b> {@code [4-byte big-endian length][12-byte
 *       nonce][AES-GCM ciphertext]}.</li>
 * </ul>
 *
 * <p>The handshake is fully symmetric (no client/server role) and works over any
 * {@link Transport}: TCP, Bluetooth RFCOMM serial, or in-process pipes.
 */
public final class SecureChannel implements Closeable {
    private static final String PREFIX_KEY = "K:";
    private static final String PREFIX_IDENTITY = "I:";
    private static final String PREFIX_CHALLENGE = "C:";
    private static final String PREFIX_RESPONSE = "R:";

    private final Transport transport;
    private final DataInputStream in;
    private final DataOutputStream out;
    private byte[] aesKey;
    private final String fingerprint;

    public SecureChannel(Socket socket, Identity identity) throws IOException {
        this(new SocketTransport(socket), identity);
    }

    public SecureChannel(Transport transport, Identity identity) throws IOException {
        this.transport = transport;
        this.in = new DataInputStream(new BufferedInputStream(transport.input()));
        this.out = new DataOutputStream(new BufferedOutputStream(transport.output()));
        try {
            transport.setReadTimeout(Config.get().getHandshakeTimeoutMs());
        } catch (IOException ignored) {
            // Not fatal: unsupported on some transports.
        }
        String fp = handshake(identity);
        try {
            transport.setReadTimeout(0); // back to blocking mode after handshake
        } catch (IOException ignored) {
        }
        this.fingerprint = fp;
    }

    /** Stable, mutually-computable code both users should compare out-of-band. */
    public String getFingerprint() {
        return fingerprint;
    }

    public String remote() {
        return transport.id();
    }

    /**
     * Symmetric handshake:
     * <pre>
     *   send K:&lt;ephemeral pub&gt;   send I:&lt;identity pub&gt;   // both sides
     *   read  K:&lt;...&gt;             read I:&lt;...&gt;
     *   session key = HKDF(ECDH(eph, peerEph))
     *   fingerprint = canonical hash(identityA, identityB)
     *   send C:&lt;enc(myToken)&gt;    read C:&lt;enc(peerToken)&gt;
     *   send R:&lt;enc(peerToken)&gt;   read R:&lt;...&gt; verify == myToken
     * </pre>
     * Every step writes before it blocks on a read, so the protocol cannot
     * deadlock. The challenge/response catches any key mismatch early.
     */
    private String handshake(Identity identity) throws IOException {
        try {
            KeyPair ephemeral = CryptoUtil.generateX25519KeyPair();

            writeLine(PREFIX_KEY + CryptoUtil.base64(CryptoUtil.getRawPublicKey(ephemeral.getPublic())));
            writeLine(PREFIX_IDENTITY + CryptoUtil.base64(identity.rawPublicKey()));

            String keyLine = readLine();
            String identityLine = readLine();
            if (keyLine == null || !keyLine.startsWith(PREFIX_KEY)
                    || identityLine == null || !identityLine.startsWith(PREFIX_IDENTITY)) {
                throw new IOException("Peer sent an invalid handshake");
            }
            byte[] peerEphemeral = CryptoUtil.decodeBase64(keyLine.substring(PREFIX_KEY.length()));
            byte[] peerIdentity = CryptoUtil.decodeBase64(identityLine.substring(PREFIX_IDENTITY.length()));

            byte[] sharedSecret = CryptoUtil.ecdhe(ephemeral.getPrivate(), peerEphemeral);
            this.aesKey = CryptoUtil.hkdfSha256(sharedSecret, "p2p-chat-v1", 32);
            String fp = CryptoUtil.fingerprint(identity.rawPublicKey(), peerIdentity);

            byte[] myToken = new byte[16];
            new SecureRandom().nextBytes(myToken);
            writeLine(PREFIX_CHALLENGE + CryptoUtil.base64(CryptoUtil.encryptAesGcm(aesKey, myToken)));

            String challengeLine = readLine();
            if (challengeLine == null || !challengeLine.startsWith(PREFIX_CHALLENGE)) {
                throw new IOException("Peer did not answer the challenge");
            }
            byte[] peerToken = CryptoUtil.decryptAesGcm(aesKey,
                    CryptoUtil.decodeBase64(challengeLine.substring(PREFIX_CHALLENGE.length())));

            writeLine(PREFIX_RESPONSE + CryptoUtil.base64(CryptoUtil.encryptAesGcm(aesKey, peerToken)));

            String responseLine = readLine();
            if (responseLine == null || !responseLine.startsWith(PREFIX_RESPONSE)) {
                throw new IOException("Peer did not return a proof");
            }
            byte[] proof = CryptoUtil.decryptAesGcm(aesKey,
                    CryptoUtil.decodeBase64(responseLine.substring(PREFIX_RESPONSE.length())));
            if (!CryptoUtil.constantTimeEquals(myToken, proof)) {
                throw new IOException("Key verification failed: derived keys do not match");
            }
            return fp;
        } catch (GeneralSecurityException e) {
            throw new IOException("Handshake failed: " + e.getMessage(), e);
        }
    }

    private void writeLine(String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.writeByte('\n');
        out.flush();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.readByte(); // EOFException when peer closes mid-line
            if (b == '\n') {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
    }

    /** Encrypts {@code text} and writes one framed message. */
    public synchronized void send(String text) throws IOException {
        try {
            byte[] nonceAndCipher = CryptoUtil.encryptAesGcm(aesKey, text.getBytes(StandardCharsets.UTF_8));
            out.writeInt(nonceAndCipher.length);
            out.write(nonceAndCipher);
            out.flush();
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /** Blocks until a framed message arrives; returns null if the peer never sent one and closed. */
    public String receive() throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > Config.get().getMaxFrame()) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] nonceAndCipher = new byte[length];
        in.readFully(nonceAndCipher);
        try {
            return new String(CryptoUtil.decryptAesGcm(aesKey, nonceAndCipher), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("Message authentication failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException ignored) {
            // Closing a transport is best-effort.
        }
    }
}
