package com.srd.client.capture;

import com.srd.common.util.Os;
import com.srd.common.video.FrameBuffer;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.util.List;

/**
 * Portable capture via {@link Robot#createMultiResolutionScreenCapture}: the largest resolution
 * variant is the device-pixel image (sharp on Retina/HiDPI). The output size is the logical
 * region times the capture scale (macOS: backing scale; Windows: physical/logical ratio).
 */
final class RobotCapturer implements ScreenCapturer {
    private final Robot robot;
    private final Rectangle logical;
    private final int width;
    private final int height;
    private FrameBuffer lastFb;
    private Graphics2D g;

    RobotCapturer(ScreenLayout layout, int monitorIndex) throws IOException {
        this.logical = layout.logicalRegion(monitorIndex);
        double scale = 1.0;
        for (ScreenLayout.Monitor m : layout.monitors) {
            if (monitorIndex >= 0 && m.index != monitorIndex) continue;
            double s = Os.WINDOWS && m.logical.width > 0 ? (double) m.physical.width / m.logical.width : m.captureScale;
            if (s > scale) scale = s;
        }
        if (Os.WINDOWS && monitorIndex < 0) {
            // Prefer the physical union when it is exactly a scaled logical union (uniform DPI).
            Rectangle phys = layout.physicalRegion(-1);
            double sx = (double) phys.width / logical.width;
            double sy = (double) phys.height / logical.height;
            if (Math.abs(sx - sy) < 0.01) scale = sx;
        }
        this.width = Math.max(1, (int) Math.round(logical.width * scale));
        this.height = Math.max(1, (int) Math.round(logical.height * scale));
        try {
            robot = new Robot();
        } catch (AWTException | SecurityException e) {
            throw new IOException("Robot unavailable: " + e, e);
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public String name() {
        return "Robot";
    }

    @Override
    public Grab grab(FrameBuffer fb, int timeoutMs) throws IOException {
        if (fb.width != width || fb.height != height) {
            throw new IllegalArgumentException("fb " + fb.width + "x" + fb.height + " != " + width + "x" + height);
        }
        Image best;
        try {
            MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(logical);
            List<Image> variants = mri.getResolutionVariants();
            best = null;
            long bestArea = -1;
            for (Image v : variants) {
                long a = (long) v.getWidth(null) * v.getHeight(null);
                if (a > bestArea) {
                    bestArea = a;
                    best = v;
                }
            }
            if (best == null) best = (Image) mri;
        } catch (RuntimeException e) {
            throw new CaptureLostException("Robot capture failed: " + e);
        }

        if (best instanceof BufferedImage && ((BufferedImage) best).getWidth() == width
                && ((BufferedImage) best).getHeight() == height && fastCopy((BufferedImage) best, fb)) {
            return Grab.UNKNOWN;
        }
        Graphics2D g2 = graphicsFor(fb);
        g2.drawImage(best, 0, 0, width, height, null);
        return Grab.UNKNOWN;
    }

    /** Direct int[] copy when the variant is an int RGB raster of the right size. */
    private static boolean fastCopy(BufferedImage img, FrameBuffer fb) {
        int t = img.getType();
        if ((t != BufferedImage.TYPE_INT_RGB && t != BufferedImage.TYPE_INT_ARGB)
                || !(img.getRaster().getDataBuffer() instanceof DataBufferInt)) return false;
        if (img.getRaster().getSampleModelTranslateX() != 0 || img.getRaster().getSampleModelTranslateY() != 0) return false;
        int[] src = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        if (src.length < fb.pixels.length) return false;
        int[] dst = fb.pixels;
        if (t == BufferedImage.TYPE_INT_RGB) {
            System.arraycopy(src, 0, dst, 0, dst.length);
        } else {
            for (int i = 0; i < dst.length; i++) dst[i] = src[i] & 0x00FFFFFF;
        }
        return true;
    }

    private Graphics2D graphicsFor(FrameBuffer fb) {
        if (fb != lastFb || g == null) {
            if (g != null) g.dispose();
            g = fb.image().createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            lastFb = fb;
        }
        return g;
    }

    @Override
    public void close() {
        if (g != null) g.dispose();
        g = null;
        lastFb = null;
    }
}
