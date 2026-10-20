package com.srd.client.net;

import com.srd.client.ClientApp;
import com.srd.client.ClientContext;
import com.srd.client.HostSession;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.StatusMessage;
import com.srd.common.status.VpnGuardStatus;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The persistent link from this client to one server console (main or alternative).
 * Reconnects with backoff, a 3 s connect timeout (the old code had none and stalled ~20 s per
 * attempt on an unreachable alternative server), and only logs when the state changes.
 *
 * <p>Over the link the client:
 * <ul>
 *   <li>sends a full-desktop JPEG snapshot every {@code SET_INTERVAL} seconds (and on
 *       REFRESH_SCREEN);</li>
 *   <li>sends VPN Guard status whenever it changes, plus every 30 s;</li>
 *   <li>acts as host for the server's live view, control, audio and pen through a
 *       {@link HostSession}.</li>
 * </ul>
 */
public final class ServerLink {
    private static final long STATUS_RESEND_SEC = 30;
    private static final ExecutorService SNAPSHOTS = Threads.pool("snapshot", 1);

    private final ClientContext ctx;
    public final String host;
    public final int port;
    public final String label;
    private final Consumer<ServerLink> stateListener;
    private final Consumer<VpnGuardStatus> vpnListener = s -> sendStatus();

    private volatile boolean running = true;
    private volatile MuxConnection conn;
    private volatile HostSession session;
    private volatile int intervalSec = 60;
    private volatile ScheduledFuture<?> snapshotTask;
    private volatile ScheduledFuture<?> statusTask;
    private volatile boolean snapshotInFlight;
    private volatile String lastError;
    /** Last CLIENT_STATE from the server ("" = not reported, e.g. not connected). */
    private volatile String serverState = "";

    public ServerLink(ClientContext ctx, String host, int port, String label, Consumer<ServerLink> stateListener) {
        this.ctx = ctx;
        this.host = host;
        this.port = port;
        this.label = label;
        this.stateListener = stateListener;
    }

    public void start() {
        ctx.vpn.addListener(vpnListener);
        Threads.start("server-link-" + host, this::loop);
    }

    public boolean isConnected() {
        MuxConnection c = conn;
        return c != null && c.isOpen();
    }

    public String lastError() {
        return lastError;
    }

    /** Connected, and the server lists this PC (servers that don't report it count as registered). */
    public boolean isRegistered() {
        return isConnected() && !"NOT_REGISTERED".equals(serverState);
    }

    /** Connected, but the server said this PC is not on its client list (auto-add off). */
    public boolean isNotRegistered() {
        return isConnected() && "NOT_REGISTERED".equals(serverState);
    }

    public void stop() {
        running = false;
        MuxConnection c = conn;
        if (c != null) c.closeGracefully("client shutting down");
    }

    private void loop() {
        long backoff = 2000;
        while (running) {
            try {
                MuxConnection c = MuxConnection.connect(host, port, 3000, ctx.agentHello(), ctx.pskKey);
                backoff = 2000;
                lastError = null;
                CountDownLatch closed = new CountDownLatch(1);
                runSession(c, closed);
                closed.await();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (!msg.equals(lastError)) Log.info(label + " (" + host + ":" + port + ") unavailable: " + msg);
                lastError = msg;
            }
            if (!running) return;
            Threads.sleepQuietly(backoff);
            backoff = Math.min(15_000, backoff * 2);
        }
    }

    private void runSession(MuxConnection c, CountDownLatch closed) {
        HostSession hs = new HostSession(c, ctx.streamer, ctx.injector, ctx.annotations,
                () -> ctx.serverControlEnabled, () -> true);
        session = hs;
        conn = c;
        Log.info("Connected to " + label + " " + host + ":" + port + " (" + c.remoteHello().name() + ")");
        c.start(new MuxConnection.Listener() {
            @Override
            public void onMessage(MuxConnection m, int type, byte[] p) {
                if (type == MessageType.CONTROL) {
                    handleControl(new String(p, StandardCharsets.UTF_8));
                } else if (!hs.handle(type, p)) {
                    Log.debug(label + ": ignored message type " + type);
                }
            }

            @Override
            public void onClosed(MuxConnection m, String reason) {
                Log.info("Disconnected from " + label + " " + host + ": " + reason);
                hs.close();
                cancelTasks();
                serverState = "";
                conn = null;
                session = null;
                stateListener.accept(ServerLink.this);
                closed.countDown();
            }
        });
        stateListener.accept(this);
        sendStatus();
        statusTask = Threads.scheduler().scheduleWithFixedDelay(this::sendStatus, STATUS_RESEND_SEC, STATUS_RESEND_SEC, TimeUnit.SECONDS);
        scheduleSnapshots(0);
    }

    private void cancelTasks() {
        ScheduledFuture<?> t = snapshotTask;
        if (t != null) t.cancel(false);
        t = statusTask;
        if (t != null) t.cancel(false);
    }

    private synchronized void scheduleSnapshots(long initialDelaySec) {
        ScheduledFuture<?> t = snapshotTask;
        if (t != null) t.cancel(false);
        if (!isConnected()) return;
        snapshotTask = Threads.scheduler().scheduleWithFixedDelay(this::requestSnapshot,
                initialDelaySec, Math.max(1, intervalSec), TimeUnit.SECONDS);
    }

    private void requestSnapshot() {
        if (snapshotInFlight || !isConnected()) return;
        snapshotInFlight = true;
        SNAPSHOTS.execute(() -> {
            try {
                BufferedImage img = ctx.streamer.snapshot();
                MuxConnection c = conn;
                if (img != null && c != null && c.isOpen()) c.send(MessageType.THUMBNAIL, Jpeg.encode(img, ctx.snapshotQuality));
            } catch (Exception e) {
                Log.warn(label + ": snapshot failed: " + e);
            } finally {
                snapshotInFlight = false;
            }
        });
    }

    private void sendStatus() {
        MuxConnection c = conn;
        if (c == null || !c.isOpen()) return;
        Map<String, String> m = new LinkedHashMap<>(ctx.vpn.current().toMap());
        m.put("client.version", ClientContext.VERSION);
        m.put("client.viewers", Integer.toString(PeerHost.viewerCount()));
        c.send(MessageType.STATUS, StatusMessage.encode(m));
    }

    private void handleControl(String cmd) {
        if (cmd.startsWith("SERVER_CONFIG:")) {
            applyServerConfig(cmd.substring("SERVER_CONFIG:".length()));
        } else if (cmd.startsWith("SET_INTERVAL:")) {
            try {
                intervalSec = Math.max(1, Integer.parseInt(cmd.substring("SET_INTERVAL:".length()).trim()));
                Log.info(label + ": snapshot interval " + intervalSec + " s");
                scheduleSnapshots(intervalSec);
            } catch (NumberFormatException e) {
                Log.warn("bad SET_INTERVAL: " + cmd);
            }
        } else if (cmd.startsWith("CLIENT_STATE:")) {
            String st = cmd.substring("CLIENT_STATE:".length()).trim();
            if (!st.equals(serverState)) {
                serverState = st;
                Log.info(label + ": server reports this client as " + st);
                stateListener.accept(this);
            }
        } else if (cmd.startsWith("NOTIFY:")) {
            // NOTIFY:<WARN|INFO>|<title>|<text> — the server saw a problem (or its end) it wants this PC to show.
            String[] f = cmd.substring("NOTIFY:".length()).split("\\|", 3);
            if (f.length == 3) ClientApp.notifyUser(f[1], f[2], "WARN".equals(f[0]));
        } else if (cmd.equals("REFRESH_SCREEN")) {
            requestSnapshot();
        } else {
            Log.info(label + ": unknown control command " + cmd);
        }
    }

    private void applyServerConfig(String cfg) {
        Log.info(label + ": server configuration " + cfg);
        for (String kv : cfg.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim();
            String v = kv.substring(eq + 1).trim();
            switch (k) {
                case "SCREEN_CONTROL":
                    ctx.serverControlEnabled = "1".equals(v);
                    break;
                case "IMAGE_QUALITY":
                    try {
                        ctx.snapshotQuality = Math.max(0.1f, Math.min(1f, Float.parseFloat(v)));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                case "CLIENT_NOTIFY":
                    ctx.serverNotify = !"0".equals(v);
                    break;
                case "NOTIFY_DELAY":
                    try {
                        ctx.notifyDelaySec = Math.max(0, Math.min(3600, Integer.parseInt(v)));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                case "ALTERNATIVE_IPS":
                    ServerLinks.setAlternatives(v.isEmpty() ? new String[0] : v.split(","));
                    break;
                default:
                    break;
            }
        }
    }
}
