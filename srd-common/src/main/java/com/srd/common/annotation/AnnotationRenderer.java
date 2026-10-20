package com.srd.common.annotation;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/** Draws strokes into any Graphics2D, mapping normalized points to a target rectangle. */
public final class AnnotationRenderer {
    private float[] xs = new float[256];
    private float[] ys = new float[256];
    private long[] ts = new long[256];

    /**
     * @param ox,oy,w,h where the shared image appears on this surface
     */
    public void paint(Graphics2D g0, List<Stroke> strokes, double ox, double oy, double w, double h, long now) {
        if (strokes.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (Stroke s : strokes) paintStroke(g, s, ox, oy, w, h, now);
        } finally {
            g.dispose();
        }
    }

    private void ensure(int n) {
        if (xs.length < n) {
            xs = new float[n * 2];
            ys = new float[n * 2];
            ts = new long[n * 2];
        }
    }

    private void paintStroke(Graphics2D g, Stroke s, double ox, double oy, double w, double h, long now) {
        int size = s.size();
        if (size == 0) return;
        ensure(size);
        int n = s.copy(xs, ys, ts);
        float px = (float) Math.max(1.0, s.width * w);
        float alpha = ((s.argb >>> 24) & 0xFF) / 255f;
        if (s.fadeSeconds > 0 && s.isEnded()) {
            long left = s.fadeSeconds * 1000L - (now - s.endedAtMs());
            if (left < 1000) alpha *= Math.max(0f, left / 1000f);
        }
        Color base = new Color(s.argb & 0xFFFFFF);
        Composite oldComp = g.getComposite();
        switch (s.tool) {
            case HIGHLIGHTER: {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f * alpha));
                g.setColor(base);
                g.setStroke(new BasicStroke(px * 3f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
                g.draw(polyline(n, ox, oy, w, h));
                break;
            }
            case LINE:
            case ARROW:
            case RECTANGLE:
            case ELLIPSE: {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setColor(base);
                g.setStroke(new BasicStroke(px, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double x0 = ox + xs[0] * w, y0 = oy + ys[0] * h;
                double x1 = ox + xs[n - 1] * w, y1 = oy + ys[n - 1] * h;
                if (s.tool == PenTool.RECTANGLE) {
                    g.draw(new Rectangle2D.Double(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0)));
                } else if (s.tool == PenTool.ELLIPSE) {
                    g.draw(new Ellipse2D.Double(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0)));
                } else {
                    g.draw(new Line2D.Double(x0, y0, x1, y1));
                    if (s.tool == PenTool.ARROW) drawArrowHead(g, x0, y0, x1, y1, px);
                }
                break;
            }
            case LASER: {
                // Fading trail of recent points plus a bright dot at the tip.
                long cutoff = now - AnnotationModel.LASER_TRAIL_MS;
                float r = Math.max(5f, px * 2.2f);
                Color c = base;
                for (int i = 1; i < n; i++) {
                    if (ts[i] < cutoff) continue;
                    float age = (float) (now - ts[i]) / AnnotationModel.LASER_TRAIL_MS;
                    float a = Math.max(0f, 1f - age) * 0.85f;
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                    g.setColor(c);
                    g.setStroke(new BasicStroke(r * (1f - age * 0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(ox + xs[i - 1] * w, oy + ys[i - 1] * h, ox + xs[i] * w, oy + ys[i] * h));
                }
                if (!s.isEnded() || now - s.endedAtMs() < AnnotationModel.LASER_TRAIL_MS) {
                    double tx = ox + xs[n - 1] * w, ty = oy + ys[n - 1] * h;
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                    g.fill(new Ellipse2D.Double(tx - r * 1.6, ty - r * 1.6, r * 3.2, r * 3.2));
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                    g.fill(new Ellipse2D.Double(tx - r / 2, ty - r / 2, r, r));
                }
                break;
            }
            case ERASER:
                break;
            case TEXT: {
                if (s.text.isEmpty()) break;
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setColor(base);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                float fontPx = (float) Math.max(6.0, s.width * w);
                java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12).deriveFont(fontPx);
                g.setFont(font);
                java.awt.FontMetrics fm = g.getFontMetrics();
                double tx = ox + xs[0] * w, ty = oy + ys[0] * h + fm.getAscent();
                for (String line : s.text.split("\n", -1)) {
                    g.drawString(line, (float) tx, (float) ty);
                    ty += fm.getHeight();
                }
                break;
            }
            case IMAGE: {
                java.awt.image.BufferedImage img = s.image();
                if (img == null || n < 2) break;
                double x0 = ox + xs[0] * w, y0 = oy + ys[0] * h;
                double x1 = ox + xs[n - 1] * w, y1 = oy + ys[n - 1] * h;
                int dx = (int) Math.round(Math.min(x0, x1)), dy = (int) Math.round(Math.min(y0, y1));
                int dw = (int) Math.round(Math.abs(x1 - x0)), dh = (int) Math.round(Math.abs(y1 - y0));
                if (dw < 2 || dh < 2) break;
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, dx, dy, dw, dh, null);
                break;
            }
            default: {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setColor(base);
                g.setStroke(new BasicStroke(px, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (n == 1) {
                    double cx = ox + xs[0] * w, cy = oy + ys[0] * h;
                    g.fill(new Ellipse2D.Double(cx - px / 2, cy - px / 2, px, px));
                } else {
                    g.draw(polyline(n, ox, oy, w, h));
                }
            }
        }
        g.setComposite(oldComp);
    }

    /** Smooth path through the points (quadratic midpoints) so fast pen motion isn't jagged. */
    private Path2D polyline(int n, double ox, double oy, double w, double h) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(ox + xs[0] * w, oy + ys[0] * h);
        if (n == 2) {
            p.lineTo(ox + xs[1] * w, oy + ys[1] * h);
            return p;
        }
        for (int i = 1; i < n - 1; i++) {
            double cx = ox + xs[i] * w, cy = oy + ys[i] * h;
            double mx = ox + (xs[i] + xs[i + 1]) / 2 * w, my = oy + (ys[i] + ys[i + 1]) / 2 * h;
            p.quadTo(cx, cy, mx, my);
        }
        p.lineTo(ox + xs[n - 1] * w, oy + ys[n - 1] * h);
        return p;
    }

    private static void drawArrowHead(Graphics2D g, double x0, double y0, double x1, double y1, float px) {
        double ang = Math.atan2(y1 - y0, x1 - x0);
        double len = Math.max(12, px * 4.5);
        Path2D.Double head = new Path2D.Double();
        head.moveTo(x1, y1);
        head.lineTo(x1 - len * Math.cos(ang - Math.PI / 7), y1 - len * Math.sin(ang - Math.PI / 7));
        head.lineTo(x1 - len * Math.cos(ang + Math.PI / 7), y1 - len * Math.sin(ang + Math.PI / 7));
        head.closePath();
        g.fill(head);
    }
}
