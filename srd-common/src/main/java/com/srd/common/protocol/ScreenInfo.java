package com.srd.common.protocol;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometry of the live stream. {@code width x height} is the size of the streamed image in its own
 * pixels (physical pixels where the host can capture them). Monitors are in stream pixels so a
 * viewer can offer "show monitor N" without knowing the host's DPI layout.
 */
public final class ScreenInfo {
    public static final int ALL_MONITORS = 255;

    public final int width;
    public final int height;
    public final List<Rectangle> monitors;
    /** Index into {@link #monitors} that is being streamed, or {@link #ALL_MONITORS}. */
    public final int selected;

    public ScreenInfo(int width, int height, List<Rectangle> monitors, int selected) {
        this.width = width;
        this.height = height;
        this.monitors = Collections.unmodifiableList(new ArrayList<>(monitors));
        this.selected = selected;
    }

    public byte[] encode() {
        WireWriter w = new WireWriter(32 + monitors.size() * 16);
        w.u16(width).u16(height).u8(selected).u8(monitors.size());
        for (Rectangle r : monitors) w.i32(r.x).i32(r.y).i32(r.width).i32(r.height);
        return w.toByteArray();
    }

    public static ScreenInfo decode(byte[] data) {
        WireReader r = new WireReader(data);
        int w = r.u16();
        int h = r.u16();
        int sel = r.u8();
        int n = r.u8();
        List<Rectangle> mons = new ArrayList<>();
        for (int i = 0; i < n; i++) mons.add(new Rectangle(r.i32(), r.i32(), r.i32(), r.i32()));
        return new ScreenInfo(w, h, mons, sel);
    }

    @Override
    public String toString() {
        return "ScreenInfo[" + width + "x" + height + ", monitors=" + monitors.size() + ", selected=" + selected + "]";
    }
}
