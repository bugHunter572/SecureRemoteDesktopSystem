package com.srd.common.input;

/**
 * Converts Java wheel events into 1/120-notch deltas without losing precision.
 *
 * <p>The old viewers sent {@code getWheelRotation() * getScrollAmount()}. That multiplied each
 * notch by the local "lines per notch" (usually 3) before the host's own OS applied its 3 lines
 * per notch again, so one notch scrolled 9 lines. Precision-touchpad sub-notch deltas also
 * truncated to 0 and then arrived in bursts. Here we use {@code getPreciseWheelRotation()},
 * scale to WHEEL_DELTA units, and carry the fractional remainder forward, so smooth touchpad
 * scrolling stays smooth and a mouse notch is exactly one notch on the host.
 */
public final class WheelAccumulator {
    private double remY;
    private double remX;

    /**
     * @param preciseRotation {@code MouseWheelEvent.getPreciseWheelRotation()} (positive = towards user / down)
     * @param horizontal      true for a horizontal scroll (Shift+wheel or a tilt wheel)
     * @return the event to send, or null if the accumulated delta is still below one unit
     */
    public InputEvent onWheel(double preciseRotation, boolean horizontal) {
        double units = -preciseRotation * InputEvent.WHEEL_DELTA;
        if (horizontal) {
            // Java reports "scroll right" as positive rotation with Shift; Windows HWHEEL positive = right.
            remX += -units;
            int whole = (int) remX;
            if (whole == 0) return null;
            remX -= whole;
            return InputEvent.wheel(0, whole);
        }
        remY += units;
        int whole = (int) remY;
        if (whole == 0) return null;
        remY -= whole;
        return InputEvent.wheel(whole, 0);
    }

    public void reset() {
        remX = 0;
        remY = 0;
    }
}
