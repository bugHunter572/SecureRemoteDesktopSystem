package com.srd.common.annotation;

import java.util.Arrays;

/**
 * One annotation stroke. Points are normalized (0..1) to the shared screen image, so a stroke
 * drawn in a 900-px-wide viewer window lands on exactly the same spot of the host's 4K desktop.
 */
public final class Stroke {
    public final long id;
    public final int authorId;
    public final String authorName;
    public final PenTool tool;
    public final int argb;
    /** Line width as a fraction of the image width. */
    public final float width;
    /** Auto-remove this many seconds after the stroke ends (0 = keep). */
    public final int fadeSeconds;

    private float[] xs = new float[32];
    private float[] ys = new float[32];
    private long[] ts = new long[32];
    private int n;
    private long endedAtMs;

    /** Text of a {@link PenTool#TEXT} stroke ("" otherwise); {@link #width} is then the font height as a fraction of the image width. */
    public final String text;
    /** Encoded picture (PNG/JPEG) of a {@link PenTool#IMAGE} stroke, or null. */
    private final byte[] imageData;
    private volatile java.awt.image.BufferedImage image;
    private volatile boolean imageFailed;

    public Stroke(long id, int authorId, String authorName, PenTool tool, int argb, float width, int fadeSeconds) {
        this(id, authorId, authorName, tool, argb, width, fadeSeconds, "", null);
    }

    public Stroke(long id, int authorId, String authorName, PenTool tool, int argb, float width, int fadeSeconds,
                  String text, byte[] imageData) {
        this.text = text == null ? "" : text;
        this.imageData = imageData == null || imageData.length == 0 ? null : imageData;
        this.id = id;
        this.authorId = authorId;
        this.authorName = authorName == null ? "" : authorName;
        this.tool = tool;
        this.argb = argb;
        this.width = width;
        this.fadeSeconds = fadeSeconds;
    }

    public byte[] imageData() {
        return imageData;
    }

    /** The decoded picture of an IMAGE stroke (decoded once, on first use), or null. */
    public java.awt.image.BufferedImage image() {
        java.awt.image.BufferedImage img = image;
        if (img != null || imageData == null || imageFailed) return img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageData));
        } catch (Exception e) {
            img = null;
        }
        if (img == null) imageFailed = true;
        image = img;
        return img;
    }

    /** Longest line length and line count of a TEXT stroke. */
    private int[] textShape() {
        int lines = 1, longest = 0, cur = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
                cur = 0;
            } else {
                longest = Math.max(longest, ++cur);
            }
        }
        return new int[] {Math.max(1, longest), lines};
    }

    public static long id(int authorId, int seq) {
        return ((long) authorId << 32) | (seq & 0xFFFFFFFFL);
    }

    public synchronized void add(float x, float y, long nowMs) {
        x = Math.max(0f, Math.min(1f, x));
        y = Math.max(0f, Math.min(1f, y));
        if (tool.shape && n >= 2) {
            xs[1] = x;
            ys[1] = y;
            ts[1] = nowMs;
            return;
        }
        if (n > 0 && xs[n - 1] == x && ys[n - 1] == y) return;
        if (n == xs.length) {
            xs = Arrays.copyOf(xs, n * 2);
            ys = Arrays.copyOf(ys, n * 2);
            ts = Arrays.copyOf(ts, n * 2);
        }
        xs[n] = x;
        ys[n] = y;
        ts[n] = nowMs;
        n++;
    }

    public synchronized void end(long nowMs) {
        if (endedAtMs == 0) endedAtMs = nowMs;
    }

    public synchronized boolean isEnded() {
        return endedAtMs != 0;
    }

    public synchronized long endedAtMs() {
        return endedAtMs;
    }

    public synchronized int size() {
        return n;
    }

    /** Copies points into the given arrays (sized ≥ size()) and returns the count. */
    public synchronized int copy(float[] outX, float[] outY, long[] outT) {
        System.arraycopy(xs, 0, outX, 0, n);
        System.arraycopy(ys, 0, outY, 0, n);
        if (outT != null) System.arraycopy(ts, 0, outT, 0, n);
        return n;
    }

    public synchronized float[] pointsXY() {
        float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) {
            p[2 * i] = xs[i];
            p[2 * i + 1] = ys[i];
        }
        return p;
    }

    /** Distance check used by the eraser (normalized units, aspect-corrected by {@code aspect}=h/w). */
    public synchronized boolean near(float x, float y, float tol, float aspect) {
        if (n == 0) return false;
        if (tool == PenTool.TEXT) {
            int[] shape = textShape();
            float w = shape[0] * 0.56f * width, h = shape[1] * 1.25f * width / Math.max(0.01f, aspect);
            return x >= xs[0] - tol && x <= xs[0] + w + tol && y >= ys[0] - tol && y <= ys[0] + h + tol;
        }
        if (tool == PenTool.IMAGE && n >= 2) {
            float x0 = Math.min(xs[0], xs[n - 1]), x1 = Math.max(xs[0], xs[n - 1]);
            float y0 = Math.min(ys[0], ys[n - 1]), y1 = Math.max(ys[0], ys[n - 1]);
            return x >= x0 - tol && x <= x1 + tol && y >= y0 - tol && y <= y1 + tol;
        }
        if (tool.shape && n >= 2) {
            float x0 = xs[0], y0 = ys[0], x1 = xs[n - 1], y1 = ys[n - 1];
            switch (tool) {
                case RECTANGLE: {
                    float minX = Math.min(x0, x1), maxX = Math.max(x0, x1), minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
                    return segDist(x, y, minX, minY, maxX, minY, aspect) < tol || segDist(x, y, maxX, minY, maxX, maxY, aspect) < tol
                            || segDist(x, y, maxX, maxY, minX, maxY, aspect) < tol || segDist(x, y, minX, maxY, minX, minY, aspect) < tol;
                }
                case ELLIPSE: {
                    float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                    float rx = Math.abs(x1 - x0) / 2, ry = Math.abs(y1 - y0) / 2;
                    if (rx < 1e-4f || ry < 1e-4f) return Math.hypot(x - cx, (y - cy) * aspect) < tol;
                    double d = Math.hypot((x - cx) / rx, (y - cy) / ry);
                    return Math.abs(d - 1) * Math.min(rx, ry * aspect) < tol;
                }
                default:
                    return segDist(x, y, x0, y0, x1, y1, aspect) < tol;
            }
        }
        if (n == 1) return Math.hypot(x - xs[0], (y - ys[0]) * aspect) < tol;
        for (int i = 1; i < n; i++) {
            if (segDist(x, y, xs[i - 1], ys[i - 1], xs[i], ys[i], aspect) < tol) return true;
        }
        return false;
    }

    private static double segDist(float px, float py, float ax, float ay, float bx, float by, float aspect) {
        double ay2 = ay * aspect, by2 = by * aspect, py2 = py * aspect;
        double dx = bx - ax, dy = by2 - ay2;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py2 - ay2) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py2 - (ay2 + t * dy));
    }
}
