package com.srd.common.annotation;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Viewer-side pen logic. Strokes are drawn into the local {@link AnnotationModel} immediately
 * (zero perceived latency) and streamed to the host as BEGIN / batched POINTS / END. Shapes and
 * the eraser work in the same normalized space, so they line up at any window size.
 */
public final class PenController {
    private static final long POINT_BATCH_MS = 8;

    private final AnnotationModel model;
    private final Consumer<byte[]> sender;
    private final int authorId;
    private final String authorName;

    private volatile PenTool tool = PenTool.PEN;
    private volatile int argb = 0xFFE53935;
    /** Width in pixels of the remote image. */
    private volatile float widthPx = 4f;
    private volatile int fadeSeconds;

    /** Picture waiting to be placed by the next drag (IMAGE tool), and its width/height ratio. */
    private volatile byte[] pendingImage;
    private volatile float pendingAspect = 1f;
    private volatile Runnable toolListener;

    private int seq;
    private Stroke current;
    private int currentSeq;
    private float[] pending = new float[64];
    private int pendingN;
    private long lastSentMs;

    public PenController(AnnotationModel model, String authorName, Consumer<byte[]> sender) {
        this.model = model;
        this.sender = sender;
        this.authorName = authorName;
        this.authorId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    }

    public PenTool tool() {
        return tool;
    }

    public void setTool(PenTool t) {
        tool = t;
        if (t != PenTool.IMAGE) pendingImage = null;
    }

    /** Called (on any thread) when the controller changes the tool itself, e.g. back to the pen after an image was placed. */
    public void setToolListener(Runnable r) {
        toolListener = r;
    }

    /** Arms the IMAGE tool: the next drag (or click) on the screen places this picture. */
    public void setPendingImage(byte[] data, int width, int height) {
        pendingImage = data;
        pendingAspect = height <= 0 ? 1f : (float) width / height;
        tool = PenTool.IMAGE;
        Runnable r = toolListener;
        if (r != null) r.run();
    }

    /** Text height in pixels of the remote image (follows the size slider). */
    public float textSizePx() {
        return Math.max(12f, widthPx * 5f);
    }

    /** Puts {@code text} on the shared screen with its top-left corner at the given normalized position. */
    public synchronized void placeText(float fx, float fy, String text, int imageW) {
        if (text == null || text.trim().isEmpty()) return;
        int id = ++seq;
        long now = System.currentTimeMillis();
        Stroke s = new Stroke(Stroke.id(authorId, id), authorId, authorName, PenTool.TEXT, argb,
                textSizePx() / Math.max(1, imageW), fadeSeconds, text, null);
        s.add(fx, fy, now);
        s.end(now);
        model.addLocal(s);
        sender.accept(PenMessage.begin(s, id, new float[] {fx, fy}));
        sender.accept(PenMessage.end(authorId, id));
    }

    public Color color() {
        return new Color(argb, true);
    }

    public void setColor(Color c) {
        argb = c.getRGB() | 0xFF000000;
    }

    public float widthPx() {
        return widthPx;
    }

    public void setWidthPx(float w) {
        widthPx = Math.max(1f, Math.min(64f, w));
    }

    public int fadeSeconds() {
        return fadeSeconds;
    }

    public void setFadeSeconds(int s) {
        fadeSeconds = Math.max(0, Math.min(255, s));
    }

    public int authorId() {
        return authorId;
    }

    /**
     * @param fx,fy  normalized position
     * @param imageW remote image width in pixels (to normalize the line width)
     * @param aspect remote image height / width (for the eraser's distance test)
     */
    public synchronized void press(float fx, float fy, int imageW, float aspect) {
        if (tool == PenTool.ERASER) {
            erase(fx, fy, aspect);
            return;
        }
        if (tool == PenTool.TEXT) return; // the panel asks for the text and calls placeText
        byte[] image = tool == PenTool.IMAGE ? pendingImage : null;
        if (tool == PenTool.IMAGE && image == null) return;
        currentSeq = ++seq;
        float wNorm = widthPx / Math.max(1, imageW);
        Stroke s = new Stroke(Stroke.id(authorId, currentSeq), authorId, authorName, tool, argb, wNorm,
                tool == PenTool.LASER ? 0 : fadeSeconds, "", image);
        s.add(fx, fy, System.currentTimeMillis());
        current = s;
        model.addLocal(s);
        sender.accept(PenMessage.begin(s, currentSeq, new float[] {fx, fy}));
        pendingN = 0;
        lastSentMs = System.currentTimeMillis();
    }

    public synchronized void drag(float fx, float fy, float aspect) {
        if (tool == PenTool.ERASER) {
            erase(fx, fy, aspect);
            return;
        }
        Stroke s = current;
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (s.tool == PenTool.IMAGE) {
            // Keep the picture's proportions: the height follows the dragged width.
            float[] p0 = s.pointsXY();
            float dy = Math.abs(fx - p0[0]) / (pendingAspect * Math.max(0.01f, aspect));
            fy = p0[1] + (fy >= p0[1] ? dy : -dy);
        }
        s.add(fx, fy, now);
        model.touch();
        if (s.tool.shape) pendingN = 0; // shapes only need the latest endpoint
        if (pendingN + 2 > pending.length) pending = java.util.Arrays.copyOf(pending, pending.length * 2);
        pending[pendingN++] = fx;
        pending[pendingN++] = fy;
        if (now - lastSentMs >= POINT_BATCH_MS) flushPoints(now);
    }

    public synchronized void release(float fx, float fy, float aspect) {
        if (tool == PenTool.ERASER) return;
        Stroke s = current;
        if (s == null) return;
        if (s.tool == PenTool.IMAGE) {
            float x0 = s.pointsXY()[0];
            if (Math.abs(fx - x0) < 0.01f) fx = x0 + (x0 > 0.7f ? -0.3f : 0.3f); // a plain click: default size
        }
        drag(fx, fy, aspect);
        flushPoints(System.currentTimeMillis());
        s.end(System.currentTimeMillis());
        sender.accept(PenMessage.end(authorId, currentSeq));
        current = null;
        model.touch();
        if (s.tool == PenTool.IMAGE) {
            pendingImage = null;
            tool = PenTool.PEN;
            Runnable r = toolListener;
            if (r != null) r.run();
        }
    }

    /** Flushes buffered points; call from a ~15 ms timer while drawing. */
    public synchronized void tick() {
        if (current != null && pendingN > 0) flushPoints(System.currentTimeMillis());
    }

    public synchronized boolean isDrawing() {
        return current != null;
    }

    private void flushPoints(long now) {
        if (pendingN == 0) return;
        float[] pts = java.util.Arrays.copyOf(pending, pendingN);
        pendingN = 0;
        lastSentMs = now;
        sender.accept(PenMessage.points(authorId, currentSeq, pts));
    }

    private void erase(float fx, float fy, float aspect) {
        float tol = 0.008f;
        List<Stroke> hits = model.hit(fx, fy, tol, aspect);
        for (Stroke s : hits) {
            byte[] msg = PenMessage.remove(s.authorId, (int) s.id);
            model.apply(PenMessage.decode(msg));
            sender.accept(msg);
        }
    }

    /** Removes this viewer's most recent stroke. */
    public synchronized void undo() {
        Stroke s = model.lastBy(authorId);
        if (s == null) return;
        byte[] msg = PenMessage.remove(authorId, (int) s.id);
        model.apply(PenMessage.decode(msg));
        sender.accept(msg);
    }

    /** Removes every stroke from everyone. */
    public synchronized void clearAll() {
        byte[] msg = PenMessage.clear(0);
        model.apply(PenMessage.decode(msg));
        sender.accept(msg);
    }
}
