package com.srd.server.net;

import java.util.Map;

/** Callbacks from sessions, invoked on the connection's reader thread (keep them fast). */
public interface SessionEvents {
    /** A full-resolution JPEG of the client's desktop (already coalesced: latest only). */
    void onThumbnail(ClientSession s, byte[] jpeg);

    void onStatus(ClientSession s, Map<String, String> status);

    void onClientClosed(ClientSession s, String reason);

    void onPeerClosed(PeerSession s, String reason);
}
