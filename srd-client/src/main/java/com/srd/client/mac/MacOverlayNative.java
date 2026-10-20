package com.srd.client.mac;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.sun.jna.Function;
import com.sun.jna.Pointer;

/**
 * macOS equivalent of the Windows overlay tweaks in {@code OverlayNative}, done through the
 * Objective-C runtime (libobjc) via JNA. Windows has {@code WS_EX_TRANSPARENT} and
 * {@code WDA_EXCLUDEFROMCAPTURE}; macOS exposes two levers on {@code NSWindow}:
 *
 * <ul>
 *   <li><b>{@code setIgnoresMouseEvents:YES}</b> — makes the annotation overlay click-through, so
 *       it never steals the broadcaster's mouse or focus. This is <b>reliable</b> and is the fix
 *       for "the broadcaster can't do anything while someone is drawing".</li>
 *   <li><b>{@code setSharingType:NSWindowSharingNone}</b> — asks macOS to keep the window out of
 *       capture. This is <b>best-effort only</b>: it hides the overlay from window-targeted
 *       capture (Zoom/Meet "share a window"), but macOS has no app-facing equivalent to Windows'
 *       {@code WDA_EXCLUDEFROMCAPTURE}, so a full-screen ("share entire screen") capture can still
 *       include it. Callers must not claim guaranteed stealth on macOS.</li>
 * </ul>
 *
 * <p>We don't try to map a specific AWT {@code Window} to its {@code NSWindow} (that pointer isn't
 * reliably exposed by the JDK). Instead we sweep {@code [NSApp windows]} and apply the tweaks to
 * every borderless, floating (above-normal level) window — which, in this app, is exactly the
 * annotation overlay windows. Anything else borderless (tooltips) is unaffected in practice.
 *
 * <p>Every call is guarded by {@link Os#tryNative}; a failure degrades silently and never throws.
 */
public final class MacOverlayNative {
    private static final long NS_WINDOW_STYLE_MASK_BORDERLESS = 0L;
    private static final long NS_WINDOW_SHARING_NONE = 0L;

    private MacOverlayNative() {
    }

    // libobjc entry points. Resolved lazily and cached; if libobjc is missing these throw and
    // tryNative swallows it.
    private static final class ObjC {
        static final Function MSG_SEND = Function.getFunction("objc", "objc_msgSend");
        static final Function GET_CLASS = Function.getFunction("objc", "objc_getClass");
        static final Function SEL = Function.getFunction("objc", "sel_registerName");

        static Pointer cls(String name) {
            return GET_CLASS.invokePointer(new Object[] {name});
        }

        static Pointer sel(String name) {
            return SEL.invokePointer(new Object[] {name});
        }

        static Pointer msgPtr(Pointer receiver, String selector, Object... args) {
            return MSG_SEND.invokePointer(prepend(receiver, sel(selector), args));
        }

        static long msgLong(Pointer receiver, String selector, Object... args) {
            return MSG_SEND.invokeLong(prepend(receiver, sel(selector), args));
        }

        static void msgVoid(Pointer receiver, String selector, Object... args) {
            MSG_SEND.invokeVoid(prepend(receiver, sel(selector), args));
        }

        private static Object[] prepend(Pointer receiver, Pointer selector, Object[] args) {
            Object[] all = new Object[2 + args.length];
            all[0] = receiver;
            all[1] = selector;
            System.arraycopy(args, 0, all, 2, args.length);
            return all;
        }
    }

    /**
     * Makes every annotation overlay window click-through and best-effort private to capture.
     * Safe to call repeatedly (idempotent) and whenever overlay windows appear.
     *
     * @return the number of overlay windows configured, or -1 if the native call failed entirely
     */
    public static int configureOverlays() {
        if (!Os.MAC) return -1;
        return Os.tryNative("macOS overlay NSWindow tweaks", () -> {
            Pointer app = ObjC.msgPtr(ObjC.cls("NSApplication"), "sharedApplication");
            if (app == null) return -1;
            Pointer windows = ObjC.msgPtr(app, "windows");
            if (windows == null) return 0;
            long count = ObjC.msgLong(windows, "count");
            int configured = 0;
            for (long i = 0; i < count; i++) {
                Pointer win = ObjC.msgPtr(windows, "objectAtIndex:", i);
                if (win == null) continue;
                long styleMask = ObjC.msgLong(win, "styleMask");
                long level = ObjC.msgLong(win, "level");
                boolean visible = (ObjC.msgLong(win, "isVisible") & 0xFF) != 0;
                // Our overlays are the only borderless, above-normal-level (always-on-top) windows.
                if (styleMask != NS_WINDOW_STYLE_MASK_BORDERLESS || level == 0 || !visible) continue;
                ObjC.msgVoid(win, "setIgnoresMouseEvents:", 1);          // reliable: click-through
                ObjC.msgVoid(win, "setSharingType:", NS_WINDOW_SHARING_NONE); // best-effort: hide from capture
                configured++;
            }
            if (configured > 0) {
                Log.info("macOS annotation overlay: made " + configured + " window(s) click-through; "
                        + "setSharingType=none (best-effort — full-screen shares may still include it).");
            }
            return configured;
        }, -1);
    }
}
