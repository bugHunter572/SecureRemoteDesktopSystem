package com.srd.client;

import com.srd.common.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/**
 * Typed access to {@code client-config.properties} in the working directory. Keys from v2 are kept
 * ({@code server.ip}, {@code server.port}, {@code alternative.ips}, {@code autoBroadcast}) so an
 * upgraded client reconnects without asking again.
 */
public final class ClientConfig {
    public static final String FILE = "client-config.properties";
    public static final int DEFAULT_SERVER_PORT = 2582;
    public static final int DEFAULT_PEER_PORT = 2583;
    public static final int DEFAULT_CONTROL_PORT = 2584;
    public static final String DEFAULT_PSK = "MySecretKey12345";

    private final File file;
    private final Properties props = new Properties();

    public ClientConfig(File file) {
        this.file = file;
        load();
    }

    public static ClientConfig loadDefault() {
        return new ClientConfig(new File(FILE));
    }

    private synchronized void load() {
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            Log.warn("Could not read " + file + ": " + e);
        }
    }

    public synchronized void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "SecureRemoteDesktop client configuration");
        } catch (Exception e) {
            Log.warn("Could not save " + file + ": " + e);
        }
    }

    public synchronized Properties raw() {
        Properties copy = new Properties();
        copy.putAll(props);
        return copy;
    }

    public synchronized String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public synchronized void set(String key, String value) {
        props.setProperty(key, value);
        save();
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, Boolean.toString(def)));
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---- well-known settings ----

    public String serverIp() {
        return get("server.ip", "");
    }

    public int serverPort() {
        return getInt("server.port", DEFAULT_SERVER_PORT);
    }

    public int peerPort() {
        return getInt("peer.port", DEFAULT_PEER_PORT);
    }

    /** Port of the HTTP endpoint that enables broadcasting on request; 0 turns it off. */
    public int controlPort() {
        return getInt("control.port", DEFAULT_CONTROL_PORT);
    }

    public String psk() {
        return get("security.psk", DEFAULT_PSK);
    }

    public boolean autoBroadcast() {
        return getBool("autoBroadcast", false);
    }

    /** Whether peer viewers may control mouse/keyboard (the server can additionally restrict). */
    public boolean peerControlAllowed() {
        return getBool("peer.allowControl", true);
    }

    public boolean peerDrawAllowed() {
        return getBool("peer.allowDraw", true);
    }

    public List<String> alternativeIps() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (String ip : get("alternative.ips", "").split(",")) {
            if (!ip.trim().isEmpty()) s.add(ip.trim());
        }
        return new ArrayList<>(s);
    }

    public void setAlternativeIps(List<String> ips) {
        set("alternative.ips", String.join(",", ips));
    }
}
