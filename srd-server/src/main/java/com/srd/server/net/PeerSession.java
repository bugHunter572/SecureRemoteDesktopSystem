package com.srd.server.net;

import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.util.Log;
import java.io.IOException;

/**
 * Outbound viewer connection to a broadcasting client's peer port (2583). Everything the host
 * sends goes to the viewer session.
 */
public final class PeerSession extends RemoteSession {
    public static final int PEER_PORT = 2583;

    private final SessionEvents events;
    private final String displayName;

    private PeerSession(MuxConnection conn, SessionEvents events) {
        super(conn);
        this.events = events;
        String n = conn.remoteHello().get(Hello.K_NAME);
        this.displayName = (n == null || n.trim().isEmpty()) ? "Peer-" + conn.remoteAddress() : n.trim();
    }

    /** Connects and handshakes (blocking, ≤3 s connect + handshake); does not start the listener yet. */
    public static PeerSession connect(String ip, String serverName, byte[] pskKey, SessionEvents events) throws IOException {
        Hello hello = Hello.builder(Hello.ROLE_VIEWER)
                .put(Hello.K_NAME, serverName)
                .put(Hello.K_ID, serverName)
                .put(Hello.K_VERSION, com.srd.server.ServerApp.VERSION)
                .build();
        MuxConnection conn = MuxConnection.connect(ip, PEER_PORT, 3000, hello, pskKey);
        return new PeerSession(conn, events);
    }

    public void start() {
        conn.start(this);
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public void onMessage(MuxConnection c, int type, byte[] payload) {
        if (type == MessageType.CONTROL) {
            logControl(payload);
            return;
        }
        if (!toViewer(type, payload)) Log.debug("[peer " + ip + "] unhandled message type " + type);
    }

    @Override
    public void onClosed(MuxConnection c, String reason) {
        events.onPeerClosed(this, reason);
    }
}
