package com.srd.server.net;

import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.StatusMessage;
import com.srd.common.util.Log;
import java.awt.Dimension;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A monitored client agent connected to our port 2582. Identified by its IP (clientId = IP, as
 * in v2); the display name comes from the agent's Hello.
 *
 * <p>Thumbnails are coalesced: the reader thread only swaps the newest JPEG into a slot and, if
 * no decode is pending, schedules one on the decode pool — a slow decode never backs up the
 * connection and at most one undecoded image per client is held.
 */
public final class ClientSession extends RemoteSession {
    private final SessionEvents events;
    private final Executor decodePool;
    private final String clientId;
    private final String helloName;
    private final Dimension helloScreen;
    private final AtomicReference<byte[]> pendingThumb = new AtomicReference<>();
    private final long connectedAtMs = System.currentTimeMillis();
    private volatile long lastFrameMs;

    public ClientSession(MuxConnection conn, SessionEvents events, Executor decodePool) {
        super(conn);
        this.events = events;
        this.decodePool = decodePool;
        this.clientId = conn.remoteAddress();
        Hello h = conn.remoteHello();
        String n = h.get(Hello.K_NAME);
        this.helloName = (n == null || n.trim().isEmpty()) ? clientId : n.trim();
        this.helloScreen = parseScreen(h.get(Hello.K_SCREEN));
    }

    static Dimension parseScreen(String s) {
        if (s != null) {
            int x = s.toLowerCase().indexOf('x');
            if (x > 0) {
                try {
                    return new Dimension(Integer.parseInt(s.substring(0, x).trim()), Integer.parseInt(s.substring(x + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new Dimension(1920, 1080);
    }

    public String clientId() {
        return clientId;
    }

    /** Display name announced by the agent (K_NAME), or the IP. */
    public String helloName() {
        return helloName;
    }

    public Dimension helloScreen() {
        return helloScreen;
    }

    public long connectedAtMs() {
        return connectedAtMs;
    }

    /** When the last picture arrived, or 0 if none has since this connection started. */
    public long lastFrameMs() {
        return lastFrameMs;
    }

    @Override
    public void onMessage(MuxConnection c, int type, byte[] payload) {
        switch (type) {
            case MessageType.THUMBNAIL:
                lastFrameMs = System.currentTimeMillis();
                if (pendingThumb.getAndSet(payload) == null) decodePool.execute(this::drainThumbnails);
                break;
            case MessageType.STATUS:
                events.onStatus(this, StatusMessage.decode(payload));
                break;
            case MessageType.CONTROL:
                logControl(payload);
                break;
            default:
                if (!toViewer(type, payload)) Log.debug("[" + clientId + "] unhandled message type " + type);
        }
    }

    private void drainThumbnails() {
        byte[] jpeg;
        while ((jpeg = pendingThumb.getAndSet(null)) != null) {
            try {
                events.onThumbnail(this, jpeg);
            } catch (Exception e) {
                Log.error("Thumbnail handling failed for " + clientId, e);
            }
        }
    }

    @Override
    public void onClosed(MuxConnection c, String reason) {
        pendingThumb.set(null);
        events.onClientClosed(this, reason);
    }

    @Override
    public String toString() {
        return "ClientSession[" + clientId + " " + helloName + "]";
    }
}
