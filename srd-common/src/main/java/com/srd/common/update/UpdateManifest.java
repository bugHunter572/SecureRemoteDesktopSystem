package com.srd.common.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * What a release publishes per app ({@code update-<app>.properties}, signed with Ed25519 in a
 * {@code .sig} file): {@code app}, {@code version}, {@code url} (https zip), {@code sha256}, {@code size}
 * and optionally {@code minFrom} (oldest version allowed to update straight to this one).
 */
public final class UpdateManifest {
    public final String app;
    public final String version;
    public final String url;
    public final String sha256;
    public final long size;
    public final String minFrom;

    public UpdateManifest(String app, String version, String url, String sha256, long size, String minFrom) {
        this.app = app;
        this.version = version;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
        this.minFrom = minFrom;
    }

    public static UpdateManifest parse(byte[] data) throws IOException {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(data));
        String app = req(p, "app");
        String version = req(p, "version");
        String url = req(p, "url");
        String sha = req(p, "sha256").toLowerCase();
        if (Versions.parse(version) == null) throw new IOException("bad version: " + version);
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("bad sha256");
        long size;
        try {
            size = Long.parseLong(req(p, "size").trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad size");
        }
        String minFrom = p.getProperty("minFrom", "").trim();
        if (!minFrom.isEmpty() && Versions.parse(minFrom) == null) throw new IOException("bad minFrom: " + minFrom);
        return new UpdateManifest(app.trim(), version.trim(), url.trim(), sha, size, minFrom);
    }

    /** Deterministic text form (what the signing tool writes). */
    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("app=").append(app).append('\n');
        sb.append("version=").append(version).append('\n');
        sb.append("url=").append(url).append('\n');
        sb.append("sha256=").append(sha256).append('\n');
        sb.append("size=").append(size).append('\n');
        if (!minFrom.isEmpty()) sb.append("minFrom=").append(minFrom).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String req(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) throw new IOException("manifest lacks " + key);
        return v.trim();
    }
}
