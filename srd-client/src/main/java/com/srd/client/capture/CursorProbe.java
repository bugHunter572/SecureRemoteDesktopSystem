package com.srd.client.capture;

import com.srd.common.ui.CursorKind;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the host pointer position and shape cheaply (~60×/s). Windows: GetCursorInfo (physical
 * px) with the handle compared to the shared system cursors; elsewhere MouseInfo (logical) and
 * always ARROW.
 */
public final class CursorProbe {
    private static final int CURSOR_SHOWING = 1;

    private static volatile boolean winFailed = !Os.WINDOWS;
    private static Function getCursorInfo;
    private static Map<Long, CursorKind> handles;
    private static Memory info;

    private CursorProbe() {
    }

    public static CursorState read(ScreenLayout layout) {
        if (!winFailed) {
            try {
                CursorState s = readWindows();
                if (s != null) return s;
            } catch (Throwable t) {
                winFailed = true;
                Log.warn("GetCursorInfo unavailable, using MouseInfo: " + t);
            }
        }
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi != null) {
                Point p = pi.getLocation();
                return new CursorState(p.x, p.y, true, CursorKind.ARROW);
            }
        } catch (Throwable ignored) {
        }
        ScreenLayout.Monitor m = layout == null ? null : layout.primary();
        int x = m == null ? 0 : m.physical.x + m.physical.width / 2;
        int y = m == null ? 0 : m.physical.y + m.physical.height / 2;
        return new CursorState(x, y, false, CursorKind.HIDDEN);
    }

    private static synchronized CursorState readWindows() {
        if (getCursorInfo == null) init();
        // CURSORINFO { DWORD cbSize; DWORD flags; HCURSOR hCursor; POINT ptScreenPos; }
        int ps = Native.POINTER_SIZE;
        int ptOff = 8 + ps;
        info.setInt(0, 8 + ps + 8);
        if (getCursorInfo.invokeInt(new Object[]{info}) == 0) return null; // e.g. secure desktop
        int flags = info.getInt(4);
        long h = ps == 8 ? info.getLong(8) : info.getInt(8) & 0xFFFFFFFFL;
        int x = info.getInt(ptOff);
        int y = info.getInt(ptOff + 4);
        if ((flags & CURSOR_SHOWING) == 0 || h == 0) return new CursorState(x, y, false, CursorKind.HIDDEN);
        CursorKind k = handles.get(h);
        return new CursorState(x, y, true, k == null ? CursorKind.ARROW : k);
    }

    private static void init() {
        NativeLibrary user32 = NativeLibrary.getInstance("user32");
        Function load = user32.getFunction("LoadCursorW", Function.ALT_CONVENTION);
        Map<Long, CursorKind> m = new HashMap<>();
        int[] ids = {32512, 32513, 32514, 32515, 32642, 32643, 32644, 32645, 32646, 32648, 32649, 32650};
        CursorKind[] kinds = {CursorKind.ARROW, CursorKind.IBEAM, CursorKind.WAIT, CursorKind.CROSSHAIR,
                CursorKind.SIZE_NWSE, CursorKind.SIZE_NESW, CursorKind.SIZE_WE, CursorKind.SIZE_NS,
                CursorKind.SIZE_ALL, CursorKind.NO, CursorKind.HAND, CursorKind.APP_STARTING};
        for (int i = 0; i < ids.length; i++) {
            Pointer p = (Pointer) load.invoke(Pointer.class, new Object[]{null, Pointer.createConstant(ids[i])});
            if (p != null) m.put(Pointer.nativeValue(p), kinds[i]);
        }
        handles = m;
        info = new Memory(8 + Native.POINTER_SIZE + 8);
        info.clear();
        getCursorInfo = user32.getFunction("GetCursorInfo", Function.ALT_CONVENTION);
    }
}
