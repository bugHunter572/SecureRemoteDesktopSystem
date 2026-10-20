package com.srd.common.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * A tiny blocking HTTP server on a plain {@link ServerSocket}: one request per connection, a text
 * reply. It exists instead of {@code com.sun.net.httpserver} because that one opens an NIO selector
 * (wepoll), and some security/network-filter DLLs injected into the process crash the JVM in exactly
 * that call (hs_err: {@code ASProxy64.dll} under {@code WEPoll.ctl}). Plain sockets don't touch it.
 */
public final class MiniHttpServer {
    public interface Handler {
        /** Header names in {@code headers} are lower case. */
        Reply handle(String method, String path, Map<String, String> headers, byte[] body);
    }

    public static final class Reply {
        final int code;
        final String body;

        public Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private final ServerSocket server;
    private final ExecutorService workers = Threads.pool("mini-http", 2);
    private final int maxBody;
    private final Handler handler;

    public MiniHttpServer(int port, int maxBody, Handler handler) throws IOException {
        this.maxBody = maxBody;
        this.handler = handler;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        Threads.start("mini-http-accept", Thread.NORM_PRIORITY, this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public void stop() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                workers.execute(() -> serve(s));
            } catch (IOException e) {
                if (!server.isClosed()) Log.warn("HTTP accept failed: " + e);
            } catch (RuntimeException e) {
                return; // pool shut down
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0; // progress through \r\n\r\n
            while (state < 4) {
                int b = in.read();
                if (b < 0 || head.size() > MAX_HEADER_BYTES) return;
                head.write(b);
                if (b == '\r') state = (state == 0 || state == 2) ? state + 1 : 1;
                else if (b == '\n' && (state == 1 || state == 3)) state++;
                else state = 0;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) {
                write(sock, new Reply(400, "bad request"));
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            int len;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                write(sock, new Reply(400, "bad content-length"));
                return;
            }
            if (len < 0 || len > maxBody) {
                write(sock, new Reply(413, "too large"));
                return;
            }
            byte[] body = in.readNBytes(len);
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            write(sock, handler.handle(req[0], path, headers, body));
        } catch (IOException | RuntimeException e) {
            Log.debug("HTTP request failed: " + e);
        }
    }

    private static void write(Socket sock, Reply r) throws IOException {
        byte[] b = r.body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + r.code + " " + (r.code == 200 ? "OK" : "Error") + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        OutputStream o = sock.getOutputStream();
        o.write(head.getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.flush();
    }
}
