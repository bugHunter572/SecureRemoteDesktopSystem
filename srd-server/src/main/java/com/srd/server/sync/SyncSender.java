package com.srd.server.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Pushes a {@link SyncPayload} to each alternative server's sync endpoint, one after the other (blocking). */
public final class SyncSender {
    private SyncSender() {
    }

    /** One line per target: "ip: ok" or "ip: reason". */
    public static List<String> send(List<String> targets, int port, String psk, SyncPayload payload) {
        byte[] body = payload.toText().getBytes(StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String ip : targets) {
            String host = ip.trim();
            if (host.isEmpty()) continue;
            out.add(host + ": " + sendOne(host, port, psk, body));
        }
        return out;
    }

    static String sendOne(String host, int port, String psk, byte[] body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/sync").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty(SyncPayload.HEADER, psk);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
            int code = c.getResponseCode();
            if (code == 200) return "ok";
            return code == 401 ? "wrong key (PSK differs)" : "HTTP " + code;
        } catch (IOException e) {
            return "not reachable (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) {
                try (InputStream in = c.getResponseCode() / 100 == 2 ? c.getInputStream() : c.getErrorStream()) {
                    if (in != null) in.readAllBytes();
                } catch (IOException ignored) {
                    // draining only
                }
                c.disconnect();
            }
        }
    }
}
