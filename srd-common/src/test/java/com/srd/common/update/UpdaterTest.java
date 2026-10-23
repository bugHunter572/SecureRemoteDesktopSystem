package com.srd.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdaterTest {
    @TempDir
    File dir;

    private KeyPair keys;
    private Updater updater;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        updater = new Updater(keys.getPublic(), true);
    }

    // ------------------------------------------------------------------ versions

    @Test
    void versionsCompareNumerically() {
        assertTrue(Versions.compare("3.0.10", "3.0.9") > 0);
        assertEquals(0, Versions.compare("3.0", "3.0.0"));
        assertTrue(Versions.compare("v3.1.0", "3.0.99") > 0);
        assertEquals(0, Versions.compare("3.0.6-SNAPSHOT", "3.0.6"));
        assertNull(Versions.parse("dev"));
        assertNull(Versions.parse("3..1"));
        assertThrows(IllegalArgumentException.class, () -> Versions.compare("dev", "3.0.0"));
    }

    // ------------------------------------------------------------------ signature + manifest

    @Test
    void signatureDetectsTampering() throws Exception {
        byte[] data = "app=client\nversion=3.0.7\n".getBytes(StandardCharsets.UTF_8);
        String sig = UpdateSignature.sign(data, keys.getPrivate());
        assertTrue(UpdateSignature.verify(data, sig, keys.getPublic()));
        byte[] other = "app=client\nversion=9.9.9\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(UpdateSignature.verify(other, sig, keys.getPublic()));
        assertFalse(UpdateSignature.verify(data, "not base64!!", keys.getPublic()));
        KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(UpdateSignature.verify(data, sig, stranger.getPublic()));
    }

    @Test
    void bundledPublicKeyLoads() throws Exception {
        assertNotNull(UpdateSignature.bundledPublicKey());
    }

    @Test
    void manifestRoundTripAndValidation() throws Exception {
        UpdateManifest m = new UpdateManifest("client", "3.0.7", "https://x/y.zip", "ab".repeat(32), 1234, "3.0.0");
        UpdateManifest back = UpdateManifest.parse(m.toBytes());
        assertEquals("client", back.app);
        assertEquals("3.0.7", back.version);
        assertEquals(1234, back.size);
        assertEquals("3.0.0", back.minFrom);
        assertThrows(IOException.class, () -> UpdateManifest.parse("app=client\nversion=3.0.7\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> UpdateManifest.parse(
                "app=a\nversion=1\nurl=u\nsha256=zz\nsize=1\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ check

    private String publish(String app, String version, File zip, String minFrom, KeyPair signer) throws Exception {
        File rel = new File(dir, "release");
        rel.mkdirs();
        File mf = new File(rel, "update-" + app + ".properties");
        UpdateTool.manifest(app, version, zip, zip.toURI().toString(), mf, minFrom);
        String sig = UpdateSignature.sign(Files.readAllBytes(mf.toPath()), signer.getPrivate());
        Files.write(new File(rel, mf.getName() + ".sig").toPath(), sig.getBytes(StandardCharsets.UTF_8));
        return rel.toURI().toString();
    }

    private File zipOf(String entryName, String content) throws IOException {
        File z = new File(dir, "pkg-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(z))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return z;
    }

    @Test
    void checkReturnsNewerOnlyAndEnforcesApp() throws Exception {
        File zip = zipOf("SecureRemoteDesktopClient/SecureRemoteDesktopClient.exe", "exe");
        String base = publish("client", "3.0.7", zip, "", keys);
        assertEquals("3.0.7", updater.check(base, "client", "3.0.6").version);
        assertNull(updater.check(base, "client", "3.0.7"));
        assertNull(updater.check(base, "client", "3.1.0"));
        assertNull(updater.check(base, "client", "dev"));
        // the client manifest must not be accepted for the server
        File rel = new File(dir, "release");
        Files.copy(new File(rel, "update-client.properties").toPath(), new File(rel, "update-server.properties").toPath());
        Files.copy(new File(rel, "update-client.properties.sig").toPath(), new File(rel, "update-server.properties.sig").toPath());
        assertThrows(IOException.class, () -> updater.check(base, "server", "3.0.6"));
    }

    @Test
    void checkRejectsManifestSignedByAnotherKey() throws Exception {
        File zip = zipOf("a.exe", "x");
        KeyPair evil = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String base = publish("client", "3.0.7", zip, "", evil);
        assertThrows(IOException.class, () -> updater.check(base, "client", "3.0.6"));
    }

    @Test
    void checkRefusesTooOldInstall() throws Exception {
        File zip = zipOf("a.exe", "x");
        String base = publish("client", "4.0.0", zip, "3.0.5", keys);
        assertThrows(IOException.class, () -> updater.check(base, "client", "3.0.0"));
        assertEquals("4.0.0", updater.check(base, "client", "3.0.6").version);
    }

    @Test
    void productionUpdaterRefusesPlainHttpAndFileUrls() {
        Updater prod = new Updater(keys.getPublic());
        assertThrows(IOException.class, () -> prod.check("http://example.com/", "client", "3.0.0"));
        assertThrows(IOException.class, () -> prod.check(dir.toURI().toString(), "client", "3.0.0"));
    }

    // ------------------------------------------------------------------ prepare

    @Test
    void prepareUnpacksAndFindsExe() throws Exception {
        File zip = zipOf("SecureRemoteDesktopClient/SecureRemoteDesktopClient.exe", "exe");
        String base = publish("client", "3.0.7", zip, "", keys);
        UpdateManifest m = updater.check(base, "client", "3.0.6");
        Updater.Prepared p = updater.prepare(m, new File(dir, "work"), "SecureRemoteDesktopClient.exe");
        assertTrue(new File(p.root, "SecureRemoteDesktopClient.exe").isFile());
    }

    @Test
    void prepareRejectsHashMismatch() throws Exception {
        File zip = zipOf("a.exe", "original");
        String base = publish("client", "3.0.7", zip, "", keys);
        UpdateManifest m = updater.check(base, "client", "3.0.6");
        // same size, different content: swap the package after the manifest was signed
        Files.write(zip.toPath(), "tampered".getBytes(StandardCharsets.UTF_8));
        File same = new File(dir, "same.zip");
        byte[] orig = Files.readAllBytes(zip.toPath());
        byte[] pad = new byte[(int) m.size];
        System.arraycopy(orig, 0, pad, 0, Math.min(orig.length, pad.length));
        Files.write(same.toPath(), pad);
        UpdateManifest forged = new UpdateManifest(m.app, m.version, same.toURI().toString(), m.sha256, m.size, m.minFrom);
        IOException e = assertThrows(IOException.class, () -> updater.prepare(forged, new File(dir, "work2"), "a.exe"));
        assertTrue(e.getMessage().contains("hash"));
    }

    @Test
    void prepareRejectsZipSlip() throws Exception {
        File zip = zipOf("../evil.exe", "x");
        String base = publish("client", "3.0.7", zip, "", keys);
        UpdateManifest m = updater.check(base, "client", "3.0.6");
        assertThrows(IOException.class, () -> updater.prepare(m, new File(dir, "work3"), "evil.exe"));
        assertFalse(new File(dir, "evil.exe").exists());
    }

    @Test
    void prepareRejectsPackageWithoutExe() throws Exception {
        File zip = zipOf("folder/readme.txt", "x");
        String base = publish("client", "3.0.7", zip, "", keys);
        UpdateManifest m = updater.check(base, "client", "3.0.6");
        assertThrows(IOException.class, () -> updater.prepare(m, new File(dir, "work4"), "SecureRemoteDesktopClient.exe"));
    }
}
