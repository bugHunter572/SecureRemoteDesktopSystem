package com.srd.server.net;

import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.ui.ViewerSession;
import com.srd.common.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * One v3 connection to a machine whose screen we can view: either a monitored agent
 * ({@link ClientSession}) or a peer-port broadcaster ({@link PeerSession}).
 *
 * <p>Owns at most ONE {@link ViewerSession}, shared by its users (the live window and/or the
 * recorder) with a simple user set as reference count. The stream request follows the users:
 * live window = balanced 30 fps; recorder alone = speed preset at 10 fps. Acquire/release run on
 * the EDT (the viewer creates Swing components).
 */
public abstract class RemoteSession implements MuxConnection.Listener {
    public static final String USER_LIVE = "live";
    public static final String USER_RECORD = "record";

    protected final MuxConnection conn;
    protected final String ip;
    private final Set<String> users = new HashSet<>();
    private volatile ViewerSession viewer;

    protected RemoteSession(MuxConnection conn) {
        this.conn = conn;
        this.ip = conn.remoteAddress();
    }

    public MuxConnection connection() {
        return conn;
    }

    public String ip() {
        return ip;
    }

    public boolean isOpen() {
        return conn.isOpen();
    }

    public ViewerSession viewer() {
        return viewer;
    }

    public synchronized boolean hasUser(String user) {
        return users.contains(user);
    }

    /** Creates (or reuses) the viewer session for {@code user} and adjusts the stream. */
    public synchronized ViewerSession acquireViewer(String user) {
        ViewerSession v = viewer;
        if (v == null) {
            v = new ViewerSession(conn, "Server");
            viewer = v;
        }
        users.add(user);
        if (USER_LIVE.equals(user)) {
            v.startStream(StreamRequest.PRESET_BALANCED, ScreenInfo.ALL_MONITORS, 30);
        } else if (USER_RECORD.equals(user) && !users.contains(USER_LIVE)) {
            v.startStream(StreamRequest.PRESET_SPEED, ScreenInfo.ALL_MONITORS, 10);
        }
        return v;
    }

    /** Drops {@code user}; the viewer session is closed (stream/audio stopped) with its last user. */
    public synchronized void releaseViewer(String user) {
        ViewerSession v = viewer;
        if (v == null || !users.remove(user)) return;
        if (users.isEmpty()) {
            viewer = null;
            v.close();
            return;
        }
        if (USER_LIVE.equals(user)) {
            // Window gone but the recorder still needs pictures: stop local playback/control only.
            v.setAudioEnabled(false);
            v.setControlEnabled(false);
            v.setPenEnabled(false);
            v.setKeyboardGrabber(null); // closes the live window's system keyboard hook
            if (users.contains(USER_RECORD)) {
                v.startStream(StreamRequest.PRESET_SPEED, ScreenInfo.ALL_MONITORS, 10);
            }
        }
    }

    /** Hands a host→viewer message to the viewer session, if any. */
    protected boolean toViewer(int type, byte[] payload) {
        ViewerSession v = viewer;
        return v != null && v.handle(type, payload);
    }

    public void sendControl(String text) {
        conn.sendText(MessageType.CONTROL, text);
    }

    public void close(String reason) {
        conn.closeGracefully(reason);
    }

    protected void logControl(byte[] payload) {
        Log.info("[" + ip + "] CONTROL from remote: " + new String(payload, StandardCharsets.UTF_8));
    }
}
