package com.srd.client.net;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * A tiny HTTP endpoint that turns broadcasting on when someone asks:
 * <pre>
 *   curl -X POST -H "X-SRD-Key: &lt;security.psk&gt;" http://&lt;client-ip&gt;:2584/broadcast
 *   curl "http://&lt;client-ip&gt;:2584/broadcast?key=&lt;security.psk&gt;"
 * </pre>
 * The request must carry the client's pre-shared key, the same one peer viewers need, so the
 * endpoint gives nobody anything they couldn't already get from the tray. Plain sockets rather than
 * {@code com.sun.net.httpserver}: the bundled JREs don't include the {@code jdk.httpserver} module.
 */
public final class ControlEndpoint {
    public static final String PATH = "/broadcast";
    private static final ExecutorService REQUESTS = Threads.pool("control-http", 2);
    private static final int MAX_HEADER_LINES = 64;

    private final int port;
    private final byte[] key;
    private final BooleanSupplier enableBroadcasting;
    private volatile ServerSocket server;

    /**
     * @param enableBroadcasting turns broadcasting on; returns whether it is running afterwards
     */
    public ControlEndpoint(int port, String psk, BooleanSupplier enableBroadcasting) {
        this.port = port;
        this.key = psk.getBytes(StandardCharsets.UTF_8);
        this.enableBroadcasting = enableBroadcasting;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        ServerSocket ss = new ServerSocket(port);
        server = ss;
        Log.info("Control endpoint: POST/GET http://<this-pc>:" + ss.getLocalPort() + PATH + " enables broadcasting");
        Threads.start("control-accept", () -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    REQUESTS.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!ss.isClosed()) Log.warn("control accept failed: " + e);
                }
            }
        });
    }

    public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve(Socket s) {
        String from = s.getInetAddress().getHostAddress();
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = sock.getOutputStream();
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(out, 400, "{\"error\":\"bad request\"}");
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String headerKey = null;
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String h = in.readLine();
                if (h == null || h.isEmpty()) break;
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("X-SRD-Key")) {
                    headerKey = h.substring(colon + 1).trim();
                }
            }

            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String queryKey = q < 0 ? null : queryParam(target.substring(q + 1), "key");
            if (!path.equals(PATH)) {
                respond(out, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!method.equals("POST") && !method.equals("GET")) {
                respond(out, 405, "{\"error\":\"use POST or GET\"}");
                return;
            }
            if (!authorized(headerKey != null ? headerKey : queryKey)) {
                Log.warn("Control request from " + from + " rejected: wrong or missing key");
                respond(out, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            Log.info("Control request from " + from + ": enable broadcasting");
            boolean running = enableBroadcasting.getAsBoolean();
            respond(out, running ? 200 : 500, "{\"broadcasting\":" + running + "}");
        } catch (IOException e) {
            Log.warn("control request from " + from + " failed: " + e.getMessage());
        }
    }

    private boolean authorized(String given) {
        return given != null && MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                try {
                    return URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void respond(OutputStream out, int status, String json) throws IOException {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Internal Server Error";
        byte[] body = (json + "\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
