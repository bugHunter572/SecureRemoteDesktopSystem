package com.srd.common.input;

import java.util.function.Consumer;

/**
 * System-level keyboard capture for a focused viewer (Windows: a WH_KEYBOARD_LL hook). It
 * forwards keys Java never sees, such as Win, Alt+Tab, Alt+Esc and Ctrl+Esc, and swallows them
 * locally while active, the way a native remote-desktop viewer does.
 */
public interface KeyboardGrabber extends AutoCloseable {
    /** Activate only while the viewer window is focused and remote control is on. */
    void setActive(boolean active);

    boolean isActive();

    /** Receives KEY events (scancode + extended + vk), on the hook thread. */
    void setSink(Consumer<InputEvent> sink);

    @Override
    void close();
}
