package com.srd.common.video;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A screen-sized int[] of 0x00RRGGBB pixels with a zero-copy {@link BufferedImage} view
 * (TYPE_INT_RGB shares the array), used by both the host (capture target) and the viewer
 * (decode target that Swing paints directly).
 */
public final class FrameBuffer {
    public final int width;
    public final int height;
    public final int[] pixels;
    private final BufferedImage image;

    public FrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    public BufferedImage image() {
        return image;
    }

    public void copyFrom(FrameBuffer other) {
        if (other.width != width || other.height != height) throw new IllegalArgumentException("size mismatch");
        System.arraycopy(other.pixels, 0, pixels, 0, pixels.length);
    }

    /** Independent copy (e.g. for saving a screenshot while decoding continues). */
    public BufferedImage snapshot() {
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) copy.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels, 0, dst, 0, pixels.length);
        return copy;
    }
}
