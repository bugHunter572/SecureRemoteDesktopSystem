package com.srd.client.capture;

import com.srd.common.video.FrameBuffer;
import java.awt.Rectangle;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Manual probe (plain main, not a unit test): prints the display layout, runs each capturer for
 * ~60 frames, saves the first frame of each as PNG, and samples the cursor. Injects no input.
 *
 * <pre>java -cp classes;jna.jar;jna-platform.jar com.srd.client.capture.CaptureProbe [outDir] [monitorIndex]</pre>
 */
public final class CaptureProbe {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"));
        int monitor = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        outDir.mkdirs();

        ScreenLayout layout = Displays.layout();
        System.out.println("Layout: " + layout);
        for (ScreenLayout.Monitor m : layout.monitors) System.out.println("  " + m + " scale=" + m.captureScale);
        System.out.println("physicalRegion(" + monitor + ")=" + layout.physicalRegion(monitor)
                + " logicalRegion=" + layout.logicalRegion(monitor));

        for (String type : new String[]{"DXGI", "GDI", "Robot"}) {
            ScreenCapturer c;
            try {
                c = Capturers.openType(type, layout, monitor);
            } catch (Exception e) {
                System.out.println(type + ": OPEN FAILED " + e);
                continue;
            }
            try {
                FrameBuffer fb = new FrameBuffer(c.width(), c.height());
                long t0 = System.nanoTime();
                ScreenCapturer.Grab g = c.grab(fb, 100);
                long firstMs = (System.nanoTime() - t0) / 1_000_000;
                System.out.println(type + ": " + c.width() + "x" + c.height() + " first grab " + firstMs + " ms changed="
                        + g.changed + " dirty=" + describe(g));
                ImageIO.write(fb.snapshot(), "png", new File(outDir, "probe-" + type + ".png"));
                long total = 0;
                int changed = 0, rects = 0, n = 60;
                long maxMs = 0;
                for (int i = 0; i < n; i++) {
                    long s = System.nanoTime();
                    g = c.grab(fb, 16);
                    long d = System.nanoTime() - s;
                    total += d;
                    maxMs = Math.max(maxMs, d / 1_000_000);
                    if (g.changed) changed++;
                    if (g.dirty != null) rects += g.dirty.size();
                }
                System.out.printf("%s: %d grabs avg %.2f ms (max %d ms; includes wait-for-change for DXGI) changed=%d dirtyRects=%d%n",
                        type, n, total / 1e6 / n, maxMs, changed, rects);
                // Pure copy cost for DXGI: zero timeout.
                if (type.equals("DXGI")) {
                    long s = System.nanoTime();
                    for (int i = 0; i < 30; i++) c.grab(fb, 0);
                    System.out.printf("DXGI: 30 grabs with timeout 0 avg %.2f ms%n", (System.nanoTime() - s) / 1e6 / 30);
                }
            } catch (Exception e) {
                System.out.println(type + ": GRAB FAILED " + e);
                e.printStackTrace(System.out);
            } finally {
                c.close();
            }
        }

        ScreenCapturer auto = Capturers.open(layout, monitor);
        System.out.println("Auto-chosen: " + auto.name());
        auto.close();

        for (int i = 0; i < 5; i++) {
            CursorState cs = CursorProbe.read(layout);
            System.out.println("Cursor: " + cs.x + "," + cs.y + " visible=" + cs.visible + " kind=" + cs.kind);
            Thread.sleep(200);
        }
        long s = System.nanoTime();
        for (int i = 0; i < 1000; i++) CursorProbe.read(layout);
        System.out.printf("CursorProbe.read avg %.1f us%n", (System.nanoTime() - s) / 1e3 / 1000);

        mappingMath(layout);
        System.exit(0);
    }

    /** Pure math only (nothing is injected): normalized -> physical -> SendInput absolute round-trip. */
    private static void mappingMath(ScreenLayout layout) {
        int[] widths = {800, 1024, 1366, 1920, 2560, 3440, 3840, 5760, 7680};
        int worstFloor = 0, worstRound = 0;
        for (int vw : widths) {
            for (int px = 0; px < vw; px++) {
                int ax = com.srd.client.input.Injectors.windowsAbsolute(0, 0, new Rectangle(px, 0, 1, 1), new Rectangle(0, 0, vw, vw))[2];
                int backFloor = (int) ((long) ax * vw / 65536);          // model A
                int backRound = (int) Math.round(ax * (vw - 1) / 65535.0); // model B
                if (backFloor != px) worstFloor++;
                if (backRound != px) worstRound++;
            }
        }
        System.out.println("Absolute mapping round-trip mismatches: floor-model=" + worstFloor + " round-model=" + worstRound);
        // Normalized centre-of-pixel -> denorm exactness on each monitor.
        for (ScreenLayout.Monitor m : layout.monitors) {
            int bad = 0;
            for (int i = 0; i < m.physical.width; i++) {
                int n = (int) ((i + 0.5) * 65536.0 / m.physical.width);
                int[] r = com.srd.client.input.Injectors.windowsAbsolute(n, 0, m.physical, m.physical);
                if (r[0] != m.physical.x + i) bad++;
            }
            System.out.println("Monitor " + m.index + " normalized->physical x mismatches: " + bad
                    + "; corners abs=" + java.util.Arrays.toString(com.srd.client.input.Injectors.windowsAbsolute(0, 0, m.physical, layout.physicalRegion(-1)))
                    + " " + java.util.Arrays.toString(com.srd.client.input.Injectors.windowsAbsolute(65535, 65535, m.physical, layout.physicalRegion(-1)))
                    + " robot(65535)=" + com.srd.client.input.Injectors.robotLogical(65535, m.logical.x, m.logical.width));
        }
    }

    private static String describe(ScreenCapturer.Grab g) {
        if (g.dirty == null) return "unknown";
        StringBuilder sb = new StringBuilder("[");
        for (Rectangle r : g.dirty) sb.append(r.x).append(',').append(r.y).append(' ').append(r.width).append('x').append(r.height).append(' ');
        return sb.append(']').toString();
    }
}
