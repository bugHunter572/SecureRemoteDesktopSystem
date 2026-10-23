package com.srd.client.mac;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * macOS privacy permissions (TCC). Remote control and screen sharing silently do nothing on
 * macOS until the app is granted:
 * <ul>
 *   <li><b>Screen Recording</b> (macOS 10.15+) — to capture the screen. Checked with
 *       {@code CGPreflightScreenCaptureAccess}, requested with {@code CGRequestScreenCaptureAccess}
 *       (shows the system prompt the first time).</li>
 *   <li><b>Accessibility</b> — to inject mouse/keyboard. Checked with {@code AXIsProcessTrusted}.
 *       There is no auto-prompt API without Core Foundation, so we open the settings pane and
 *       guide the user.</li>
 * </ul>
 * All calls are guarded for non-macOS and never throw.
 */
public final class MacPermissions {
    private MacPermissions() {
    }

    private interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        boolean CGPreflightScreenCaptureAccess();

        boolean CGRequestScreenCaptureAccess();

        Pointer CGWindowListCopyWindowInfo(int option, int relativeToWindow);
    }

    private interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);
        int UTF8 = 0x08000100;
        int SINT32 = 3;

        long CFArrayGetCount(Pointer array);

        Pointer CFArrayGetValueAtIndex(Pointer array, long index);

        Pointer CFDictionaryGetValue(Pointer dict, Pointer key);

        Pointer CFStringCreateWithCString(Pointer alloc, String s, int encoding);

        long CFStringGetLength(Pointer s);

        boolean CFNumberGetValue(Pointer number, int type, IntByReference out);

        void CFRelease(Pointer cf);
    }

    private interface ApplicationServices extends Library {
        ApplicationServices INSTANCE = Native.load("ApplicationServices", ApplicationServices.class);

        boolean AXIsProcessTrusted();
    }

    /**
     * Whether Screen Recording is in effect for this process. {@code CGPreflightScreenCaptureAccess}
     * answers once per process (a grant made while running reads false until a restart), so a
     * live test is added: macOS reveals other apps' window titles only to processes allowed to
     * record the screen.
     */
    public static boolean screenRecordingGranted() {
        if (!Os.MAC) return true;
        boolean preflight = Os.tryNative("CGPreflightScreenCaptureAccess",
                () -> CoreGraphics.INSTANCE.CGPreflightScreenCaptureAccess(), false);
        return preflight || otherWindowTitlesVisible();
    }

    /** True if any normal window of another app reports its title (only visible with Screen Recording). */
    static boolean otherWindowTitlesVisible() {
        return Os.tryNative("window title check", () -> {
            CoreFoundation cf = CoreFoundation.INSTANCE;
            // kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements
            Pointer list = CoreGraphics.INSTANCE.CGWindowListCopyWindowInfo(1 | 16, 0);
            if (list == null) return false;
            Pointer kPid = cf.CFStringCreateWithCString(null, "kCGWindowOwnerPID", CoreFoundation.UTF8);
            Pointer kName = cf.CFStringCreateWithCString(null, "kCGWindowName", CoreFoundation.UTF8);
            Pointer kLayer = cf.CFStringCreateWithCString(null, "kCGWindowLayer", CoreFoundation.UTF8);
            try {
                int self = (int) ProcessHandle.current().pid();
                long n = cf.CFArrayGetCount(list);
                IntByReference v = new IntByReference();
                for (long i = 0; i < n; i++) {
                    Pointer w = cf.CFArrayGetValueAtIndex(list, i);
                    Pointer pid = cf.CFDictionaryGetValue(w, kPid);
                    Pointer layer = cf.CFDictionaryGetValue(w, kLayer);
                    if (pid == null || !cf.CFNumberGetValue(pid, CoreFoundation.SINT32, v) || v.getValue() == self) continue;
                    if (layer == null || !cf.CFNumberGetValue(layer, CoreFoundation.SINT32, v) || v.getValue() != 0) continue;
                    Pointer name = cf.CFDictionaryGetValue(w, kName);
                    if (name != null && cf.CFStringGetLength(name) > 0) return true;
                }
                return false;
            } finally {
                cf.CFRelease(kPid);
                cf.CFRelease(kName);
                cf.CFRelease(kLayer);
                cf.CFRelease(list);
            }
        }, false);
    }

    /** Shows the system Screen Recording prompt the first time; returns whether already granted. */
    public static boolean requestScreenRecording() {
        if (!Os.MAC) return true;
        return Os.tryNative("CGRequestScreenCaptureAccess",
                () -> CoreGraphics.INSTANCE.CGRequestScreenCaptureAccess(), false);
    }

    public static boolean accessibilityGranted() {
        if (!Os.MAC) return true;
        return Os.tryNative("AXIsProcessTrusted",
                () -> ApplicationServices.INSTANCE.AXIsProcessTrusted(), false);
    }

    /** Opens System Settings at the Screen Recording privacy pane. */
    public static void openScreenRecordingSettings() {
        openPane("Privacy_ScreenCapture");
    }

    /** Opens System Settings at the Accessibility privacy pane. */
    public static void openAccessibilitySettings() {
        openPane("Privacy_Accessibility");
    }

    private static void openPane(String anchor) {
        if (!Os.MAC) return;
        try {
            new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?" + anchor).start();
        } catch (Exception e) {
            Log.warn("Could not open Settings pane " + anchor + ": " + e);
        }
    }
}
