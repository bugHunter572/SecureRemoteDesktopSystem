package com.srd.common.ui;

import com.srd.common.input.InputEvent;
import java.awt.Rectangle;

/**
 * The single source of truth for where the remote image sits inside the viewer component. Both
 * painting and input mapping use it.
 *
 * <p>This is the fix for the mouse-offset bug. The old viewers painted letterboxed (centered,
 * aspect-fit) but mapped the mouse as {@code x * remoteW / panelW}, ignoring the offset and the
 * uniform scale. Any window whose aspect ratio differed from the remote screen's sent clicks to
 * the wrong spot.
 */
public final class ViewTransform {
    public enum Mode {
        /** Aspect-preserving, centered (letterbox). */
        FIT,
        /** Fill the component, ignoring aspect. */
        STRETCH,
        /** One remote pixel per physical screen pixel (scroll if larger). */
        ACTUAL
    }

    public final double scale;
    public final double scaleY;
    public final double offX;
    public final double offY;
    public final int imageW;
    public final int imageH;

    private ViewTransform(double scale, double scaleY, double offX, double offY, int imageW, int imageH) {
        this.scale = scale;
        this.scaleY = scaleY;
        this.offX = offX;
        this.offY = offY;
        this.imageW = imageW;
        this.imageH = imageH;
    }

    /**
     * @param deviceScale UI scale of the screen showing the component (1.0, 1.25, 1.5 …); used so
     *                    ACTUAL maps one image pixel to one physical pixel
     */
    public static ViewTransform compute(Mode mode, int compW, int compH, int imageW, int imageH, double deviceScale) {
        if (imageW <= 0 || imageH <= 0 || compW <= 0 || compH <= 0) {
            return new ViewTransform(1, 1, 0, 0, Math.max(1, imageW), Math.max(1, imageH));
        }
        switch (mode) {
            case STRETCH:
                return new ViewTransform((double) compW / imageW, (double) compH / imageH, 0, 0, imageW, imageH);
            case ACTUAL: {
                double s = 1.0 / Math.max(0.5, deviceScale);
                double w = imageW * s, h = imageH * s;
                return new ViewTransform(s, s, Math.max(0, (compW - w) / 2), Math.max(0, (compH - h) / 2), imageW, imageH);
            }
            default: {
                double s = Math.min((double) compW / imageW, (double) compH / imageH);
                double w = imageW * s, h = imageH * s;
                return new ViewTransform(s, s, (compW - w) / 2, (compH - h) / 2, imageW, imageH);
            }
        }
    }

    /** Displayed width/height of the image in component units. */
    public double viewW() {
        return imageW * scale;
    }

    public double viewH() {
        return imageH * scaleY;
    }

    public boolean contains(double x, double y) {
        return x >= offX && y >= offY && x < offX + viewW() && y < offY + viewH();
    }

    /** Component point → image pixel (may be outside the image). */
    public double imageX(double x) {
        return (x - offX) / scale;
    }

    public double imageY(double y) {
        return (y - offY) / scaleY;
    }

    /** Component point → normalized 0..65535 (clamped to the image edges). */
    public int normX(double x) {
        double fx = (x - offX) / viewW();
        return clampNorm(fx);
    }

    public int normY(double y) {
        double fy = (y - offY) / viewH();
        return clampNorm(fy);
    }

    /** Component point → normalized 0..1 float (clamped). */
    public float fracX(double x) {
        return (float) Math.max(0, Math.min(1, (x - offX) / viewW()));
    }

    public float fracY(double y) {
        return (float) Math.max(0, Math.min(1, (y - offY) / viewH()));
    }

    private static int clampNorm(double f) {
        // Pixel-center mapping: the right/bottom edge maps to the last pixel, never past it.
        int v = (int) Math.round(f * InputEvent.NORM_MAX);
        return Math.max(0, Math.min(InputEvent.NORM_MAX, v));
    }

    /** Image rect → component rect (rounded outwards, +1 px margin for bilinear bleed). */
    public Rectangle toView(Rectangle img) {
        int x0 = (int) Math.floor(offX + img.x * scale) - 1;
        int y0 = (int) Math.floor(offY + img.y * scaleY) - 1;
        int x1 = (int) Math.ceil(offX + (img.x + img.width) * scale) + 1;
        int y1 = (int) Math.ceil(offY + (img.y + img.height) * scaleY) + 1;
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }
}
