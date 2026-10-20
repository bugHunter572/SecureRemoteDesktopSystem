package com.srd.client.input;

import com.srd.common.input.InputEvent;
import java.awt.Rectangle;

/**
 * Applies viewer input to the host. Normalized pointer coordinates refer to the streamed region:
 * {@code physical} (Windows virtual-desktop device pixels) or {@code logical} (Robot/AWT space).
 * Each implementation uses whichever matches its injection API.
 */
public interface InputInjector {
    /**
     * @param controller identity of the sending connection; keys/buttons are tracked per
     *                   controller so {@link #releaseAll(Object)} only releases what that viewer
     *                   pressed
     */
    void inject(Object controller, InputEvent e, Rectangle physical, Rectangle logical);

    /** Releases every key and button {@code controller} still holds (disconnect, control off). */
    void releaseAll(Object controller);

    String name();
}
