package com.srd.client.capture;

import com.srd.common.video.FrameBuffer;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;

/**
 * Captures one fixed region of the desktop into a {@link FrameBuffer} of exactly
 * {@link #width()}×{@link #height()} (0x00RRGGBB, stride = width). Implementations are not
 * thread-safe: one streaming thread owns a capturer.
 */
public interface ScreenCapturer extends AutoCloseable {
    /** Result of one {@link #grab}. */
    final class Grab {
        /** Nothing changed (or the timeout elapsed); fb untouched. */
        public static final Grab NO_CHANGE = new Grab(false, null);
        /** Pixels were written, but the capturer can't tell where: diff everything. */
        public static final Grab UNKNOWN = new Grab(true, null);

        public final boolean changed;
        /** Changed areas in fb coordinates, or null = unknown (diff the whole frame). */
        public final List<Rectangle> dirty;

        public Grab(boolean changed, List<Rectangle> dirty) {
            this.changed = changed;
            this.dirty = dirty;
        }
    }

    int width();

    int height();

    /**
     * Waits up to {@code timeoutMs} for the screen to change, then writes the new pixels into
     * {@code fb}. Capturers without change notification (GDI, Robot) capture immediately.
     *
     * @throws CaptureLostException when the capturer must be recreated (resolution change,
     *         desktop switch / UAC / lock screen with DXGI ACCESS_LOST, …)
     */
    Grab grab(FrameBuffer fb, int timeoutMs) throws IOException;

    /** Short name for logs/stats, e.g. "DXGI", "GDI", "Robot". */
    String name();

    @Override
    void close();

    /** Thrown when the capture session is invalid and must be reopened. */
    class CaptureLostException extends IOException {
        public CaptureLostException(String msg) {
            super(msg);
        }
    }
}
