package com.srd.client.overlay;

import com.srd.client.capture.Capturers;
import com.srd.client.capture.Displays;
import com.srd.client.capture.ScreenCapturer;
import com.srd.client.capture.ScreenLayout;
import com.srd.common.video.FrameBuffer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * Proves the annotation overlay is invisible to screen capture. It shows a translucent, always-on-top
 * window with a bright GREEN bar, excludes it (WDA_EXCLUDEFROMCAPTURE), then captures the screen two
 * ways that cover how Zoom/Meet/Teams/OBS capture:
 *   - java.awt.Robot  = GDI BitBlt (window / region share)
 *   - DxgiCapturer    = Desktop Duplication (full-screen share)
 * With exclusion ON the bar must be absent from both; the control run (exclusion OFF) must show it.
 * Nothing is sent anywhere. Run on a real desktop session.
 */
public final class OverlayStealthProbe {
    private static final int BAR_RGB = 0x00FF00;

    public static void main(String[] args) throws Exception {
        ScreenLayout layout = Displays.layout();
        Rectangle mon = layout.monitors.get(0).physical;
        Rectangle logical = layout.monitors.get(0).logical;
        // A horizontal band across the middle of the primary monitor.
        int barH = 60;
        Rectangle barLogical = new Rectangle(logical.x + logical.width / 4, logical.y + logical.height / 2 - barH / 2,
                logical.width / 2, barH);

        for (boolean exclude : new boolean[] {true, false}) {
            JWindow[] win = new JWindow[1];
            SwingUtilities.invokeAndWait(() -> {
                JWindow w = new JWindow();
                w.setAlwaysOnTop(true);
                w.setFocusableWindowState(false);
                w.setBackground(new Color(0, 0, 0, 0));
                w.setContentPane(new JComponent() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setColor(new Color(BAR_RGB));
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                });
                w.setBounds(barLogical);
                w.setVisible(true);
                win[0] = w;
            });
            Thread.sleep(250);
            boolean ok = exclude ? OverlayNative.configure(win[0]) : setNone(win[0]);
            int aff = OverlayNative.displayAffinity(win[0]);
            Thread.sleep(500);

            // Pixel region to inspect, in physical coordinates (centre of the bar).
            Rectangle probe = new Rectangle(mon.x + mon.width / 2 - 100, mon.y + mon.height / 2 - 10, 200, 20);

            int robotHits = countGreen(robotCapture(probe), probe.width, probe.height);
            int dxgiHits = dxgiGreen(layout, probe);

            System.out.printf("exclude=%-5s configure=%-5s affinity=0x%02X  | GDI(Robot) green px=%d  DXGI green px=%d  => %s%n",
                    exclude, ok, aff & 0xFF, robotHits, dxgiHits,
                    exclude ? (robotHits == 0 && dxgiHits == 0 ? "HIDDEN (good)" : "LEAKS!")
                            : (robotHits > 0 || dxgiHits > 0 ? "visible (control ok)" : "control saw nothing?!"));

            SwingUtilities.invokeAndWait(win[0]::dispose);
            Thread.sleep(300);
        }
        System.exit(0);
    }

    private static boolean setNone(Window w) {
        // Baseline: no exclusion, so the bar should be captured.
        return true;
    }

    private static BufferedImage robotCapture(Rectangle r) throws Exception {
        return new Robot().createScreenCapture(r);
    }

    private static int countGreen(BufferedImage img, int w, int h) {
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y) & 0xFFFFFF;
                if (((p >> 8) & 0xFF) > 180 && (p & 0xFF) < 80 && ((p >> 16) & 0xFF) < 80) n++;
            }
        }
        return n;
    }

    private static int dxgiGreen(ScreenLayout layout, Rectangle probePhysical) {
        try (ScreenCapturer c = Capturers.openType("DXGI", layout, 0)) {
            FrameBuffer fb = new FrameBuffer(c.width(), c.height());
            for (int i = 0; i < 4; i++) {
                c.grab(fb, 200);
            }
            Rectangle mon = layout.monitors.get(0).physical;
            int ox = probePhysical.x - mon.x, oy = probePhysical.y - mon.y;
            int n = 0;
            for (int y = 0; y < probePhysical.height; y++) {
                for (int x = 0; x < probePhysical.width; x++) {
                    int px = ox + x, py = oy + y;
                    if (px < 0 || py < 0 || px >= fb.width || py >= fb.height) continue;
                    int p = fb.pixels[py * fb.width + px] & 0xFFFFFF;
                    if (((p >> 8) & 0xFF) > 180 && (p & 0xFF) < 80 && ((p >> 16) & 0xFF) < 80) n++;
                }
            }
            return n;
        } catch (Exception e) {
            System.out.println("  (DXGI capture unavailable: " + e.getMessage() + ")");
            return -1;
        }
    }
}
