package com.srd.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.input.InputEvent;
import org.junit.jupiter.api.Test;

/**
 * The mouse-offset bug: a 1920×1080 remote screen shown letterboxed in a 1000×1000 window. The
 * old mapping {@code x * remoteW / panelW} sent a click at the image's top-left corner (window
 * y≈218) to remote y≈235 instead of 0.
 */
class ViewTransformTest {

    private static int remoteX(ViewTransform t, double x, int w) {
        return InputEvent.denorm(t.normX(x), w);
    }

    private static int remoteY(ViewTransform t, double y, int h) {
        return InputEvent.denorm(t.normY(y), h);
    }

    @Test
    void letterboxedFitMapsCornersAndCenterExactly() {
        int rw = 1920, rh = 1080;
        ViewTransform t = ViewTransform.compute(ViewTransform.Mode.FIT, 1000, 1000, rw, rh, 1.0);
        double top = t.offY;
        assertEquals(1000.0 / 1920, t.scale, 1e-9);
        assertTrue(top > 200 && top < 230, "letterbox offset " + top);
        assertEquals(0, remoteX(t, 0.2, rw));
        assertEquals(0, remoteY(t, top + 0.2, rh));
        assertEquals(rw - 1, remoteX(t, 999.9, rw));
        assertEquals(rh - 1, remoteY(t, top + t.viewH() - 0.1, rh));
        assertEquals(rw / 2, remoteX(t, 500.0 + t.scale / 2, rw), 1);
        assertEquals(rh / 2, remoteY(t, top + t.viewH() / 2, rh), 1);
        assertFalse(t.contains(500, top - 1));
        assertTrue(t.contains(500, top + 1));
    }

    @Test
    void everyRemotePixelCenterRoundTrips() {
        int[][] sizes = {{1920, 1080}, {2560, 1440}, {1366, 768}, {3840, 2160}, {800, 1280}};
        int[][] windows = {{1000, 1000}, {1280, 720}, {640, 900}, {3000, 1000}};
        for (int[] s : sizes) {
            for (int[] win : windows) {
                ViewTransform t = ViewTransform.compute(ViewTransform.Mode.FIT, win[0], win[1], s[0], s[1], 1.0);
                for (int px = 0; px < s[0]; px += 7) {
                    double vx = t.offX + (px + 0.5) * t.scale;
                    assertEquals(px, remoteX(t, vx, s[0]), "size " + s[0] + " win " + win[0] + " px " + px);
                }
                for (int py = 0; py < s[1]; py += 5) {
                    double vy = t.offY + (py + 0.5) * t.scaleY;
                    assertEquals(py, remoteY(t, vy, s[1]));
                }
            }
        }
    }

    @Test
    void stretchUsesIndependentScales() {
        ViewTransform t = ViewTransform.compute(ViewTransform.Mode.STRETCH, 800, 800, 1600, 400, 1.0);
        assertEquals(0.5, t.scale, 1e-9);
        assertEquals(2.0, t.scaleY, 1e-9);
        assertEquals(800, remoteX(t, 400.25, 1600));
        assertEquals(200, remoteY(t, 401, 400));
    }

    @Test
    void actualSizeAccountsForHiDpi() {
        ViewTransform t = ViewTransform.compute(ViewTransform.Mode.ACTUAL, 2000, 2000, 1920, 1080, 1.5);
        assertEquals(1 / 1.5, t.scale, 1e-9); // one image pixel per physical pixel
    }
}
