package com.srd.common.ui;

import com.srd.common.annotation.PenToolbar;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.util.Threads;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Remote view window shared by the client's peer viewer and the server's live view.
 *
 * <p>An auto-hiding control bar at the top center holds:
 * <ul>
 *   <li>toggles: Remote control, Sound, Pen;</li>
 *   <li>selectors: view mode, monitor, quality;</li>
 *   <li>buttons: screenshot (also Alt+D), stats, fullscreen;</li>
 *   <li>any extra controls the owner adds with {@link #addToolbarComponent}.</li>
 * </ul>
 * The bar slides in when the pointer reaches the top edge, and the pen palette appears under it
 * while pen mode is on.
 */
public class RemoteViewerFrame extends JFrame {
    private static final Color BAR_BG = new Color(0x14, 0x14, 0x18, 215);
    private static final Color FG = new Color(0xEC, 0xEC, 0xF0);
    private static final int PEEK = 6;

    private final ViewerSession session;
    private final boolean viewOnly;
    private final JPanel bar = new BarPanel();
    private final PenToolbar penBar;
    private final JLayeredPane layers;
    private final JToggleButton remoteToggle;
    private final JToggleButton soundToggle;
    private final JToggleButton penToggle;
    private final JComboBox<String> monitorBox = new JComboBox<>(new String[] {"All monitors"});
    private Runnable onClose;
    private boolean barExpanded = true;
    private int barTargetY;
    private final Timer barAnim;
    private final Timer barAutoHide;
    private Rectangle windowedBounds;
    private boolean updatingMonitors;
    /** dispose() is needed to toggle decorations; it must not count as the user closing the window. */
    private boolean reconfiguring;

    public RemoteViewerFrame(ViewerSession session, String title) {
        this(session, title, false);
    }

    /**
     * @param viewOnly watch only: no tool bar, no drawing, no screenshots; the window opens maximized,
     *                 F11 or a double-click toggles full screen. Control and audio are never switched on.
     */
    public RemoteViewerFrame(ViewerSession session, String title, boolean viewOnly) {
        super(title);
        this.viewOnly = viewOnly;
        this.session = session;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1280, 800);
        setLocationByPlatform(true);
        ImageIcon appIcon = Icons.icon("server-icon.png", 64);
        if (appIcon != null) setIconImage(appIcon.getImage());

        RemoteScreenPanel panel = session.panel();
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(0x10, 0x10, 0x12));
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll, BorderLayout.CENTER);
        if (!viewOnly) panel.setScreenshotHotkey(this::saveScreenshot);

        // ---- control bar ----
        penToggle = new JToggleButton(new GlyphIcon(GlyphIcon.PEN, false));
        penToggle.setSelectedIcon(new GlyphIcon(GlyphIcon.PEN, true));
        penToggle.setToolTipText("Draw on the remote screen (only peers using this app see it)");
        styleButton(penToggle);
        remoteToggle = toggle("remote", "Remote control (mouse + keyboard)");
        remoteToggle.addActionListener(e -> {
            session.setControlEnabled(remoteToggle.isSelected());
            if (remoteToggle.isSelected() && penToggle.isSelected()) {
                penToggle.setSelected(false);
                setPen(false);
            }
            // While actively controlling, suppress button tooltips so they don't pop up over the
            // remote screen and distract; restore them when control is off.
            setToolbarTooltips(!remoteToggle.isSelected());
            refreshIcons();
            panel.requestFocusInWindow();
        });
        soundToggle = toggle("sound", "Remote audio");
        soundToggle.addActionListener(e -> {
            session.setAudioEnabled(soundToggle.isSelected());
            refreshIcons();
        });
        penToggle.addActionListener(e -> setPen(penToggle.isSelected()));

        JComboBox<String> viewBox = new JComboBox<>(new String[] {"Fit", "Stretch", "1:1"});
        viewBox.setToolTipText("Scaling");
        viewBox.addActionListener(e -> panel.setViewMode(
                viewBox.getSelectedIndex() == 1 ? ViewTransform.Mode.STRETCH
                        : viewBox.getSelectedIndex() == 2 ? ViewTransform.Mode.ACTUAL : ViewTransform.Mode.FIT));
        monitorBox.setToolTipText("Which monitor to show");
        monitorBox.addActionListener(e -> {
            if (updatingMonitors) return;
            int i = monitorBox.getSelectedIndex();
            session.setMonitor(i <= 0 ? ScreenInfo.ALL_MONITORS : i - 1);
        });
        JComboBox<String> qualityBox = new JComboBox<>(new String[] {"Speed", "Balanced", "Quality"});
        qualityBox.setSelectedIndex(StreamRequest.PRESET_BALANCED);
        qualityBox.setToolTipText("Picture quality vs. bandwidth");
        qualityBox.addActionListener(e -> session.setPreset(qualityBox.getSelectedIndex()));
        for (JComboBox<?> b : new JComboBox<?>[] {viewBox, monitorBox, qualityBox}) {
            b.setFocusable(false);
            b.setFont(b.getFont().deriveFont(11f));
        }

        JButton shot = iconButton(new GlyphIcon(GlyphIcon.CAMERA, false), "Save screenshot of the whole screen (Alt+D)");
        shot.addActionListener(e -> saveScreenshot());
        JButton area = iconButton(new GlyphIcon(GlyphIcon.AREA, false), "Screenshot of a selected area (drag a rectangle)");
        area.addActionListener(e -> saveAreaScreenshot());
        JToggleButton stats = new JToggleButton(new GlyphIcon(GlyphIcon.STATS, false));
        stats.setSelectedIcon(new GlyphIcon(GlyphIcon.STATS, true));
        stats.setToolTipText("Show connection statistics");
        styleButton(stats);
        stats.addActionListener(e -> panel.setShowStats(stats.isSelected()));
        JButton full = iconButton(new GlyphIcon(GlyphIcon.FULLSCREEN, false), "Full screen (toggle)");
        full.addActionListener(e -> toggleFullScreen());

        bar.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 2));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 18, 4, 18));
        bar.add(remoteToggle);
        bar.add(soundToggle);
        bar.add(penToggle);
        bar.add(gap());
        bar.add(viewBox);
        bar.add(monitorBox);
        bar.add(qualityBox);
        bar.add(gap());
        bar.add(shot);
        bar.add(area);
        bar.add(stats);
        bar.add(full);
        refreshIcons();

        penBar = new PenToolbar(session.pen());
        penBar.setVisible(false);

        layers = getLayeredPane();
        if (!viewOnly) {
            layers.add(bar, JLayeredPane.PALETTE_LAYER);
            layers.add(penBar, JLayeredPane.PALETTE_LAYER);
        }

        barAnim = new Timer(15, e -> animateBar());
        barAutoHide = new Timer(2500, e -> collapseBarIfIdle());
        barAutoHide.setRepeats(false);

        MouseAdapter reveal = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), layers);
                if (p.y < 28 && Math.abs(p.x - layers.getWidth() / 2) < bar.getWidth() / 2 + 60) expandBar();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                mouseMoved(e);
            }
        };
        panel.addMouseMotionListener(reveal);
        bar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                expandBar();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                barAutoHide.restart();
            }
        });
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutBars();
            }
        });

        if (viewOnly) installViewOnlyBehavior(panel);
        java.util.function.Consumer<ScreenInfo> screenListener = si -> SwingUtilities.invokeLater(() -> updateMonitors(si));
        session.addScreenInfoListener(screenListener);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                if (viewOnly) {
                    panel.requestFocusInWindow();
                    return;
                }
                layoutBars();
                barAutoHide.restart();
                panel.requestFocusInWindow();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (reconfiguring) return;
                session.removeScreenInfoListener(screenListener);
                barAnim.stop();
                barAutoHide.stop();
                Runnable r = onClose;
                if (r != null) r.run();
            }
        });
    }

    public ViewerSession session() {
        return session;
    }

    /** Watch-only extras: maximized start, F11 / double-click full screen. Called by the constructor. */
    private void installViewOnlyBehavior(RemoteScreenPanel panel) {
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        javax.swing.KeyStroke f11 = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F11, 0);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(f11, "fullscreen");
        getRootPane().getActionMap().put("fullscreen", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggleFullScreen();
            }
        });
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) toggleFullScreen();
            }
        });
    }

    /** Runs once when the window is closed (the owner closes the session/connection). */
    public void setOnClose(Runnable r) {
        onClose = r;
    }

    /** Adds an owner-specific control (e.g. record buttons) to the bar. */
    public void addToolbarComponent(JComponent c) {
        if (c instanceof AbstractButton) styleButton((AbstractButton) c);
        bar.add(c);
        layoutBars();
    }

    /**
     * Enables/disables the Remote toggle (e.g. the server's SCREEN_CONTROL is off, or the viewed
     * client is this very machine). Turning it off also ends remote control.
     */
    public void setRemoteControlAllowed(boolean allowed, String reasonTooltip) {
        remoteToggle.setEnabled(allowed);
        remoteToggle.setToolTipText(allowed ? "Remote control (mouse + keyboard)" : reasonTooltip);
        if (!allowed && remoteToggle.isSelected()) {
            remoteToggle.setSelected(false);
            session.setControlEnabled(false);
            refreshIcons();
        }
    }

    /** Enables/disables the Sound toggle; turning it off also stops playback. */
    public void setSoundAllowed(boolean allowed, String reasonTooltip) {
        soundToggle.setEnabled(allowed);
        soundToggle.setToolTipText(allowed ? "Remote audio" : reasonTooltip);
        if (!allowed && soundToggle.isSelected()) {
            soundToggle.setSelected(false);
            session.setAudioEnabled(false);
            refreshIcons();
        }
    }

    /** Shows/hides tooltips on all toolbar buttons (off while actively controlling the remote). */
    private void setToolbarTooltips(boolean on) {
        for (Component c : bar.getComponents()) toggleTip(c, on);
    }

    private static void toggleTip(Component c, boolean on) {
        if (!(c instanceof JComponent)) return;
        JComponent jc = (JComponent) c;
        if (on) {
            Object saved = jc.getClientProperty("tip0");
            if (saved != null) jc.setToolTipText((String) saved);
        } else {
            String tip = jc.getToolTipText();
            if (tip != null) {
                jc.putClientProperty("tip0", tip);
                jc.setToolTipText(null);
            }
        }
    }

    /** Reflects programmatic state into the toggles (e.g. audio already on). */
    public void syncToggles() {
        remoteToggle.setSelected(session.isControlEnabled());
        soundToggle.setSelected(session.isAudioEnabled());
        penToggle.setSelected(session.isPenEnabled());
        penBar.setVisible(session.isPenEnabled());
        refreshIcons();
        layoutBars();
    }

    private void setPen(boolean on) {
        session.setPenEnabled(on);
        if (on && remoteToggle.isSelected()) {
            remoteToggle.setSelected(false);
            session.setControlEnabled(false);
            refreshIcons();
        }
        penBar.setVisible(on);
        layoutBars();
        if (on) expandBar();
    }

    private void updateMonitors(ScreenInfo si) {
        updatingMonitors = true;
        try {
            monitorBox.removeAllItems();
            monitorBox.addItem("All monitors");
            for (int i = 0; i < si.monitors.size(); i++) {
                Rectangle r = si.monitors.get(i);
                monitorBox.addItem("Monitor " + (i + 1) + " (" + r.width + "x" + r.height + ")");
            }
            monitorBox.setSelectedIndex(si.selected == ScreenInfo.ALL_MONITORS || si.selected >= si.monitors.size() ? 0 : si.selected + 1);
            monitorBox.setVisible(si.monitors.size() > 1);
        } finally {
            updatingMonitors = false;
        }
        layoutBars();
    }

    /** File-name stem for screenshots, e.g. {@code 2026-10-14_192.168.1.5_OFFICE-PC}. */
    private String screenshotStem() {
        try {
            String addr = session.connection().remoteAddress();
            String name = session.connection().remoteHello().name();
            return Screenshots.stem(addr, name);
        } catch (Exception e) {
            return Screenshots.stem("client", getTitle());
        }
    }

    /**
     * Saves a PNG to Downloads/SecureRemoteDesktop named with today's date, the client IP and its
     * name, copies the image to the clipboard, and shows a camera flash with a "Saved" banner.
     */
    private void saveScreenshot() {
        saveImage(session.panel().snapshotWithAnnotations());
    }

    /** Drag a rectangle over the screen; that part is saved like the full screenshot. */
    private void saveAreaScreenshot() {
        RemoteScreenPanel panel = session.panel();
        if (panel.snapshot() == null) {
            Toolkit.getDefaultToolkit().beep();
            panel.flashSaved("No frame yet");
            return;
        }
        panel.beginRegionSelect(r -> {
            BufferedImage full = panel.snapshotWithAnnotations();
            if (full == null) return;
            int x0 = Math.max(0, Math.round(r[0] * full.getWidth()));
            int y0 = Math.max(0, Math.round(r[1] * full.getHeight()));
            int x1 = Math.min(full.getWidth(), Math.round(r[2] * full.getWidth()));
            int y1 = Math.min(full.getHeight(), Math.round(r[3] * full.getHeight()));
            if (x1 - x0 < 1 || y1 - y0 < 1) return;
            BufferedImage crop = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = crop.createGraphics();
            g.drawImage(full, -x0, -y0, null);
            g.dispose();
            saveImage(crop);
        });
    }

    private void saveImage(BufferedImage img) {
        if (img == null) {
            Toolkit.getDefaultToolkit().beep();
            session.panel().flashSaved("No frame yet");
            return;
        }
        Screenshots.copyToClipboard(img); // immediate, so paste works right away
        session.panel().flashSaved("Saved to Downloads + clipboard");
        String stem = screenshotStem();
        Threads.start("screenshot-save", () -> {
            File f = Screenshots.save(img, stem);
            if (f != null) {
                SwingUtilities.invokeLater(() -> session.panel().flashSaved("Saved: " + f.getName()));
            }
        });
    }

    private void toggleFullScreen() {
        GraphicsDevice dev = getGraphicsConfiguration().getDevice();
        reconfiguring = true;
        try {
            toggleFullScreen(dev);
        } finally {
            // dispose() posts its windowClosed event to the queue instead of delivering it now, so
            // the flag has to outlive this call; clearing it here let that event close the session
            // (the stream froze the moment full screen was toggled). Queued after it, this runs after.
            SwingUtilities.invokeLater(() -> reconfiguring = false);
        }
        SwingUtilities.invokeLater(() -> {
            layoutBars();
            session.panel().requestFocusInWindow();
        });
    }

    private void toggleFullScreen(GraphicsDevice dev) {
        if (dev.getFullScreenWindow() == this) {
            dev.setFullScreenWindow(null);
            dispose();
            setUndecorated(false);
            if (windowedBounds != null) setBounds(windowedBounds);
            setVisible(true);
        } else {
            windowedBounds = getBounds();
            dispose();
            setUndecorated(true);
            setVisible(true);
            dev.setFullScreenWindow(this);
        }
    }

    // ------------------------------------------------------------------ bar layout & animation

    private void layoutBars() {
        if (viewOnly) return;
        Dimension d = bar.getPreferredSize();
        int x = (layers.getWidth() - d.width) / 2;
        int collapsedY = -(d.height - PEEK);
        barTargetY = barExpanded ? 0 : collapsedY;
        Rectangle cur = bar.getBounds();
        int y = cur.height == 0 ? barTargetY : cur.y;
        bar.setBounds(x, y, d.width, d.height);
        if (penBar.isVisible()) {
            Dimension pd = penBar.getPreferredSize();
            penBar.setBounds((layers.getWidth() - pd.width) / 2, Math.max(PEEK, y + d.height + 4), pd.width, pd.height);
        }
        if (y != barTargetY) barAnim.start();
        layers.repaint();
    }

    private void expandBar() {
        if (viewOnly) return;
        barAutoHide.stop();
        if (!barExpanded) {
            barExpanded = true;
            layoutBars();
        }
    }

    private void collapseBarIfIdle() {
        Point p = layers.getMousePosition();
        if (p != null && bar.getBounds().contains(p)) {
            barAutoHide.restart();
            return;
        }
        if (penBar.isVisible() && p != null && penBar.getBounds().contains(p)) {
            barAutoHide.restart();
            return;
        }
        barExpanded = false;
        layoutBars();
    }

    private void animateBar() {
        Rectangle b = bar.getBounds();
        int dy = barTargetY - b.y;
        if (Math.abs(dy) <= 2) {
            b.y = barTargetY;
            barAnim.stop();
        } else {
            b.y += (int) Math.signum(dy) * Math.max(2, Math.abs(dy) / 3);
        }
        bar.setBounds(b);
        if (penBar.isVisible()) {
            Rectangle pb = penBar.getBounds();
            pb.y = Math.max(PEEK, b.y + b.height + 4);
            penBar.setBounds(pb);
        }
    }

    // ------------------------------------------------------------------ widgets

    private JToggleButton toggle(String role, String tip) {
        JToggleButton b = new JToggleButton();
        b.putClientProperty("role", role);
        b.setToolTipText(tip);
        styleButton(b);
        return b;
    }

    private void refreshIcons() {
        for (JToggleButton b : new JToggleButton[] {remoteToggle, soundToggle}) {
            String role = (String) b.getClientProperty("role");
            ImageIcon ic = Icons.icon(role + (b.isSelected() ? "-on" : "-off") + ".png", 26);
            if (ic != null) {
                b.setIcon(ic);
                b.setSelectedIcon(ic);
                b.setText(null);
            } else {
                b.setText((role.equals("remote") ? "Remote " : "Sound ") + (b.isSelected() ? "ON" : "OFF"));
                b.setForeground(FG);
            }
        }
    }

    private static JButton iconButton(Icon icon, String tip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        styleButton(b);
        return b;
    }

    private static void styleButton(AbstractButton b) {
        b.setFocusable(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setForeground(FG);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
    }

    private static Component gap() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(8, 24));
        return p;
    }

    /** Semi-transparent trapezoid, wider at the top (same look as the previous overlay bar). */
    private static final class BarPanel extends JPanel {
        BarPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int slant = Math.min(18, w / 6);
            Path2D p = new Path2D.Double();
            p.moveTo(0, 0);
            p.lineTo(w, 0);
            p.lineTo(w - slant, h);
            p.lineTo(slant, h);
            p.closePath();
            g2.setColor(BAR_BG);
            g2.fill(p);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small vector glyphs for bar buttons. */
    private static final class GlyphIcon implements Icon {
        static final int PEN = 0, CAMERA = 1, STATS = 2, FULLSCREEN = 3, AREA = 4;
        private final int kind;
        private final boolean on;

        GlyphIcon(int kind, boolean on) {
            this.kind = kind;
            this.on = on;
        }

        @Override
        public int getIconWidth() {
            return 26;
        }

        @Override
        public int getIconHeight() {
            return 26;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            if (on) {
                g2.setColor(new Color(0x4F, 0x9D, 0xFF, 110));
                g2.fill(new RoundRectangle2D.Double(0, 0, 26, 26, 8, 8));
            }
            g2.setColor(FG);
            g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case PEN: {
                    Path2D p = new Path2D.Double();
                    p.moveTo(6, 20);
                    p.lineTo(8, 15);
                    p.lineTo(17, 6);
                    p.lineTo(20, 9);
                    p.lineTo(11, 18);
                    p.closePath();
                    g2.draw(p);
                    g2.setColor(new Color(0xE5, 0x39, 0x35));
                    g2.fillOval(4, 19, 4, 4);
                    break;
                }
                case CAMERA:
                    g2.drawRoundRect(4, 8, 18, 12, 4, 4);
                    g2.drawOval(10, 10, 7, 7);
                    g2.drawLine(9, 8, 11, 5);
                    g2.drawLine(11, 5, 15, 5);
                    g2.drawLine(15, 5, 17, 8);
                    break;
                case AREA: {
                    g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3.5f, 2.5f}, 0f));
                    g2.drawRect(4, 5, 17, 13);
                    g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(15, 15, 22, 22);
                    g2.drawLine(22, 22, 22, 17);
                    g2.drawLine(22, 22, 17, 22);
                    break;
                }
                case STATS:
                    g2.drawLine(6, 20, 6, 14);
                    g2.drawLine(11, 20, 11, 8);
                    g2.drawLine(16, 20, 16, 11);
                    g2.drawLine(21, 20, 21, 5);
                    break;
                default:
                    g2.drawLine(5, 10, 5, 5);
                    g2.drawLine(5, 5, 10, 5);
                    g2.drawLine(16, 5, 21, 5);
                    g2.drawLine(21, 5, 21, 10);
                    g2.drawLine(21, 16, 21, 21);
                    g2.drawLine(21, 21, 16, 21);
                    g2.drawLine(10, 21, 5, 21);
                    g2.drawLine(5, 21, 5, 16);
            }
            g2.dispose();
        }
    }
}
