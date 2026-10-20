package com.srd.client.overlay;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Window;

/**
 * Native window tweaks for the annotation overlay.
 *
 * <p>Windows: the overlay is made click-through and non-activating, so it never steals the
 * broadcaster's mouse or focus. It is also tagged {@code WDA_EXCLUDEFROMCAPTURE} (Windows 10
 * 2004+), which keeps the annotations off Zoom/Meet/Teams screen shares and out of our own
 * capture while the local user still sees them. Other platforms have no equivalent here, so
 * {@link #configure} returns false and the caller warns that shares may include the drawings.
 */
public final class OverlayNative {
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1));

    private OverlayNative() {
    }

    /** Calls missing from jna-platform's User32 mapping. */
    private interface Affinity extends StdCallLibrary {
        Affinity INSTANCE = Native.load("user32", Affinity.class);

        boolean SetWindowDisplayAffinity(HWND hwnd, int affinity);

        boolean GetWindowDisplayAffinity(HWND hwnd, com.sun.jna.ptr.IntByReference affinity);
    }

    /** Reads back the current display affinity, or -1 if it can't be queried. */
    public static int displayAffinity(Window w) {
        if (!Os.WINDOWS) return -1;
        return Os.tryNative("get display affinity", () -> {
            HWND hwnd = hwnd(w);
            if (hwnd == null) return -1;
            com.sun.jna.ptr.IntByReference ref = new com.sun.jna.ptr.IntByReference();
            return Affinity.INSTANCE.GetWindowDisplayAffinity(hwnd, ref) ? ref.getValue() : -1;
        }, -1);
    }

    /**
     * Makes {@code w} (already visible) click-through, non-activating and topmost, and excludes it
     * from screen capture.
     *
     * @return true if the window is excluded from screen capture
     */
    public static boolean configure(Window w) {
        if (!Os.WINDOWS) return false;
        return Os.tryNative("overlay window styles", () -> {
            HWND hwnd = hwnd(w);
            if (hwnd == null) return false;
            int ex = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE,
                    ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST);
            User32.INSTANCE.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            return applyExclusion(hwnd);
        }, false);
    }

    /**
     * Sets WDA_EXCLUDEFROMCAPTURE and verifies it by reading it back. AWT realizes a translucent
     * window's native peer partly asynchronously, so the first call can land before the HWND is
     * fully ready; we retry a few times and confirm with GetWindowDisplayAffinity. This exclusion
     * is what keeps the annotations off Zoom/Meet/Teams/OBS screen shares and our own capture,
     * while the local user still sees them.
     */
    private static boolean applyExclusion(HWND hwnd) {
        int lastErr = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            boolean set = Affinity.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
            if (!set) lastErr = Native.getLastError();
            com.sun.jna.ptr.IntByReference ref = new com.sun.jna.ptr.IntByReference();
            if (Affinity.INSTANCE.GetWindowDisplayAffinity(hwnd, ref) && ref.getValue() == WDA_EXCLUDEFROMCAPTURE) {
                return true; // confirmed excluded
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.warn("Could not exclude the annotation overlay from screen capture "
                + "(SetWindowDisplayAffinity, needs Windows 10 2004+); last error " + lastErr);
        return false;
    }

    /** Toggles click-through (WS_EX_TRANSPARENT) on Windows. */
    public static void setClickThrough(Window w, boolean on) {
        if (!Os.WINDOWS) return;
        Os.tryNative("overlay click-through", () -> {
            HWND hwnd = hwnd(w);
            if (hwnd == null) return;
            int ex = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, on ? ex | WS_EX_TRANSPARENT : ex & ~WS_EX_TRANSPARENT);
        });
    }

    public static boolean isCaptureExclusionSupported() {
        if (!Os.WINDOWS) return false;
        // Windows 10 build 19041 (2004) or later; os.version is "10.0" on all Win10/11, so ask the build.
        String build = Os.tryNative("windows build", () -> com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber"), "0");
        try {
            return Integer.parseInt(build.trim()) >= 19041;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static HWND hwnd(Window w) {
        Pointer p = Native.getWindowPointer(w);
        return p == null ? null : new HWND(p);
    }
}
