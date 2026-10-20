package com.srd.common.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

class SessionCryptoTest {
    private final byte[] n1 = SessionCrypto.randomNonce();
    private final byte[] n2 = SessionCrypto.randomNonce();

    @Test
    void sequentialMessagesDecryptInOrder() throws Exception {
        byte[] psk = SessionCrypto.pskKey("secret");
        SessionCrypto a = SessionCrypto.derive(psk, n1, n2, true);
        SessionCrypto b = SessionCrypto.derive(psk, n1, n2, false);
        for (int i = 0; i < 50; i++) {
            byte[] msg = new byte[i * 37];
            for (int k = 0; k < msg.length; k++) msg[k] = (byte) (k * i);
            byte[] sealed = a.seal(11, msg, 0, msg.length);
            assertArrayEquals(msg, b.open(11, sealed, 0, sealed.length));
            byte[] back = b.seal(12, msg, 0, msg.length);
            assertArrayEquals(msg, a.open(12, back, 0, back.length));
        }
    }

    @Test
    void wrongPskIsRejected() throws Exception {
        SessionCrypto a = SessionCrypto.derive(SessionCrypto.pskKey("one"), n1, n2, true);
        SessionCrypto b = SessionCrypto.derive(SessionCrypto.pskKey("two"), n1, n2, false);
        byte[] sealed = a.seal(1, new byte[10], 0, 10);
        assertThrows(GeneralSecurityException.class, () -> b.open(1, sealed, 0, sealed.length));
    }

    @Test
    void tamperingAndRetypingAreRejected() throws Exception {
        byte[] psk = SessionCrypto.pskKey("secret");
        SessionCrypto a = SessionCrypto.derive(psk, n1, n2, true);
        SessionCrypto b = SessionCrypto.derive(psk, n1, n2, false);
        byte[] sealed = a.seal(5, "data".getBytes(StandardCharsets.UTF_8), 0, 4);
        sealed[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> b.open(5, sealed, 0, sealed.length));

        SessionCrypto c = SessionCrypto.derive(psk, n1, n2, true);
        SessionCrypto d = SessionCrypto.derive(psk, n1, n2, false);
        byte[] s2 = c.seal(5, new byte[4], 0, 4);
        assertThrows(GeneralSecurityException.class, () -> d.open(6, s2, 0, s2.length)); // type is AAD
    }
}
