package com.srd.client.net;

import com.srd.client.ClientConfig;
import com.srd.common.util.Log;
import com.srd.common.util.MiniHttpServer;
import java.io.IOException;

/**
 * Tiny HTTP endpoint: {@code POST /broadcast/start} turns broadcasting on. No key is checked (any
 * request on the LAN enables it); viewers still need the PSK to connect to the peer port. Silent by
 * design (no tray notice). Port: {@code api.port} (default 2584). Built on plain sockets
 * ({@link MiniHttpServer}), not the JDK HttpServer, whose NIO selector crashes the JVM on PCs with
 * certain injected security DLLs.
 */
public final class BroadcastApi {
    private final MiniHttpServer http;

    public BroadcastApi(ClientConfig config, Runnable startBroadcasting) throws IOException {
        http = new MiniHttpServer(config.getInt("api.port", ClientConfig.DEFAULT_API_PORT), 1024, (method, path, headers, body) -> {
            if (!"/broadcast/start".equals(path)) return new MiniHttpServer.Reply(404, "not found");
            if (!"POST".equals(method)) return new MiniHttpServer.Reply(405, "POST required");
            startBroadcasting.run();
            return new MiniHttpServer.Reply(200, "broadcasting enabled");
        });
        Log.info("Broadcast API listening on port " + http.port());
    }

    public void stop() {
        http.stop();
    }
}
