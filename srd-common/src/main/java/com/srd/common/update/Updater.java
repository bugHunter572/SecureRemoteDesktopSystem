package com.srd.common.update;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Check, download, verify and unpack one update. Nothing here touches the running install; the
 * swap itself is done by {@code apply-update.ps1} after the app has exited.
 */
public final class Updater {
    static final long MAX_MANIFEST = 64 * 1024;
    static final long MAX_PACKAGE = 400L * 1024 * 1024;

    private final PublicKey key;
    private final boolean allowInsecure;

    public Updater(PublicKey key) {
        this(key, false);
    }

    /** {@code allowInsecure} accepts file: URLs (tests); production needs https. */
    Updater(PublicKey key, boolean allowInsecure) {
        this.key = key;
        this.allowInsecure = allowInsecure;
    }

    /** An unpacked update: {@code root} holds the new exe (and jre) ready to be copied over. */
    public static final class Prepared {
        public final UpdateManifest manifest;
        public final File root;

        Prepared(UpdateManifest manifest, File root) {
            this.manifest = manifest;
            this.root = root;
        }
    }

    /**
     * Fetches and verifies {@code <base>update-<app>.properties(.sig)}; returns the manifest when it
     * is newer than {@code current}, else null. Throws when signature, app name or format is wrong.
     */
    public UpdateManifest check(String baseUrl, String app, String current) throws IOException {
        String name = "update-" + app + ".properties";
        byte[] data = fetch(baseUrl + name, MAX_MANIFEST);
        String sig = new String(fetch(baseUrl + name + ".sig", MAX_MANIFEST), StandardCharsets.UTF_8);
        if (!UpdateSignature.verify(data, sig, key)) throw new IOException("update manifest signature is not valid");
        UpdateManifest m = UpdateManifest.parse(data);
        if (!m.app.equals(app)) throw new IOException("manifest is for '" + m.app + "', not '" + app + "'");
        requireSecure(m.url);
        if (Versions.parse(current) == null) return null; // dev build
        if (Versions.compare(m.version, current) <= 0) return null;
        if (!m.minFrom.isEmpty() && Versions.compare(current, m.minFrom) < 0) {
            throw new IOException("version " + current + " is older than " + m.minFrom + "; install " + m.version + " manually");
        }
        return m;
    }

    /** Downloads the zip, checks size and SHA-256, unpacks it under {@code work}. */
    public Prepared prepare(UpdateManifest m, File work, String exeName) throws IOException {
        File zip = downloadZip(m, work);
        File stage = new File(work, "stage");
        unzip(zip, stage);
        zip.delete();
        File root = payloadRoot(stage, exeName);
        return new Prepared(m, root);
    }

    /**
     * Downloads the zip into {@code work} and checks size and SHA-256; returns it still packed. macOS
     * unpacks it with {@code ditto} instead, because Java's unzip drops the executable bits.
     */
    public File downloadZip(UpdateManifest m, File work) throws IOException {
        if (m.size <= 0 || m.size > MAX_PACKAGE) throw new IOException("unreasonable package size " + m.size);
        deleteTree(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("cannot create " + work);
        File zip = new File(work, "package.zip");
        download(m.url, zip, m.size);
        String sha = sha256(zip);
        if (!sha.equalsIgnoreCase(m.sha256)) {
            zip.delete();
            throw new IOException("package hash mismatch (got " + sha + ")");
        }
        return zip;
    }

    // ------------------------------------------------------------------ pieces

    private void requireSecure(String url) throws IOException {
        if (url.startsWith("https://")) return;
        if (allowInsecure && url.startsWith("file:")) return;
        throw new IOException("refusing non-https update url");
    }

    byte[] fetch(String url, long max) throws IOException {
        requireSecure(url);
        URLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("response too large: " + url);
            }
            return out.toByteArray();
        }
    }

    private void download(String url, File to, long expectedSize) throws IOException {
        requireSecure(url);
        URLConnection c = open(url);
        long total = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > expectedSize) throw new IOException("package larger than announced");
                out.write(buf, 0, n);
            }
        }
        if (total != expectedSize) throw new IOException("package size " + total + " != announced " + expectedSize);
    }

    private static URLConnection open(String url) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "SecureRemoteDesktop-Updater");
        if (c instanceof HttpURLConnection) {
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(true);
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
        }
        return c;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Unzips with zip-slip protection (no entry may land outside {@code dest}). */
    static void unzip(File zip, File dest) throws IOException {
        if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("cannot create " + dest);
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipFile zf = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(base)) throw new IOException("unsafe path in package: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The folder holding the exe: the stage itself or its single top-level directory. */
    static File payloadRoot(File stage, String exeName) throws IOException {
        if (new File(stage, exeName).isFile()) return stage;
        File[] kids = stage.listFiles(File::isDirectory);
        if (kids != null) {
            for (File k : kids) if (new File(k, exeName).isFile()) return k;
        }
        throw new IOException("package has no " + exeName);
    }

    public static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
