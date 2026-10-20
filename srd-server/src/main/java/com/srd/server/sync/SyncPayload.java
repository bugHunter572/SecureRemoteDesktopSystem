package com.srd.server.sync;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What the main server sends to its alternative servers (observers) when the user clicks
 * Sync: the client list (id and display name) and the tag IP ranges. Plain {@link Properties}
 * text so no JSON library is needed on either side.
 */
public final class SyncPayload {
    /** Port the observers listen on for a sync. */
    public static final int DEFAULT_PORT = 2585;
    public static final String HEADER = "X-SRD-Key";

    /** Client id (its IP) to display name, in the order sent. */
    public final Map<String, String> clients;
    /** The Tags-by-IP-range text of Notification Settings. */
    public final String tags;

    public SyncPayload(Map<String, String> clients, String tags) {
        this.clients = clients;
        this.tags = tags == null ? "" : tags;
    }

    public String toText() {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("tags", tags);
        int n = 0;
        for (Map.Entry<String, String> e : clients.entrySet()) {
            p.setProperty("client." + n + ".id", e.getKey());
            p.setProperty("client." + n + ".name", e.getValue() == null ? "" : e.getValue());
            n++;
        }
        p.setProperty("count", String.valueOf(n));
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringWriter does not fail
        }
        return w.toString();
    }

    public static SyncPayload parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        if (!"1".equals(p.getProperty("version"))) throw new IOException("unsupported sync version");
        int count;
        try {
            count = Integer.parseInt(p.getProperty("count", "0"));
        } catch (NumberFormatException e) {
            throw new IOException("bad client count");
        }
        Map<String, String> clients = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = p.getProperty("client." + i + ".id", "").trim();
            if (id.isEmpty()) continue;
            clients.put(id, p.getProperty("client." + i + ".name", "").trim());
        }
        return new SyncPayload(clients, p.getProperty("tags", ""));
    }
}
