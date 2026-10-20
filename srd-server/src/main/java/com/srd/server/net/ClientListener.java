package com.srd.server.net;

import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Accepts agent connections on the server port (2582, overridable with {@code -Dsrd.port}).
 * Only private/loopback addresses are served. The v3 handshake runs on a worker thread, never on
 * the accept thread, so one slow or hostile peer cannot hold up everyone else.
 */
public final class ClientListener {
    public static final int DEFAULT_PORT = 2582;
    private static final long FAILURE_LOG_INTERVAL_MS = 60 * 60 * 1000L;

    public interface Handler {
        Hello localHello();

        byte[] pskKey();

        /** Handshake done; the listener has NOT been started yet. Called on a worker thread. */
        void onConnected(MuxConnection conn);

        /** The peer does not speak v3 (an old v2 client). */
        void onOutdatedClient(String ip);

        /** The listening socket could not be opened. */
        void onBindFailed(int port, Exception e);
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService handshakes = new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Threads.factory("handshake", Thread.NORM_PRIORITY));
    /** ip + failure kind -> last time it was logged (rate-limits reconnect loops of old clients). */
    private final Map<String, Long> failureLogged = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;

    public ClientListener(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public static int configuredPort() {
        return Integer.getInteger("srd.port", DEFAULT_PORT);
    }

    public int port() {
        return port;
    }

    public void start() {
        Threads.start("client-accept", this::acceptLoop);
    }

    private void acceptLoop() {
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            Log.info("Secure Server started on port " + port + ". Waiting for clients...");
            while (!ss.isClosed()) {
                Socket s = ss.accept();
                String ip = s.getInetAddress().getHostAddress();
                if (!NetUtil.isLocalIPAddress(ip)) {
                    Log.info("Client " + ip + " is on a public network. Rejecting.");
                    closeQuietly(s);
                    continue;
                }
                try {
                    handshakes.execute(() -> handshake(s, ip));
                } catch (Exception rejected) {
                    Log.warn("Too many concurrent handshakes; dropping " + ip);
                    closeQuietly(s);
                }
            }
        } catch (IOException e) {
            if (serverSocket == null) {
                Log.error("Could not listen on port " + port, e);
                handler.onBindFailed(port, e);
            } else {
                Log.info("Listener stopped: " + e.getMessage());
            }
        }
    }

    private void handshake(Socket s, String ip) {
        try {
            MuxConnection conn = MuxConnection.accept(s, handler.localHello(), handler.pskKey());
            failureLogged.remove(ip + "|outdated");
            try {
                handler.onConnected(conn);
            } catch (RuntimeException e) {
                Log.error("Setting up the session for " + ip + " failed", e);
                conn.close("server error");
            }
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.startsWith("not an SRD v3 peer")) {
                if (shouldLog(ip + "|outdated")) {
                    Log.warn("Client " + ip + " runs an outdated (pre-v3) version: " + msg
                            + " (further attempts from this IP are not logged for an hour)");
                }
                handler.onOutdatedClient(ip);
            } else if (shouldLog(ip + "|" + msg)) {
                Log.info("Handshake with " + ip + " failed: " + msg);
            }
        } catch (RuntimeException e) {
            Log.error("Handshake with " + ip + " crashed", e);
            closeQuietly(s);
        }
    }

    private boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = failureLogged.get(key);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MS) return false;
        failureLogged.put(key, now);
        return true;
    }

    public void stop() {
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        handshakes.shutdownNow();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
