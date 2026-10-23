package com.srd.client.viewer;

import com.srd.client.ClientContext;
import com.srd.common.input.KeyboardGrabber;
import com.srd.common.protocol.Hello;
import com.srd.common.input.KeyboardGrabbers;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import com.srd.common.ui.RemoteViewerFrame;
import com.srd.common.ui.ViewerSession;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/**
 * "Connect to Another PC": a peer viewer window onto another broadcasting client, with remote
 * control, sound, pen annotations, monitor/quality selection and screenshots.
 */
public final class PeerViewerWindow {
    private PeerViewerWindow() {
    }

    public static void open(ClientContext ctx, String ip, int port) {
        Threads.start("peer-connect-" + ip, () -> {
            MuxConnection conn;
            try {
                conn = MuxConnection.connect(ip, port, 4000, ctx.viewerHello(), ctx.pskKey);
            } catch (Exception e) {
                Log.warn("Peer connect to " + ip + ":" + port + " failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Could not connect to " + ip + ":" + port + "\n" + e.getMessage()
                                + "\n\nIs broadcasting enabled on that PC, and do both PCs run v" + ClientContext.VERSION + "?",
                        "Connection Error", JOptionPane.ERROR_MESSAGE));
                return;
            }
            SwingUtilities.invokeLater(() -> show(ctx, conn, ip));
        });
    }

    private static void show(ClientContext ctx, MuxConnection conn, String ip) {
        ViewerSession session = new ViewerSession(conn, ctx.clientName);
        KeyboardGrabber grabber = KeyboardGrabbers.create();
        if (grabber != null) session.setKeyboardGrabber(grabber);
        String remoteName = conn.remoteHello().name();
        RemoteViewerFrame frame = new RemoteViewerFrame(session, "Peer Screen Viewer - " + remoteName + " (" + ip + ")");
        if (isMac(conn.remoteHello())) hidePenButton(frame);
        frame.setOnClose(() -> {
            session.close();
            conn.closeGracefully("viewer closed");
        });
        conn.start(new MuxConnection.Listener() {
            @Override
            public void onMessage(MuxConnection c, int type, byte[] p) {
                if (session.handle(type, p)) return;
                if (type == MessageType.CONTROL) Log.info("peer " + remoteName + ": " + new String(p, StandardCharsets.UTF_8));
            }

            @Override
            public void onClosed(MuxConnection c, String reason) {
                session.panel().releaseAllInput();
                SwingUtilities.invokeLater(() -> session.panel().setStatusMessage("Connection lost: " + reason));
            }
        });
        session.startStream(StreamRequest.PRESET_BALANCED, ScreenInfo.ALL_MONITORS, 60);
        frame.setVisible(true);
        Log.info("Viewing peer " + remoteName + " at " + ip);
    }

    /** The broadcaster is a Mac (its hello carries {@code os.name}, e.g. "Mac OS X 15.1 (aarch64)"). */
    static boolean isMac(Hello h) {
        String os = h.get(Hello.K_OS, "").toLowerCase(Locale.ROOT);
        return os.startsWith("mac") || os.startsWith("darwin");
    }

    /**
     * A Mac broadcaster does not accept drawings, so the pen button is removed from the control
     * bar (the rest - control, sound, screenshots - stays). Found by its tooltip, which keeps the
     * shared viewer code untouched.
     */
    private static void hidePenButton(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JToggleButton) {
                String tip = ((JToggleButton) child).getToolTipText();
                if (tip != null && tip.startsWith("Draw on the remote screen")) {
                    child.setVisible(false);
                    Log.info("Pen hidden: drawing is not available when a Mac is broadcasting");
                    return;
                }
            }
            if (child instanceof Container) hidePenButton((Container) child);
        }
    }
}
