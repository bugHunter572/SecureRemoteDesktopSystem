package com.srd.common.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 signatures over the manifest bytes. The algorithm is looked up by name because the
 * project compiles for Java 11; it needs a runtime of 15+ (the bundled JRE is newer).
 */
public final class UpdateSignature {
    private static final String ALGORITHM = "Ed25519";

    private UpdateSignature() {
    }

    /** The public key shipped inside the jar ({@code update-public-key.b64}, X.509 base64). */
    public static PublicKey bundledPublicKey() throws IOException, GeneralSecurityException {
        try (InputStream in = UpdateSignature.class.getResourceAsStream("/update-public-key.b64")) {
            if (in == null) throw new IOException("no update-public-key.b64 in the jar");
            return publicKey(readAll(in));
        }
    }

    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        byte[] der = Base64.getMimeDecoder().decode(base64.trim());
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    }

    public static PrivateKey privateKey(String base64) throws GeneralSecurityException {
        byte[] der = Base64.getMimeDecoder().decode(base64.trim());
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static String sign(byte[] data, PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(ALGORITHM);
        s.initSign(key);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** False for a bad or malformed signature; never throws for those. */
    public static boolean verify(byte[] data, String signatureBase64, PublicKey key) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getMimeDecoder().decode(signatureBase64.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
