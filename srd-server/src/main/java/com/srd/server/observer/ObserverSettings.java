package com.srd.server.observer;

import com.srd.common.util.Log;
import com.srd.server.config.ServerConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Settings of the watch-only server, read from {@code observer.properties} next to the exe. The
 * file is created with the defaults on first start, since the observer has no menus to edit them.
 */
final class ObserverSettings {
    /** Must match the pre-shared key of the main server, or clients cannot connect. */
    final String psk;
    final int port;
    /** Seconds between pictures from each client (the observer asks every client for this). */
    final int intervalSec;
    /** Widest picture kept per client, px. */
    final int thumbWidth;
    /** Port other PCs connect to when this observer broadcasts its screen. */
    final int peerPort;
    /** Port the main server's "Sync to Alternative Servers" connects to. */
    final int syncPort;

    private ObserverSettings(String psk, int port, int intervalSec, int thumbWidth, int peerPort, int syncPort) {
        this.syncPort = syncPort;
        this.peerPort = peerPort;
        this.psk = psk;
        this.port = port;
        this.intervalSec = intervalSec;
        this.thumbWidth = thumbWidth;
    }

    static ObserverSettings load(File dir) {
        File f = new File(dir, "observer.properties");
        Properties p = new Properties();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                Log.warn("Cannot read " + f + ": " + e);
            }
        } else {
            writeTemplate(f);
        }
        String psk = p.getProperty("psk", "").trim();
        return new ObserverSettings(psk.isEmpty() ? ServerConfig.DEFAULT_PSK : psk,
                num(p, "port", Integer.getInteger("srd.port", 2582), 1, 65535),
                num(p, "interval", 10, 1, 3600),
                num(p, "thumbWidth", 640, 160, 1920),
                num(p, "peerPort", 2583, 1, 65535),
                num(p, "syncPort", 2585, 1, 65535));
    }

    private static int num(Properties p, String key, int def, int min, int max) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeTemplate(File f) {
        try (FileWriter w = new FileWriter(f)) {
            w.write("# Secure Remote Desktop Observer (watch-only server). Restart the app after editing.\r\n");
            w.write("# Pre-shared key: must be the same as the main server's (Edit > Server Configuration there).\r\n");
            w.write("psk=" + ServerConfig.DEFAULT_PSK + "\r\n");
            w.write("# Port clients connect to (list this PC under Alternative IPs on the main server).\r\n");
            w.write("port=2582\r\n");
            w.write("# Seconds between pictures from each client.\r\n");
            w.write("interval=10\r\n");
            w.write("# Widest picture kept per client in pixels (160-1920). Bigger = sharper large tiles, more memory.\r\n");
            w.write("thumbWidth=640\r\n");
            w.write("# Port other PCs connect to in order to watch this screen (the observer always broadcasts it, view only).\r\n");
            w.write("peerPort=2583\r\n");
            w.write("# Port the main server pushes its client list to (Edit > Sync to Alternative Servers there).\r\n");
            w.write("syncPort=2585\r\n");
        } catch (IOException e) {
            Log.warn("Cannot write " + f + ": " + e);
        }
    }
}
