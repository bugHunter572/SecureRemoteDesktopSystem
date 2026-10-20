package com.srd.common.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Release-side helper (run by CI, never by the apps):
 * <pre>
 *   keygen   &lt;outDir&gt;
 *   manifest &lt;app&gt; &lt;version&gt; &lt;zip&gt; &lt;url&gt; &lt;outFile&gt; [minFrom]
 *   sign     &lt;file&gt;    (private key base64 in env SRD_UPDATE_PRIVATE_KEY; writes &lt;file&gt;.sig)
 * </pre>
 */
public final class UpdateTool {
    private UpdateTool() {
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 0) usage();
        switch (a[0]) {
            case "keygen":
                keygen(new File(a[1]));
                break;
            case "manifest":
                if (a.length < 6) usage();
                manifest(a[1], a[2], new File(a[3]), a[4], new File(a[5]), a.length > 6 ? a[6] : "");
                break;
            case "sign":
                sign(new File(a[1]));
                break;
            default:
                usage();
        }
    }

    private static void keygen(File dir) throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        dir.mkdirs();
        write(new File(dir, "update-public-key.b64"), Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        write(new File(dir, "update-private-key.b64"), Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        System.out.println("Wrote update-public-key.b64 (commit it) and update-private-key.b64 (SECRET: only as CI secret SRD_UPDATE_PRIVATE_KEY) to " + dir);
    }

    static void manifest(String app, String version, File zip, String url, File out, String minFrom) throws IOException {
        UpdateManifest m = new UpdateManifest(app, version, url, Updater.sha256(zip), zip.length(), minFrom);
        Files.write(out.toPath(), m.toBytes());
    }

    private static void sign(File file) throws Exception {
        String pk = System.getenv("SRD_UPDATE_PRIVATE_KEY");
        if (pk == null || pk.trim().isEmpty()) throw new IllegalStateException("SRD_UPDATE_PRIVATE_KEY is not set");
        String sig = UpdateSignature.sign(Files.readAllBytes(file.toPath()), UpdateSignature.privateKey(pk));
        write(new File(file.getPath() + ".sig"), sig);
    }

    private static void write(File f, String text) throws IOException {
        Files.write(f.toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void usage() {
        System.err.println("usage: UpdateTool keygen <dir> | manifest <app> <version> <zip> <url> <out> [minFrom] | sign <file>");
        System.exit(2);
    }
}
