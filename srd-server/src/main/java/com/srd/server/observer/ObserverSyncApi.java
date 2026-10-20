package com.srd.server.observer;

import com.srd.common.util.Log;
import com.srd.common.util.MiniHttpServer;
import com.srd.server.sync.SyncPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * Receives the main server's Sync: {@code POST /sync} with header {@code X-SRD-Key: <psk>} and the
 * {@link SyncPayload} text. Nothing is ever sent back except the status line. Plain sockets
 * ({@link MiniHttpServer}), not the JDK HttpServer, whose NIO selector crashes the JVM on PCs with
 * certain injected security DLLs.
 */
final class ObserverSyncApi {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final MiniHttpServer http;

    ObserverSyncApi(int port, String psk, Consumer<SyncPayload> onSync) throws IOException {
        http = new MiniHttpServer(port, MAX_BYTES, (method, path, headers, body) -> {
            if (!"/sync".equals(path)) return new MiniHttpServer.Reply(404, "not found");
            if (!"POST".equals(method)) return new MiniHttpServer.Reply(405, "POST required");
            String given = headers.get("x-srd-key");
            if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), psk.getBytes(StandardCharsets.UTF_8))) {
                return new MiniHttpServer.Reply(401, "unauthorized");
            }
            SyncPayload payload;
            try {
                payload = SyncPayload.parse(new String(body, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new MiniHttpServer.Reply(400, "bad payload: " + e.getMessage());
            }
            try {
                onSync.accept(payload);
            } catch (RuntimeException e) {
                Log.warn("[SYNC] failed: " + e);
                return new MiniHttpServer.Reply(500, "error");
            }
            return new MiniHttpServer.Reply(200, "ok");
        });
        Log.info("Sync endpoint listening on port " + http.port());
    }

    void stop() {
        http.stop();
    }
}
