package com.srd.client.net;

import com.srd.client.ClientConfig;
import com.srd.client.ClientContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Registry of the main + alternative server links. */
public final class ServerLinks {
    public static final String MAIN = "Main Server";

    private static final Map<String, ServerLink> LINKS = new ConcurrentHashMap<>();
    private static volatile ClientContext ctx;
    private static volatile Consumer<ServerLink> listener = l -> { };

    private ServerLinks() {
    }

    public static void init(ClientContext context, Consumer<ServerLink> stateListener) {
        ctx = context;
        listener = stateListener;
    }

    public static void connect(String host, int port, String label) {
        String key = host + ":" + port;
        LINKS.computeIfAbsent(key, k -> {
            ServerLink l = new ServerLink(ctx, host, port, label, s -> listener.accept(s));
            l.start();
            return l;
        });
    }

    /** Points the main-server link at a new address (tray "Set Server Address..."). */
    public static void replaceMain(String host, int port) {
        for (Map.Entry<String, ServerLink> e : LINKS.entrySet()) {
            if (MAIN.equals(e.getValue().label)) {
                LINKS.remove(e.getKey());
                e.getValue().stop();
            }
        }
        connect(host, port, MAIN);
    }

    /** Applies the server-pushed ALTERNATIVE_IPS list (adds new links; persists the list). */
    static void setAlternatives(String[] ips) {
        Set<String> clean = new LinkedHashSet<>();
        for (String ip : ips) if (!ip.trim().isEmpty()) clean.add(ip.trim());
        List<String> list = new ArrayList<>(clean);
        ctx.config.setAlternativeIps(list);
        for (String ip : list) connect(ip, ClientConfig.DEFAULT_SERVER_PORT, "Alternative Server: " + ip);
    }

    public static Collection<ServerLink> all() {
        return new ArrayList<>(LINKS.values());
    }

    public static void stopAll() {
        for (ServerLink l : LINKS.values()) l.stop();
    }
}
