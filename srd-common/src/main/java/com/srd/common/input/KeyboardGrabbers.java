package com.srd.common.input;

import com.srd.common.util.Os;

/** Factory for the platform's system-keyboard grabber, if any. */
public final class KeyboardGrabbers {
    private KeyboardGrabbers() {
    }

    /**
     * @return a {@link WindowsKeyboardHook} on Windows (the hook thread starts on the first
     *         {@code setActive(true)}), or {@code null} where system-key capture isn't available
     *         (macOS, Linux, or a broken JNA setup)
     */
    public static KeyboardGrabber create() {
        if (!Os.WINDOWS) return null;
        return Os.tryNative("keyboard hook", WindowsKeyboardHook::new, null);
    }
}
