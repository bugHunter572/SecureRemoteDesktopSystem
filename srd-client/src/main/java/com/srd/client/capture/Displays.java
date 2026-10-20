package com.srd.client.capture;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Enumerates the host's monitors as a {@link ScreenLayout}.
 *
 * <p>Windows: physical rectangles come from EnumDisplayMonitors/GetMonitorInfo (the JVM is
 * per-monitor-v2 DPI aware once AWT is initialised, so these are device pixels on the virtual
 * desktop) and are paired with the AWT GraphicsDevice whose logical bounds Robot uses. AWT
 * numbers its screens in EnumDisplayMonitors order ({@code getIDstring()} = {@code "\Display<n>"}),
 * which is the primary match; geometry is the fallback. macOS/Linux: AWT only, physical = logical,
 * captureScale = the device's default transform (2.0 on Retina).
 *
 * <p>Never throws: falls back to a single Toolkit-sized monitor.
 */
public final class Displays {
    private Displays() {
    }

    public static ScreenLayout layout() {
        try {
            List<ScreenLayout.Monitor> list = null;
            if (Os.WINDOWS) list = Os.tryNative("EnumDisplayMonitors", Displays::windows, null);
            if (list == null || list.isEmpty()) list = awt();
            if (list != null && !list.isEmpty()) return sortAndIndex(list);
        } catch (Throwable t) {
            Log.warn("Display enumeration failed: " + t);
        }
        return fallback();
    }

    // ------------------------------------------------------------------ Windows

    private static final class NativeMon {
        final Rectangle rect;
        final String device;
        final boolean primary;

        NativeMon(Rectangle rect, String device, boolean primary) {
            this.rect = rect;
            this.device = device;
            this.primary = primary;
        }
    }

    private static List<ScreenLayout.Monitor> windows() {
        // AWT initialisation makes the process per-monitor DPI aware; do it before asking Win32.
        GraphicsDevice[] devs = awtDevices();
        final List<NativeMon> mons = new ArrayList<>();
        WinUser.MONITORENUMPROC proc = (hMonitor, hdc, rc, data) -> {
            WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
            if (User32.INSTANCE.GetMonitorInfo(hMonitor, info).booleanValue()) {
                WinDef.RECT r = info.rcMonitor;
                mons.add(new NativeMon(new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top),
                        Native.toString(info.szDevice), (info.dwFlags & 1 /* MONITORINFOF_PRIMARY */) != 0));
            }
            return 1;
        };
        User32.INSTANCE.EnumDisplayMonitors(null, null, proc, new WinDef.LPARAM(0));
        if (mons.isEmpty()) return null;

        boolean[] used = new boolean[devs.length];
        List<ScreenLayout.Monitor> out = new ArrayList<>();
        for (int i = 0; i < mons.size(); i++) {
            NativeMon nm = mons.get(i);
            int match = -1;
            // 1) AWT's screen number is its EnumDisplayMonitors index.
            for (int d = 0; d < devs.length && match < 0; d++) {
                if (!used[d] && awtScreenNumber(devs[d]) == i) match = d;
            }
            // 2) Same origin (older JDKs report the physical origin) or same physical size.
            for (int d = 0; d < devs.length && match < 0; d++) {
                if (used[d]) continue;
                Rectangle b = bounds(devs[d]);
                if (b != null && b.x == nm.rect.x && b.y == nm.rect.y) match = d;
            }
            // 3) Plain order.
            if (match < 0 && i < devs.length && !used[i]) match = i;

            Rectangle logical;
            if (match >= 0) {
                used[match] = true;
                logical = bounds(devs[match]);
                if (logical == null) logical = new Rectangle(nm.rect);
            } else {
                logical = new Rectangle(nm.rect);
            }
            out.add(new ScreenLayout.Monitor(i, nm.device, nm.rect, logical, nm.primary, 1.0));
        }
        return out;
    }

    /** "\Display3" → 3, else -1. */
    private static int awtScreenNumber(GraphicsDevice d) {
        try {
            String id = d.getIDstring();
            if (id == null) return -1;
            int k = id.length();
            while (k > 0 && Character.isDigit(id.charAt(k - 1))) k--;
            if (k == id.length() || !id.toLowerCase().contains("display")) return -1;
            return Integer.parseInt(id.substring(k));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ AWT

    private static GraphicsDevice[] awtDevices() {
        try {
            if (GraphicsEnvironment.isHeadless()) return new GraphicsDevice[0];
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        } catch (Throwable t) {
            return new GraphicsDevice[0];
        }
    }

    private static Rectangle bounds(GraphicsDevice d) {
        try {
            return d.getDefaultConfiguration().getBounds();
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<ScreenLayout.Monitor> awt() {
        GraphicsDevice[] devs = awtDevices();
        GraphicsDevice prim = null;
        try {
            if (devs.length > 0) prim = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (Throwable ignored) {
        }
        List<ScreenLayout.Monitor> out = new ArrayList<>();
        for (int i = 0; i < devs.length; i++) {
            GraphicsDevice d = devs[i];
            Rectangle b = bounds(d);
            if (b == null || b.width <= 0 || b.height <= 0) continue;
            double scale = 1.0;
            try {
                GraphicsConfiguration gc = d.getDefaultConfiguration();
                scale = gc.getDefaultTransform().getScaleX();
                if (!(scale > 0) || Double.isInfinite(scale)) scale = 1.0;
            } catch (Throwable ignored) {
            }
            // Robot works in logical coordinates; on Windows (only reached if Win32 failed) the
            // capture is resampled to logical too, so scale 1.
            if (Os.WINDOWS) scale = 1.0;
            String name;
            try {
                name = d.getIDstring();
            } catch (Throwable t) {
                name = "screen" + i;
            }
            out.add(new ScreenLayout.Monitor(i, name, new Rectangle(b), new Rectangle(b), d == prim, scale));
        }
        return out;
    }

    private static ScreenLayout fallback() {
        Dimension s;
        try {
            s = Toolkit.getDefaultToolkit().getScreenSize();
        } catch (Throwable t) {
            s = new Dimension(1024, 768);
        }
        Rectangle r = new Rectangle(0, 0, Math.max(1, s.width), Math.max(1, s.height));
        return new ScreenLayout(Collections.singletonList(
                new ScreenLayout.Monitor(0, "screen", r, new Rectangle(r), true, 1.0)));
    }

    private static ScreenLayout sortAndIndex(List<ScreenLayout.Monitor> in) {
        List<ScreenLayout.Monitor> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.<ScreenLayout.Monitor>comparingInt(m -> m.physical.x).thenComparingInt(m -> m.physical.y));
        List<ScreenLayout.Monitor> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ScreenLayout.Monitor m = sorted.get(i);
            out.add(new ScreenLayout.Monitor(i, m.name, m.physical, m.logical, m.primary, m.captureScale));
        }
        return new ScreenLayout(out);
    }
}
