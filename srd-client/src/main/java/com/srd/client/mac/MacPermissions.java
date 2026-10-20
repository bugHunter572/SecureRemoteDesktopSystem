package com.srd.client.mac;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Library;
import com.sun.jna.Native;

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
    }

    private interface ApplicationServices extends Library {
        ApplicationServices INSTANCE = Native.load("ApplicationServices", ApplicationServices.class);

        boolean AXIsProcessTrusted();
    }

    public static boolean screenRecordingGranted() {
        if (!Os.MAC) return true;
        return Os.tryNative("CGPreflightScreenCaptureAccess",
                () -> CoreGraphics.INSTANCE.CGPreflightScreenCaptureAccess(), false);
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
