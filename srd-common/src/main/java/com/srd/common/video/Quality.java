package com.srd.common.video;

import com.srd.common.protocol.StreamRequest;

/** Encoder tuning for one stream preset. */
public final class Quality {
    /** JPEG quality while a region keeps changing. */
    public final float jpeg;
    /** JPEG quality used when a region has settled (refinement pass). */
    public final float refineJpeg;
    /** Max distinct colors for a tile to use the lossless palette encoding. */
    public final int maxPaletteColors;
    /** Re-send a region at {@link #refineJpeg} once unchanged for this long (0 = never). */
    public final int refineAfterMs;

    public Quality(float jpeg, float refineJpeg, int maxPaletteColors, int refineAfterMs) {
        this.jpeg = jpeg;
        this.refineJpeg = refineJpeg;
        this.maxPaletteColors = maxPaletteColors;
        this.refineAfterMs = refineAfterMs;
    }

    public static Quality forPreset(int preset) {
        switch (preset) {
            case StreamRequest.PRESET_SPEED:
                return new Quality(0.45f, 0.75f, 24, 500);
            case StreamRequest.PRESET_QUALITY:
                return new Quality(0.80f, 0.92f, 256, 250);
            default:
                return new Quality(0.62f, 0.88f, 96, 300);
        }
    }
}
