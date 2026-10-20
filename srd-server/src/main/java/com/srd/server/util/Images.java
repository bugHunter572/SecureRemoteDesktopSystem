package com.srd.server.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/** Image helpers shared by thumbnails, idle screenshots and the recorder. */
public final class Images {
    /**
     * Upper bound for the width kept per client for its thumbnail tile (a few large tiles, ~4 MB
     * each). The width actually kept follows the on-screen tile size ({@link #tileWidthFor}), so a
     * wall of 150 small tiles needs ~30 MB instead of ~550 MB.
     */
    public static volatile int thumbMaxWidth = 1600; // Edit > Server Configuration > Thumbnail Size
    public static final int THUMB_MIN_WIDTH = 320;

    /** Stored width for a tile that is {@code tileDevicePx} wide on screen: 50 % headroom, clamped. */
    public static int tileWidthFor(int tileDevicePx) {
        return Math.max(THUMB_MIN_WIDTH, Math.min(thumbMaxWidth, (int) Math.round(tileDevicePx * 1.5)));
    }

    private Images() {
    }

    /** True if a sample of the picture is pure black (failed capture, locked desktop, display off). */
    public static boolean isBlank(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int stepX = Math.max(1, w / 64), stepY = Math.max(1, h / 64);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    /** Scales down (aspect kept) so the width is at most {@code maxW}; returns {@code src} if already small. */
    public static BufferedImage boundWidth(BufferedImage src, int maxW) {
        if (src == null || src.getWidth() <= maxW) return src;
        int w = maxW;
        int h = Math.max(1, (int) ((long) src.getHeight() * maxW / src.getWidth()));
        return scaleHQ(src, w, h);
    }

    /**
     * High-quality resize. A one-step bilinear shrink by 3-4x skips most source pixels (aliased,
     * jagged text), so shrinking halves the image step by step (each step averages 2x2 pixels)
     * and finishes with one bilinear step. Enlarging is a single bicubic step.
     */
    public static BufferedImage scaleHQ(BufferedImage src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (w >= src.getWidth() || h >= src.getHeight()) return scale(src, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = scale(cur, cw, ch, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return cw == w && ch == h ? cur : scale(cur, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public static void writeJpeg(BufferedImage img, File file, float quality) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        if (!it.hasNext()) throw new IOException("No JPEG ImageWriter available");
        ImageWriter writer = it.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (FileImageOutputStream output = new FileImageOutputStream(file)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(img, null, null), param);
            }
        } finally {
            writer.dispose();
        }
    }

    /** Deletes the .jpg files directly inside a directory (Clear Screenshots / after encoding). */
    public static void deleteJpgFiles(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.isDirectory()) return;
        File[] jpgFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
        if (jpgFiles == null) return;
        for (File file : jpgFiles) {
            if (!file.delete()) System.err.println("Failed to delete: " + file.getAbsolutePath());
        }
    }
}
