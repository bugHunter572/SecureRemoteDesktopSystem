package com.srd.common.input;

import com.srd.common.protocol.ProtocolException;
import com.srd.common.protocol.WireReader;
import com.srd.common.protocol.WireWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * One remote input event. Pointer coordinates are normalized to 0..65535 across the streamed
 * image, so they map exactly regardless of the viewer's window size or either side's resolution
 * or DPI. Buttons carry their position: a press lands where the pointer actually is, even if an
 * intermediate move was coalesced away. No synthetic "click" events exist; the host OS derives
 * double-clicks from real down/up timing, just like a local mouse.
 */
public final class InputEvent {
    public static final int MOVE = 1;
    public static final int BUTTON = 2;
    public static final int WHEEL = 3;
    public static final int KEY = 4;
    public static final int RELEASE_ALL = 5;
    public static final int TEXT = 6;

    public static final int BTN_LEFT = 1;
    public static final int BTN_MIDDLE = 2;
    public static final int BTN_RIGHT = 3;
    public static final int BTN_BACK = 4;
    public static final int BTN_FORWARD = 5;

    /** One wheel notch in Windows units (WHEEL_DELTA). */
    public static final int WHEEL_DELTA = 120;
    public static final int NORM_MAX = 65535;

    public final int kind;
    public final int nx, ny;
    public final int button;
    public final boolean down;
    /** Vertical wheel in 1/120 notch units; positive = scroll up (away from the user). */
    public final int wheelY;
    /** Horizontal wheel in 1/120 notch units; positive = scroll right. */
    public final int wheelX;
    /** PC/AT set-1 scancode (0 = unknown) and whether it carries the E0 prefix. */
    public final int scancode;
    public final boolean extended;
    /** java.awt.event.KeyEvent VK code and key location, as a fallback / for Robot hosts. */
    public final int vk;
    public final int location;
    public final String text;

    private InputEvent(int kind, int nx, int ny, int button, boolean down, int wheelY, int wheelX,
                       int scancode, boolean extended, int vk, int location, String text) {
        this.kind = kind;
        this.nx = nx;
        this.ny = ny;
        this.button = button;
        this.down = down;
        this.wheelY = wheelY;
        this.wheelX = wheelX;
        this.scancode = scancode;
        this.extended = extended;
        this.vk = vk;
        this.location = location;
        this.text = text;
    }

    public static InputEvent move(int nx, int ny) {
        return new InputEvent(MOVE, clampN(nx), clampN(ny), 0, false, 0, 0, 0, false, 0, 0, null);
    }

    public static InputEvent button(int button, boolean down, int nx, int ny) {
        return new InputEvent(BUTTON, clampN(nx), clampN(ny), button, down, 0, 0, 0, false, 0, 0, null);
    }

    public static InputEvent wheel(int wheelY, int wheelX) {
        return new InputEvent(WHEEL, 0, 0, 0, false, clamp16(wheelY), clamp16(wheelX), 0, false, 0, 0, null);
    }

    public static InputEvent key(boolean down, int scancode, boolean extended, int vk, int location) {
        return new InputEvent(KEY, 0, 0, 0, down, 0, 0, scancode, extended, vk, location, null);
    }

    public static InputEvent releaseAll() {
        return new InputEvent(RELEASE_ALL, 0, 0, 0, false, 0, 0, 0, false, 0, 0, null);
    }

    public static InputEvent text(String s) {
        return new InputEvent(TEXT, 0, 0, 0, false, 0, 0, 0, false, 0, 0, s);
    }

    /**
     * Normalized coordinate → pixel index in {@code [0, size)}. Viewers send the pixel center
     * ((i + 0.5) / size), so this returns exactly i: no drift at any resolution.
     */
    public static int denorm(int n, int size) {
        return Math.max(0, Math.min(size - 1, (int) ((long) n * size / 65536L)));
    }

    /** Normalized coordinate → fractional position 0..1 (for mapping onto logical screens). */
    public static double frac(int n) {
        return n / 65536.0 + 0.5 / 65536.0;
    }

    private static int clampN(int v) {
        return Math.max(0, Math.min(NORM_MAX, v));
    }

    private static int clamp16(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    public void write(WireWriter w) {
        w.u8(kind);
        switch (kind) {
            case MOVE:
                w.u16(nx).u16(ny);
                break;
            case BUTTON:
                w.u8(button).u8(down ? 1 : 0).u16(nx).u16(ny);
                break;
            case WHEEL:
                w.u16(wheelY & 0xFFFF).u16(wheelX & 0xFFFF);
                break;
            case KEY:
                w.u8(down ? 1 : 0).u16(scancode).u8(extended ? 1 : 0).i32(vk).u8(location);
                break;
            case TEXT:
                w.str(text);
                break;
            default:
                break;
        }
    }

    public static InputEvent read(WireReader r) {
        int kind = r.u8();
        switch (kind) {
            case MOVE:
                return move(r.u16(), r.u16());
            case BUTTON: {
                int b = r.u8();
                boolean d = r.u8() != 0;
                return button(b, d, r.u16(), r.u16());
            }
            case WHEEL: {
                int y = r.i16();
                int x = r.i16();
                return wheel(y, x);
            }
            case KEY: {
                boolean d = r.u8() != 0;
                int sc = r.u16();
                boolean ext = r.u8() != 0;
                int vk = r.i32();
                int loc = r.u8();
                return key(d, sc, ext, vk, loc);
            }
            case RELEASE_ALL:
                return releaseAll();
            case TEXT:
                return text(r.str());
            default:
                throw new ProtocolException("unknown input kind " + kind);
        }
    }

    public static byte[] encodeBatch(List<InputEvent> events) {
        WireWriter w = new WireWriter(8 + events.size() * 12);
        w.u16(events.size());
        for (InputEvent e : events) e.write(w);
        return w.toByteArray();
    }

    public static byte[] encode(InputEvent e) {
        WireWriter w = new WireWriter(16);
        w.u16(1);
        e.write(w);
        return w.toByteArray();
    }

    public static List<InputEvent> decodeBatch(byte[] payload) {
        WireReader r = new WireReader(payload);
        int n = r.u16();
        List<InputEvent> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(read(r));
        return out;
    }

    @Override
    public String toString() {
        switch (kind) {
            case MOVE:
                return "Move(" + nx + "," + ny + ")";
            case BUTTON:
                return "Button(" + button + (down ? " down" : " up") + " @" + nx + "," + ny + ")";
            case WHEEL:
                return "Wheel(y=" + wheelY + ", x=" + wheelX + ")";
            case KEY:
                return "Key(" + (down ? "down" : "up") + " sc=0x" + Integer.toHexString(scancode) + (extended ? " E0" : "") + " vk=" + vk + ")";
            case RELEASE_ALL:
                return "ReleaseAll";
            default:
                return "Text(" + text + ")";
        }
    }
}
