package com.srd.client.overlay;

import com.srd.client.capture.ScreenLayout;
import com.srd.client.mac.MacOverlayNative;
import com.srd.common.annotation.AnnotationModel;
import com.srd.common.annotation.AnnotationRenderer;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Shows viewer annotations on the broadcaster's real screen: one transparent, click-through,
 * always-on-top window per monitor.
 *
 * <p>On Windows 10 2004+ the windows are marked {@code WDA_EXCLUDEFROMCAPTURE}, so they're
 * visible to the person at this PC but left out of every screen capture: Zoom, Google Meet,
 * Teams and OBS shares, and our own capture too, so viewers don't get the strokes back baked
 * into the video. Viewers render the strokes themselves from the stroke messages, which is why
 * peers see them smoothly and with no latency.
 *
 * <p>Windows exist only while there is something to draw.
 */
final class AnnotationOverlay {
    private final AnnotationModel model;
    private final Supplier<ScreenLayout> layout;
    private final AnnotationRenderer renderer = new AnnotationRenderer();
    private final List<JWindow> windows = new ArrayList<>();
    private Timer anim;
    private volatile boolean captureExcluded = true;
    private boolean warned;

    AnnotationOverlay(AnnotationModel model, Supplier<ScreenLayout> layout) {
        this.model = model;
        this.layout = layout;
        model.addListener(() -> SwingUtilities.invokeLater(this::onChanged));
    }

    boolean isCaptureExcluded() {
        return captureExcluded;
    }

    void ensureShown() {
        SwingUtilities.invokeLater(this::onChanged);
    }

    private void onChanged() {
        if (model.isEmpty()) {
            hide();
            return;
        }
        if (windows.isEmpty()) show();
        for (JWindow w : windows) w.repaint();
        if (anim == null) {
            anim = new Timer(16, e -> {
                boolean animating = model.tick(System.currentTimeMillis());
                for (JWindow w : windows) w.repaint();
                if (!animating) {
                    ((Timer) e.getSource()).stop();
                    anim = null;
                }
            });
            anim.start();
        }
    }

    private void show() {
        ScreenLayout l = layout.get();
        Rectangle desk = l.physicalRegion(-1);
        boolean allExcluded = true;
        for (ScreenLayout.Monitor m : l.monitors) {
            JWindow w = new JWindow();
            w.setAlwaysOnTop(true);
            w.setFocusableWindowState(false);
            w.setAutoRequestFocus(false);
            w.setType(Window.Type.UTILITY);
            w.setBackground(new Color(0, 0, 0, 0));
            w.setContentPane(new Canvas(m, desk));
            w.setBounds(m.logical);
            w.setVisible(true);
            boolean excluded = OverlayNative.configure(w);
            allExcluded &= excluded;
            windows.add(w);
        }
        captureExcluded = allExcluded;
        if (Os.MAC) {
            // Windows gets guaranteed click-through + capture exclusion from OverlayNative.configure
            // (above). macOS has no app-facing equivalent to WDA_EXCLUDEFROMCAPTURE, so configure()
            // returned false and captureExcluded stays false (honest: full-screen shares may still
            // include the strokes). We still make the overlay click-through so it never blocks the
            // broadcaster, plus setSharingType=none as a best-effort hide for window-sharing. The
            // NSWindow peer realizes slightly after setVisible, so apply now and once more shortly.
            MacOverlayNative.configureOverlays();
            Timer settle = new Timer(150, e -> {
                MacOverlayNative.configureOverlays();
                ((Timer) e.getSource()).stop();
            });
            settle.setRepeats(false);
            settle.start();
        }
        if (allExcluded) {
            Log.info("Annotation stealth ON: drawings are hidden from all screen capture/sharing "
                    + "(Zoom, Meet, Teams, OBS) while visible on this screen.");
        } else if (!warned) {
            warned = true;
            Log.warn("Annotation stealth NOT available on this OS/version: meeting screen shares may include the drawings.");
        }
        if (statusCallback != null) statusCallback.accept(allExcluded);
    }

    private volatile java.util.function.Consumer<Boolean> statusCallback;

    /** Notified (true=stealth active, false=overlay visible to capture) when the overlay first shows. */
    void onStatus(java.util.function.Consumer<Boolean> cb) {
        statusCallback = cb;
    }

    private void hide() {
        for (JWindow w : windows) w.dispose();
        windows.clear();
        if (anim != null) {
            anim.stop();
            anim = null;
        }
    }

    void dispose() {
        SwingUtilities.invokeLater(this::hide);
    }

    /** Paints desktop-space strokes mapped onto one monitor. */
    private final class Canvas extends JComponent {
        private final Rectangle phys;
        private final Rectangle desk;

        Canvas(ScreenLayout.Monitor m, Rectangle desk) {
            this.phys = m.physical;
            this.desk = desk;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            // Monitor-local logical units per physical pixel.
            double sx = getWidth() / (double) Math.max(1, phys.width);
            double sy = getHeight() / (double) Math.max(1, phys.height);
            double ox = (desk.x - phys.x) * sx;
            double oy = (desk.y - phys.y) * sy;
            renderer.paint(g2, model.snapshot(), ox, oy, desk.width * sx, desk.height * sy, System.currentTimeMillis());
        }
    }
}
