package com.srd.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-connection authenticated encryption (AES-128-GCM).
 *
 * <p>Replaces the old scheme (a static key, AES/ECB, and on the client a fresh {@code Cipher}
 * built for every message). Here:
 * <ul>
 *   <li>both peers derive direction-specific keys with HKDF-SHA256 from the shared PSK and two
 *       fresh random nonces, so each connection has its own keys;</li>
 *   <li>the 12-byte GCM IV is a 4-byte per-direction salt plus a 64-bit message counter. TCP
 *       delivers in order, so the counter never travels on the wire and never repeats;</li>
 *   <li>the message type byte is bound as AAD, so a frame can't be re-typed;</li>
 *   <li>a wrong PSK fails the first {@link #open} (tag mismatch) — this is the authentication.</li>
 * </ul>
 * One instance is used by one sender thread ({@link #seal}) and one reader thread
 * ({@link #open}); the two directions share no mutable state.
 */
public final class SessionCrypto {
    public static final int NONCE_BYTES = 16;
    private static final int TAG_BITS = 128;

    private final Direction tx;
    private final Direction rx;

    private SessionCrypto(Direction tx, Direction rx) {
        this.tx = tx;
        this.rx = rx;
    }

    /** SHA-256 of the configured pre-shared key text. */
    public static byte[] pskKey(String psk) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(psk.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(n);
        return n;
    }

    /**
     * @param initiator true on the side that opened the TCP connection
     */
    public static SessionCrypto derive(byte[] pskKey, byte[] initiatorNonce, byte[] acceptorNonce, boolean initiator) {
        byte[] salt = new byte[initiatorNonce.length + acceptorNonce.length];
        System.arraycopy(initiatorNonce, 0, salt, 0, initiatorNonce.length);
        System.arraycopy(acceptorNonce, 0, salt, initiatorNonce.length, acceptorNonce.length);
        byte[] prk = hmac(salt, pskKey);
        byte[] i2a = expand(prk, "srd3 initiator->acceptor", 20);
        byte[] a2i = expand(prk, "srd3 acceptor->initiator", 20);
        Direction send = new Direction(initiator ? i2a : a2i, Cipher.ENCRYPT_MODE);
        Direction recv = new Direction(initiator ? a2i : i2a, Cipher.DECRYPT_MODE);
        return new SessionCrypto(send, recv);
    }

    /** Encrypts {@code data[off..off+len)} with {@code type} as AAD. Sender thread only. */
    public byte[] seal(int type, byte[] data, int off, int len) throws GeneralSecurityException {
        return tx.process(type, data, off, len);
    }

    /** Decrypts and authenticates. Throws if tampered with or keyed with a different PSK. Reader thread only. */
    public byte[] open(int type, byte[] data, int off, int len) throws GeneralSecurityException {
        return rx.process(type, data, off, len);
    }

    private static final class Direction {
        private final SecretKeySpec key;
        private final byte[] salt;
        private final int mode;
        private final Cipher cipher;
        private final byte[] iv = new byte[12];
        private final byte[] aad = new byte[1];
        private long counter;

        Direction(byte[] material, int mode) {
            this.key = new SecretKeySpec(Arrays.copyOfRange(material, 0, 16), "AES");
            this.salt = Arrays.copyOfRange(material, 16, 20);
            this.mode = mode;
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM unavailable", e);
            }
        }

        byte[] process(int type, byte[] data, int off, int len) throws GeneralSecurityException {
            System.arraycopy(salt, 0, iv, 0, 4);
            ByteBuffer.wrap(iv, 4, 8).putLong(counter++);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            aad[0] = (byte) type;
            cipher.updateAAD(aad);
            return cipher.doFinal(data, off, len);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-Expand (RFC 5869). */
    private static byte[] expand(byte[] prk, String info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < length; i++) {
                mac.update(t);
                mac.update(info.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
