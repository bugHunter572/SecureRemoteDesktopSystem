package com.srd.common.ui;

import com.srd.common.annotation.AnnotationModel;
import com.srd.common.annotation.AnnotationRenderer;
import com.srd.common.annotation.PenController;
import com.srd.common.annotation.PenTool;
import com.srd.common.input.InputBatcher;
import com.srd.common.input.InputEvent;
import com.srd.common.input.KeyMap;
import com.srd.common.input.KeyboardGrabber;
import com.srd.common.input.WheelAccumulator;
import com.srd.common.video.FrameBuffer;
import com.srd.common.video.VideoDecoder;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The remote screen view used by every viewer: the client's peer viewer and the server's
 * live/peer views.
 *
 * <ul>
 *   <li><b>Rendering:</b> decoded pixels live in a {@link FrameBuffer}. Only damaged regions are
 *       copied into a GPU-side {@link VolatileImage}, which is then scaled in hardware. The old
 *       viewers pushed the whole full-HD frame through software bilinear scaling on the EDT,
 *       every frame.</li>
 *   <li><b>Mapping:</b> one {@link ViewTransform} serves painting, mouse, pen and cursor, so a
 *       click lands exactly where it is drawn.</li>
 *   <li><b>Input:</b> real button down/up with position (no synthetic clicks), precise wheel,
 *       scancode keys, key auto-repeat forwarded, and everything released when focus is
 *       lost.</li>
 *   <li><b>Pen mode:</b> mouse input draws annotations instead of controlling the host.</li>
 * </ul>
 */
public class RemoteScreenPanel extends JComponent implements VideoDecoder.Sink, Scrollable {
    public enum Mode { VIEW, CONTROL, PEN }

    private static final Color BG = new Color(0x10, 0x10, 0x12);
    private static Cursor blankCursor;

    /** Created lazily: Toolkit cursors throw HeadlessException in a headless JVM (tests, recording). */
    private static synchronized Cursor blankCursor() {
        if (blankCursor == null) {
            blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");
        }
        return blankCursor;
    }

    private volatile FrameBuffer fb;
    private VolatileImage vimg;
    private final Object damageLock = new Object();
    private Rectangle pendingDamage;
    private boolean fullCopyNeeded = true;

    private volatile ViewTransform.Mode viewMode = ViewTransform.Mode.FIT;
    private volatile ViewTransform xf = ViewTransform.compute(ViewTransform.Mode.FIT, 1, 1, 1, 1, 1);
    private double deviceScale = 1;

    private volatile Mode mode = Mode.VIEW;
    private volatile InputBatcher input;
    private final WheelAccumulator wheel = new WheelAccumulator();
    private final Set<Integer> pressedKeys = new HashSet<>();     // packed scancode|vk<<16
    private final Set<Integer> pressedButtons = new HashSet<>();
    private boolean shiftKeyDown;
    private KeyEventDispatcher keyDispatcher;
    private volatile KeyboardGrabber grabber;

    private volatile AnnotationModel annotations;
    private volatile PenController pen;
    private final AnnotationRenderer annotationRenderer = new AnnotationRenderer();
    private final Timer animTimer;

    /** While set, the mouse selects a screen area instead of controlling/drawing; the callback gets {x0, y0, x1, y1} as 0..1 fractions. */
    private volatile java.util.function.Consumer<float[]> regionCallback;
    private java.awt.Point selStart;
    private java.awt.Point selEnd;

    private volatile int cursorNx = -1;
    private volatile int cursorNy = -1;
    private volatile boolean cursorVisible = true;
    private volatile CursorKind cursorKind = CursorKind.ARROW;

    private volatile Supplier<String> statsSupplier;
    private volatile boolean showStats;
    private volatile String statusMessage = "Connecting…";
    private volatile IntConsumer frameListener;
    private volatile Runnable screenshotHotkey;

    public RemoteScreenPanel() {
        setOpaque(true);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // Tab must reach the remote machine
        setBackground(BG);
        enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onMousePressed(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onMouseReleased(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                onMouseMoved(e, false);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                onMouseMoved(e, true);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                onWheel(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) releaseAllInput();
            }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (isDisplayable()) installKeyDispatcher();
                else uninstallKeyDispatcher();
            }
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) hookWindow();
        });

        animTimer = new Timer(16, ev -> {
            PenController p = pen;
            if (p != null) p.tick();
            AnnotationModel m = annotations;
            boolean animating = m != null && m.tick(System.currentTimeMillis());
            if (animating || (p != null && p.isDrawing())) {
                repaint();
            } else {
                ((Timer) ev.getSource()).stop();
                repaint();
            }
        });
    }

    // ------------------------------------------------------------------ configuration

    /** Where INPUT payloads go (typically {@code conn.send(MessageType.INPUT, …)}). */
    public void setInputSender(java.util.function.Consumer<byte[]> sender) {
        this.input = sender == null ? null : new InputBatcher(sender);
    }

    public void setKeyboardGrabber(KeyboardGrabber g) {
        KeyboardGrabber old = grabber;
        if (old != null) old.close();
        grabber = g;
        if (g != null) {
            g.setSink(ev -> {
                if (ev.kind == InputEvent.KEY && handleLocalHotkeyFromHook(ev)) return;
                InputBatcher b = input;
                if (b != null && mode == Mode.CONTROL) {
                    trackKey(ev);
                    b.send(ev);
                }
            });
        }
        updateGrabber();
    }

    public void setAnnotations(AnnotationModel model, PenController controller) {
        this.annotations = model;
        this.pen = controller;
        if (model != null) model.addListener(() -> SwingUtilities.invokeLater(this::onAnnotationsChanged));
    }

    public AnnotationModel annotations() {
        return annotations;
    }

    public PenController pen() {
        return pen;
    }

    public void setStatsSupplier(Supplier<String> s) {
        statsSupplier = s;
    }

    public void setShowStats(boolean show) {
        showStats = show;
        repaint();
    }

    public boolean isShowStats() {
        return showStats;
    }

    public void setStatusMessage(String msg) {
        statusMessage = msg;
        repaint();
    }

    /** Called with each completed frame id (on the decoder thread). */
    public void setFrameListener(IntConsumer l) {
        frameListener = l;
    }

    /** Alt+D (local screenshot) handler. */
    public void setScreenshotHotkey(Runnable r) {
        screenshotHotkey = r;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        if (m == mode) return;
        if (mode == Mode.CONTROL) releaseAllInput();
        mode = m;
        updateCursor();
        updateGrabber();
        repaint();
    }

    public ViewTransform.Mode viewMode() {
        return viewMode;
    }

    public void setViewMode(ViewTransform.Mode m) {
        viewMode = m;
        revalidate();
        synchronized (damageLock) {
            fullCopyNeeded = true;
        }
        repaint();
    }

    public FrameBuffer frameBuffer() {
        return fb;
    }

    /** Copy of the current picture (for screenshots), or null before the first frame. */
    public BufferedImage snapshot() {
        FrameBuffer f = fb;
        return f == null ? null : f.snapshot();
    }

    // ------------------------------------------------------------------ VideoDecoder.Sink

    @Override
    public void onFrameBuffer(FrameBuffer newFb) {
        fb = newFb;
        synchronized (damageLock) {
            fullCopyNeeded = true;
            pendingDamage = null;
        }
        statusMessage = null;
        SwingUtilities.invokeLater(() -> {
            revalidate();
            repaint();
        });
    }

    @Override
    public void onDamage(Rectangle damage) {
        synchronized (damageLock) {
            pendingDamage = pendingDamage == null ? new Rectangle(damage) : pendingDamage.union(damage);
        }
        if (statusMessage != null) statusMessage = null;
        Rectangle v = xf.toView(damage);
        repaint(v.x, v.y, v.width, v.height); // thread-safe; RepaintManager coalesces
    }

    @Override
    public void onFrameComplete(int frameId, long decodeNanos) {
        IntConsumer l = frameListener;
        if (l != null) l.accept(frameId);
    }

    /** Remote pointer position (normalized), from CURSOR_POS. */
    public void setRemoteCursor(int nx, int ny, boolean visible) {
        ViewTransform t = xf;
        int oldX = cursorNx, oldY = cursorNy;
        cursorNx = nx;
        cursorNy = ny;
        cursorVisible = visible;
        if (mode != Mode.CONTROL || !isPointerInside()) {
            repaintCursorAt(t, oldX, oldY);
            repaintCursorAt(t, nx, ny);
        }
    }

    public void setRemoteCursorKind(CursorKind k) {
        cursorKind = k;
        SwingUtilities.invokeLater(this::updateCursor);
    }

    private volatile boolean pointerInside;

    private boolean isPointerInside() {
        return pointerInside;
    }

    private void repaintCursorAt(ViewTransform t, int nx, int ny) {
        if (nx < 0) return;
        int x = (int) (t.offX + nx / 65535.0 * t.viewW());
        int y = (int) (t.offY + ny / 65535.0 * t.viewH());
        repaint(x - 4, y - 4, 24, 28);
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setColor(BG);
        Rectangle clip = g.getClipBounds();
        if (clip != null) g.fillRect(clip.x, clip.y, clip.width, clip.height);
        else g.fillRect(0, 0, getWidth(), getHeight());

        deviceScale = g.getTransform().getScaleX();
        FrameBuffer f = fb;
        if (f == null) {
            paintMessage(g, statusMessage != null ? statusMessage : "Waiting for video…");
            return;
        }
        ViewTransform t = ViewTransform.compute(viewMode, getWidth(), getHeight(), f.width, f.height, deviceScale);
        xf = t;

        GraphicsConfiguration gc = getGraphicsConfiguration();
        boolean drawn = false;
        if (gc != null) {
            for (int attempt = 0; attempt < 3 && !drawn; attempt++) {
                if (vimg == null || vimg.getWidth() != f.width || vimg.getHeight() != f.height) {
                    if (vimg != null) vimg.flush();
                    vimg = gc.createCompatibleVolatileImage(f.width, f.height);
                    synchronized (damageLock) {
                        fullCopyNeeded = true;
                    }
                }
                int valid = vimg.validate(gc);
                if (valid == VolatileImage.IMAGE_INCOMPATIBLE) {
                    vimg.flush();
                    vimg = null;
                    continue;
                }
                Rectangle dmg;
                boolean full;
                synchronized (damageLock) {
                    full = fullCopyNeeded || valid == VolatileImage.IMAGE_RESTORED;
                    dmg = pendingDamage;
                    pendingDamage = null;
                    fullCopyNeeded = false;
                }
                Graphics2D vg = vimg.createGraphics();
                try {
                    if (full) {
                        vg.drawImage(f.image(), 0, 0, null);
                    } else if (dmg != null) {
                        Rectangle r = dmg.intersection(new Rectangle(0, 0, f.width, f.height));
                        if (!r.isEmpty()) {
                            vg.drawImage(f.image(), r.x, r.y, r.x + r.width, r.y + r.height,
                                    r.x, r.y, r.x + r.width, r.y + r.height, null);
                        }
                    }
                } finally {
                    vg.dispose();
                }
                drawScaled(g, vimg, t);
                drawn = !vimg.contentsLost();
                if (!drawn) {
                    synchronized (damageLock) {
                        fullCopyNeeded = true;
                    }
                }
            }
        }
        if (!drawn) drawScaled(g, f.image(), t); // software fallback

        AnnotationModel m = annotations;
        if (m != null && !m.isEmpty()) {
            Graphics2D ag = (Graphics2D) g.create();
            ag.clipRect((int) t.offX, (int) t.offY, (int) Math.ceil(t.viewW()), (int) Math.ceil(t.viewH()));
            long now = System.currentTimeMillis();
            annotationRenderer.paint(ag, m.snapshot(), t.offX, t.offY, t.viewW(), t.viewH(), now);
            ag.dispose();
            if (m.tick(now) && !animTimer.isRunning()) animTimer.start();
        }

        paintRegionSelection(g);
        if ((mode != Mode.CONTROL || !pointerInside) && cursorVisible && cursorNx >= 0) paintRemoteCursor(g, t);
        if (showStats) paintStats(g);
        if (statusMessage != null) paintMessage(g, statusMessage);
        paintFlash(g);
    }

    // ------------------------------------------------------------------ text, area selection

    /** Asks for the text, then puts it on the screen where the user clicked. */
    private void promptText(PenController p, float fx, float fy, int imageW) {
        javax.swing.JTextArea ta = new javax.swing.JTextArea(4, 30);
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(ta);
        ta.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent ev) {
                ta.requestFocusInWindow();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent ev) {
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent ev) {
            }
        });
        int r = javax.swing.JOptionPane.showConfirmDialog(this, new Object[] {"Text to put on the screen (Enter = new line):", sp},
                "Add text", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (r == javax.swing.JOptionPane.OK_OPTION) {
            p.placeText(fx, fy, ta.getText(), imageW);
            if (!animTimer.isRunning()) animTimer.start();
        }
        requestFocusInWindow();
    }

    /** The picture as it is now, with the drawings, text and images on it (a copy, safe to use off the event thread). */
    public BufferedImage snapshotWithAnnotations() {
        FrameBuffer f = fb;
        if (f == null) return null;
        BufferedImage img = f.snapshot();
        AnnotationModel m = annotations;
        if (m != null && !m.isEmpty()) {
            Graphics2D g = img.createGraphics();
            new AnnotationRenderer().paint(g, m.snapshot(), 0, 0, img.getWidth(), img.getHeight(), System.currentTimeMillis());
            g.dispose();
        }
        return img;
    }

    /** Lets the user drag a rectangle over the screen; {@code callback} receives {x0, y0, x1, y1} as 0..1 fractions of the picture. */
    public void beginRegionSelect(java.util.function.Consumer<float[]> callback) {
        selStart = null;
        selEnd = null;
        regionCallback = callback;
        requestFocusInWindow();
        updateCursor();
        repaint();
    }

    public void cancelRegionSelect() {
        regionCallback = null;
        selStart = null;
        selEnd = null;
        updateCursor();
        repaint();
    }

    private void finishRegion(MouseEvent e) {
        java.util.function.Consumer<float[]> cb = regionCallback;
        java.awt.Point a = selStart;
        if (cb == null || a == null) return;
        ViewTransform t = xf;
        float x0 = t.fracX(a.x), y0 = t.fracY(a.y), x1 = t.fracX(e.getX()), y1 = t.fracY(e.getY());
        cancelRegionSelect();
        FrameBuffer f = fb;
        if (f == null || Math.abs(x1 - x0) * f.width < 4 || Math.abs(y1 - y0) * f.height < 4) {
            flashSaved("Selection too small");
            return;
        }
        cb.accept(new float[] {Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)});
    }

    private void paintRegionSelection(Graphics2D g0) {
        if (regionCallback == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.Point a = selStart, b = selEnd;
        if (a != null && b != null) {
            int x = Math.min(a.x, b.x), y = Math.min(a.y, b.y), w = Math.abs(a.x - b.x), h = Math.abs(a.y - b.y);
            java.awt.geom.Area dim = new java.awt.geom.Area(new Rectangle(0, 0, getWidth(), getHeight()));
            dim.subtract(new java.awt.geom.Area(new Rectangle(x, y, w, h)));
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(dim);
            g.setColor(Color.WHITE);
            g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f,
                    new float[] {6f, 4f}, 0f));
            g.drawRect(x, y, w, h);
        } else {
            g.setColor(new Color(0, 0, 0, 70));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        String hint = "Drag to select the area   \u00B7   Esc to cancel";
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(hint) + 30, bh = fm.getHeight() + 12;
        int bx = (getWidth() - bw) / 2, by = 36;
        g.setColor(new Color(0.11f, 0.11f, 0.13f, 0.92f));
        g.fillRoundRect(bx, by, bw, bh, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString(hint, bx + 15, by + 6 + fm.getAscent());
        g.dispose();
    }

    // ------------------------------------------------------------------ screenshot flash

    private volatile long flashStartMs;
    private volatile String flashText;
    private Timer flashTimer;

    /** Camera-like flash plus a short "saved" banner, confirming a screenshot was taken. */
    public void flashSaved(String text) {
        flashStartMs = System.currentTimeMillis();
        flashText = text;
        if (flashTimer == null) {
            flashTimer = new Timer(16, e -> {
                if (System.currentTimeMillis() - flashStartMs > 1600) ((Timer) e.getSource()).stop();
                repaint();
            });
        }
        flashTimer.restart();
        repaint();
    }

    private void paintFlash(Graphics2D g) {
        long start = flashStartMs;
        if (start == 0) return;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 1600) return;
        // 0-220 ms: white flash fading out.
        if (elapsed < 220) {
            float a = (float) (1 - elapsed / 220.0) * 0.7f;
            g.setColor(new Color(1f, 1f, 1f, Math.max(0f, a)));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        // 0-1600 ms: a "Saved" banner near the bottom, fading in the last 400 ms.
        String text = flashText;
        if (text != null) {
            float a = elapsed > 1200 ? (float) (1 - (elapsed - 1200) / 400.0) : 1f;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 40;
            int h = fm.getHeight() + 16;
            int x = (getWidth() - w) / 2;
            int y = getHeight() - h - 28;
            g.setColor(new Color(0.11f, 0.11f, 0.13f, Math.max(0f, 0.9f * a)));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(new Color(0.3f, 0.78f, 0.35f, Math.max(0f, a)));
            g.fillOval(x + 14, y + h / 2 - 5, 10, 10);
            g.setColor(new Color(1f, 1f, 1f, Math.max(0f, a)));
            g.drawString(text, x + 32, y + 8 + fm.getAscent());
        }
    }

    private void drawScaled(Graphics2D g, java.awt.Image img, ViewTransform t) {
        boolean unscaled = Math.abs(t.scale * deviceScale - 1) < 1e-3 && Math.abs(t.scaleY * deviceScale - 1) < 1e-3;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, unscaled
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, (int) Math.round(t.offX), (int) Math.round(t.offY),
                (int) Math.round(t.viewW()), (int) Math.round(t.viewH()), null);
    }

    private void paintRemoteCursor(Graphics2D g0, ViewTransform t) {
        double x = t.offX + cursorNx / 65535.0 * t.viewW();
        double y = t.offY + cursorNy / 65535.0 * t.viewH();
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17);
        arrow.lineTo(4.5, 13);
        arrow.lineTo(7.5, 19.5);
        arrow.lineTo(10, 18.5);
        arrow.lineTo(7, 12);
        arrow.lineTo(12.5, 12);
        arrow.closePath();
        g.setColor(Color.WHITE);
        g.fill(arrow);
        g.setColor(Color.BLACK);
        g.draw(arrow);
        g.dispose();
    }

    private void paintStats(Graphics2D g) {
        Supplier<String> s = statsSupplier;
        if (s == null) return;
        String[] lines = s.get().split("\n");
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        int h = lines.length * fm.getHeight() + 10;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(8, 8, w + 16, h, 10, 10);
        g.setColor(new Color(0x9E, 0xF0, 0x9E));
        int y = 8 + 5 + fm.getAscent();
        for (String l : lines) {
            g.drawString(l, 16, y);
            y += fm.getHeight();
        }
    }

    private void paintMessage(Graphics2D g, String msg) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(getFont() != null ? getFont().deriveFont(Font.PLAIN, 15f) : new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(msg) + 32;
        int h = fm.getHeight() + 16;
        int x = (getWidth() - w) / 2;
        int y = (getHeight() - h) / 2;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(new Color(0xEE, 0xEE, 0xEE));
        g.drawString(msg, x + 16, y + 8 + fm.getAscent());
    }

    private void onAnnotationsChanged() {
        repaint();
        if (!animTimer.isRunning()) animTimer.start();
    }

    // ------------------------------------------------------------------ mouse

    private static int buttonOf(MouseEvent e) {
        switch (e.getButton()) {
            case MouseEvent.BUTTON1:
                return InputEvent.BTN_LEFT;
            case MouseEvent.BUTTON2:
                return InputEvent.BTN_MIDDLE;
            case MouseEvent.BUTTON3:
                return InputEvent.BTN_RIGHT;
            case 4:
                return InputEvent.BTN_BACK;
            case 5:
                return InputEvent.BTN_FORWARD;
            default:
                return 0;
        }
    }

    private float aspect() {
        FrameBuffer f = fb;
        return f == null ? 1f : (float) f.height / f.width;
    }

    private void onMousePressed(MouseEvent e) {
        requestFocusInWindow();
        ViewTransform t = xf;
        if (regionCallback != null) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                selStart = e.getPoint();
                selEnd = selStart;
                repaint();
            }
            return;
        }
        if (mode == Mode.CONTROL) {
            InputBatcher b = input;
            int btn = buttonOf(e);
            if (b != null && btn != 0 && fb != null) {
                pressedButtons.add(btn);
                b.send(InputEvent.button(btn, true, t.normX(e.getX()), t.normY(e.getY())));
            }
        } else if (mode == Mode.PEN && SwingUtilities.isLeftMouseButton(e)) {
            PenController p = pen;
            FrameBuffer f = fb;
            if (p != null && f != null && t.contains(e.getX(), e.getY())) {
                if (p.tool() == PenTool.TEXT) {
                    promptText(p, t.fracX(e.getX()), t.fracY(e.getY()), f.width);
                    return;
                }
                p.press(t.fracX(e.getX()), t.fracY(e.getY()), f.width, aspect());
                if (!animTimer.isRunning()) animTimer.start();
            }
        }
    }

    private void onMouseReleased(MouseEvent e) {
        ViewTransform t = xf;
        if (regionCallback != null) {
            finishRegion(e);
            return;
        }
        if (mode == Mode.CONTROL) {
            InputBatcher b = input;
            int btn = buttonOf(e);
            if (b != null && btn != 0 && pressedButtons.remove(btn)) {
                b.send(InputEvent.button(btn, false, t.normX(e.getX()), t.normY(e.getY())));
            }
        } else if (mode == Mode.PEN && SwingUtilities.isLeftMouseButton(e)) {
            PenController p = pen;
            if (p != null && p.isDrawing()) p.release(t.fracX(e.getX()), t.fracY(e.getY()), aspect());
        }
    }

    private void onMouseMoved(MouseEvent e, boolean dragged) {
        if (regionCallback != null) {
            if (dragged && selStart != null) {
                selEnd = e.getPoint();
                repaint();
            }
            return;
        }
        ViewTransform t = xf;
        boolean inside = t.contains(e.getX(), e.getY());
        if (inside != pointerInside) {
            pointerInside = inside;
            updateCursor();
            repaintCursorAt(t, cursorNx, cursorNy);
        }
        if (mode == Mode.CONTROL) {
            InputBatcher b = input;
            if (b != null && fb != null && (inside || dragged)) b.move(t.normX(e.getX()), t.normY(e.getY()));
        } else if (mode == Mode.PEN && dragged) {
            PenController p = pen;
            if (p != null && p.isDrawing()) p.drag(t.fracX(e.getX()), t.fracY(e.getY()), aspect());
        }
    }

    private void onWheel(MouseWheelEvent e) {
        if (mode != Mode.CONTROL) return;
        InputBatcher b = input;
        if (b == null) return;
        // Trackpads (notably macOS) report horizontal scrolling as Shift+wheel with no Shift key
        // physically held. A real held Shift is forwarded as a key, so the host app sees a
        // genuine Shift+wheel.
        boolean horizontal = e.isShiftDown() && !shiftKeyDown;
        InputEvent ev = wheel.onWheel(e.getPreciseWheelRotation(), horizontal);
        if (ev != null) b.send(ev);
        e.consume();
    }

    private void updateCursor() {
        if (regionCallback != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        } else if (mode == Mode.PEN) {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        } else if (mode == Mode.CONTROL && pointerInside) {
            CursorKind k = cursorKind;
            setCursor(k == CursorKind.HIDDEN ? blankCursor() : Cursor.getPredefinedCursor(k.awtType));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    // ------------------------------------------------------------------ keyboard

    private Window hookedWindow;
    private final WindowAdapter windowFocus = new WindowAdapter() {
        @Override
        public void windowActivated(WindowEvent e) {
            updateGrabber();
        }

        @Override
        public void windowDeactivated(WindowEvent e) {
            releaseAllInput();
            updateGrabber();
        }
    };

    private void hookWindow() {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w == hookedWindow) return;
        if (hookedWindow != null) hookedWindow.removeWindowListener(windowFocus);
        hookedWindow = w;
        if (w != null) w.addWindowListener(windowFocus);
    }

    private void updateGrabber() {
        KeyboardGrabber g = grabber;
        if (g == null) return;
        Window w = SwingUtilities.getWindowAncestor(this);
        g.setActive(mode == Mode.CONTROL && w != null && w.isActive());
    }

    private void installKeyDispatcher() {
        if (keyDispatcher != null) return;
        keyDispatcher = this::dispatchKey;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher);
        hookWindow();
    }

    private void uninstallKeyDispatcher() {
        if (keyDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher);
            keyDispatcher = null;
        }
        KeyboardGrabber g = grabber;
        if (g != null) g.setActive(false);
    }

    private boolean dispatchKey(KeyEvent e) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w == null || !w.isActive() || e.getComponent() == null
                || SwingUtilities.getWindowAncestor(e.getComponent()) != w && e.getComponent() != w) {
            return false;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_D
                && (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0
                && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) == 0) {
            Runnable r = screenshotHotkey;
            if (r != null) {
                r.run();
                return true;
            }
        }
        if (regionCallback != null && e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancelRegionSelect();
            return true;
        }
        if (mode == Mode.PEN && e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_Z
                && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {
            PenController p = pen;
            if (p != null) p.undo();
            return true;
        }
        if (mode != Mode.CONTROL) return false;
        KeyboardGrabber g = grabber;
        if (g != null && g.isActive()) return true; // the hook already forwarded (and swallowed) it
        InputBatcher b = input;
        if (b == null) return false;
        if (e.getID() == KeyEvent.KEY_TYPED) return true;
        boolean down = e.getID() == KeyEvent.KEY_PRESSED;
        int vk = e.getKeyCode();
        if (vk == KeyEvent.VK_UNDEFINED) return true;
        int packed = KeyMap.toScancode(vk, e.getKeyLocation());
        InputEvent ev = InputEvent.key(down, packed & 0xFF, (packed & 0x100) != 0, vk, e.getKeyLocation());
        if (!down && !pressedKeys.contains(keyId(ev))) return true; // release of a key pressed before control began
        trackKey(ev);
        b.send(ev);
        return true;
    }

    private static int keyId(InputEvent ev) {
        return ev.scancode != 0 ? (ev.scancode | (ev.extended ? 0x100 : 0)) : (0x10000 | ev.vk);
    }

    private void trackKey(InputEvent ev) {
        synchronized (pressedKeys) {
            if (ev.down) pressedKeys.add(keyId(ev));
            else pressedKeys.remove(keyId(ev));
            if (ev.vk == KeyEvent.VK_SHIFT || ev.scancode == 0x2A || ev.scancode == 0x36) shiftKeyDown = ev.down;
        }
    }

    /** Alt+D via the low-level hook (Alt tracked from hook events). */
    private boolean altDownViaHook;

    private boolean handleLocalHotkeyFromHook(InputEvent ev) {
        if (ev.scancode == 0x38) altDownViaHook = ev.down;
        if (ev.down && altDownViaHook && ev.scancode == 0x20) { // 'D'
            Runnable r = screenshotHotkey;
            if (r != null) {
                SwingUtilities.invokeLater(r);
                return true;
            }
        }
        return false;
    }

    /** Releases every key/button we pressed on the host (focus loss, mode change, close). */
    public void releaseAllInput() {
        InputBatcher b = input;
        wheel.reset();
        if (b == null) return;
        synchronized (pressedKeys) {
            if (pressedKeys.isEmpty() && pressedButtons.isEmpty()) return;
            pressedKeys.clear();
            pressedButtons.clear();
            shiftKeyDown = false;
        }
        b.send(InputEvent.releaseAll());
    }

    // ------------------------------------------------------------------ Scrollable (ACTUAL mode scrolls)

    @Override
    public Dimension getPreferredSize() {
        FrameBuffer f = fb;
        if (f == null) return new Dimension(960, 540);
        if (viewMode == ViewTransform.Mode.ACTUAL) {
            double s = 1.0 / Math.max(0.5, deviceScale);
            return new Dimension((int) Math.ceil(f.width * s), (int) Math.ceil(f.height * s));
        }
        return new Dimension(Math.min(1280, f.width), Math.min(800, f.height));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 32;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - 32 : visibleRect.width - 32;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return viewMode != ViewTransform.Mode.ACTUAL || (getParent() != null && getParent().getWidth() > getPreferredSize().width);
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return viewMode != ViewTransform.Mode.ACTUAL || (getParent() != null && getParent().getHeight() > getPreferredSize().height);
    }

    public void dispose() {
        releaseAllInput();
        animTimer.stop();
        uninstallKeyDispatcher();
        KeyboardGrabber g = grabber;
        if (g != null) g.close();
        grabber = null;
        if (hookedWindow != null) hookedWindow.removeWindowListener(windowFocus);
        if (vimg != null) vimg.flush();
        vimg = null;
    }
}
