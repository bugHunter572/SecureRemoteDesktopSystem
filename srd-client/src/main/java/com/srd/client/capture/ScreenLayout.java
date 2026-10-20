package com.srd.client.capture;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The host's monitors in two coordinate systems:
 * <ul>
 *   <li><b>physical</b>: real device pixels on the Windows virtual desktop (per-monitor DPI
 *       aware). This is what DXGI/GDI capture and SendInput use;</li>
 *   <li><b>logical</b>: AWT/Java2D user-space coordinates. This is what Robot uses, and the
 *       only system on macOS/Linux.</li>
 * </ul>
 * On non-Windows hosts both are the same logical rectangles (Robot space), except that capture
 * may be at the Retina backing scale (see {@link Monitor#captureScale}).
 */
public final class ScreenLayout {
    public static final class Monitor {
        public final int index;
        public final String name;
        public final Rectangle physical;
        public final Rectangle logical;
        public final boolean primary;
        /** Pixels captured per logical unit (1.0 on Windows physical capture; 2.0 on Retina Robot capture). */
        public final double captureScale;

        public Monitor(int index, String name, Rectangle physical, Rectangle logical, boolean primary, double captureScale) {
            this.index = index;
            this.name = name;
            this.physical = physical;
            this.logical = logical;
            this.primary = primary;
            this.captureScale = captureScale;
        }

        @Override
        public String toString() {
            return "Monitor" + index + "[" + name + " phys=" + physical + " log=" + logical + (primary ? " primary" : "") + "]";
        }
    }

    public final List<Monitor> monitors;

    public ScreenLayout(List<Monitor> monitors) {
        this.monitors = Collections.unmodifiableList(new ArrayList<>(monitors));
    }

    /** Union of the physical bounds of the given monitors (all if {@code index} &lt; 0 or out of range). */
    public Rectangle physicalRegion(int index) {
        if (index >= 0 && index < monitors.size()) return new Rectangle(monitors.get(index).physical);
        Rectangle u = null;
        for (Monitor m : monitors) u = u == null ? new Rectangle(m.physical) : u.union(m.physical);
        return u == null ? new Rectangle(0, 0, 1, 1) : u;
    }

    /** Union of logical bounds (Robot space) of the given monitors (all if out of range). */
    public Rectangle logicalRegion(int index) {
        if (index >= 0 && index < monitors.size()) return new Rectangle(monitors.get(index).logical);
        Rectangle u = null;
        for (Monitor m : monitors) u = u == null ? new Rectangle(m.logical) : u.union(m.logical);
        return u == null ? new Rectangle(0, 0, 1, 1) : u;
    }

    public Monitor primary() {
        for (Monitor m : monitors) if (m.primary) return m;
        return monitors.isEmpty() ? null : monitors.get(0);
    }

    @Override
    public String toString() {
        return "ScreenLayout" + monitors;
    }
}
