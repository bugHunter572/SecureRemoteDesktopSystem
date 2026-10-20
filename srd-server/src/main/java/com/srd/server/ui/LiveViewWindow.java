package com.srd.server.ui;

import com.srd.common.ui.Icons;
import com.srd.common.ui.RemoteViewerFrame;
import com.srd.common.ui.ViewerSession;
import java.awt.Component;
import javax.swing.JButton;

/**
 * Server-side glue around the shared {@link RemoteViewerFrame}: adds the record start/stop
 * buttons (v2 rec icons) to its toolbar, keeps a "[REC]" title marker, and can lock the Remote /
 * Sound toggles (server SCREEN_CONTROL off, or viewing this very machine). EDT only.
 */
public final class LiveViewWindow {
    private final RemoteViewerFrame frame;
    private final ViewerSession session;
    private final String baseTitle;
    private final JButton recStart;
    private final JButton recStop;

    public LiveViewWindow(ViewerSession session, String title, Runnable onStartRecording, Runnable onStopRecording) {
        this.session = session;
        this.baseTitle = title;
        this.frame = new RemoteViewerFrame(session, title);
        recStart = new JButton(Icons.icon("rec-start.png", 24));
        recStart.setToolTipText("Start Recording");
        if (recStart.getIcon() == null) recStart.setText("REC");
        recStart.addActionListener(e -> onStartRecording.run());
        recStop = new JButton(Icons.icon("rec-stop.png", 24));
        recStop.setToolTipText("Stop Recording");
        if (recStop.getIcon() == null) recStop.setText("STOP");
        recStop.addActionListener(e -> onStopRecording.run());
        frame.addToolbarComponent(recStart);
        frame.addToolbarComponent(recStop);
        setRecording(false);
    }

    public RemoteViewerFrame frame() {
        return frame;
    }

    public ViewerSession session() {
        return session;
    }

    public void setRecording(boolean recording) {
        recStart.setEnabled(!recording);
        recStop.setEnabled(recording);
        frame.setTitle(recording ? baseTitle + "  [REC]" : baseTitle);
    }

    public void setStatusMessage(String msg) {
        session.panel().setStatusMessage(msg);
    }

    /** Enables/disables the Remote toggle; switching it off also releases control. */
    public void setRemoteAllowed(boolean allowed, String whyNot) {
        frame.setRemoteControlAllowed(allowed, whyNot);
    }

    /** Enables/disables the Sound toggle. */
    public void setSoundAllowed(boolean allowed, String whyNot) {
        frame.setSoundAllowed(allowed, whyNot);
    }

    public void show() {
        frame.setVisible(true);
        frame.toFront();
    }

    public void toFront() {
        if (!frame.isVisible()) frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    public void dispose() {
        frame.dispose();
    }
}
