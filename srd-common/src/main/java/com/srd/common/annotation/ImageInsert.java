package com.srd.common.annotation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Turns a picture from a file or the clipboard into the compact bytes an IMAGE stroke carries:
 * longest side at most {@value #MAX_SIDE} px, PNG when it fits (keeps transparency and sharp UI
 * graphics), JPEG when PNG would be too big to send over the pen channel.
 */
public final class ImageInsert {
    public static final int MAX_SIDE = 1280;
    private static final int MAX_BYTES = 1_500_000;

    public static final class Prepared {
        public final byte[] data;
        public final int width;
        public final int height;

        Prepared(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private ImageInsert() {
    }

    public static Prepared fromFile(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("Not a picture file: " + f.getName());
        return prepare(img);
    }

    /** The clipboard's picture, or null if it holds none. */
    public static Prepared fromClipboard() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.imageFlavor)) return null;
            java.awt.Image im = (java.awt.Image) t.getTransferData(DataFlavor.imageFlavor);
            int w = im.getWidth(null), h = im.getHeight(null);
            if (w <= 0 || h <= 0) return null;
            BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.drawImage(im, 0, 0, null);
            g.dispose();
            return prepare(b);
        } catch (Exception e) {
            return null;
        }
    }

    static Prepared prepare(BufferedImage src) throws IOException {
        BufferedImage img = src;
        int side = Math.max(img.getWidth(), img.getHeight());
        if (side > MAX_SIDE) {
            double s = (double) MAX_SIDE / side;
            img = scale(img, Math.max(1, (int) Math.round(img.getWidth() * s)), Math.max(1, (int) Math.round(img.getHeight() * s)));
        }
        byte[] png = write(img, "png");
        if (png.length <= MAX_BYTES) return new Prepared(png, img.getWidth(), img.getHeight());
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return new Prepared(write(rgb, "jpg"), rgb.getWidth(), rgb.getHeight());
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = step(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return step(cur, w, h);
    }

    private static BufferedImage step(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] write(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(img, format, out)) throw new IOException("No " + format + " writer");
        return out.toByteArray();
    }
}
