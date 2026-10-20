package com.srd.common.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Cached icon loading: first {@code ./icons/<name>} next to the executable (lets deployments
 * re-skin), then the bundled classpath copy. Renderers used to construct and rescale a new
 * ImageIcon on every paint.
 */
public final class Icons {
    private static final Map<String, ImageIcon> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> IMAGES = new ConcurrentHashMap<>();

    private Icons() {
    }

    public static BufferedImage image(String name) {
        BufferedImage cached = IMAGES.get(name);
        if (cached != null) return cached;
        BufferedImage img = null;
        try {
            File f = new File("icons", name);
            if (f.isFile()) img = ImageIO.read(f);
            if (img == null) {
                try (InputStream in = Icons.class.getResourceAsStream("/icons/" + name)) {
                    if (in != null) img = ImageIO.read(in);
                }
            }
        } catch (Exception ignored) {
        }
        if (img != null) IMAGES.put(name, img);
        return img;
    }

    /** Icon scaled to {@code size}×{@code size} (aspect kept), or null if missing. */
    public static ImageIcon icon(String name, int size) {
        String key = name + "@" + size;
        ImageIcon cached = CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage img = image(name);
        if (img == null) return null;
        double s = (double) size / Math.max(img.getWidth(), img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * s));
        int h = Math.max(1, (int) Math.round(img.getHeight() * s));
        ImageIcon ic = new ImageIcon(scaled(img, w, h));
        CACHE.put(key, ic);
        return ic;
    }

    /**
     * Resizes in halving steps (area-like averaging, crisp small icons) into a ready BufferedImage.
     * Unlike {@code getScaledInstance} + MediaTracker this is synchronous and takes about a
     * millisecond, so the first paint of a table full of icons never stalls the event thread.
     */
    static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resize(cur, w, h);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
