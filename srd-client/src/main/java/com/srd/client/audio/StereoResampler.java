package com.srd.client.audio;

import com.srd.common.audio.Pcm;

/**
 * Streaming linear-interpolation resampler: stereo float frames (s16 scale) at any input rate to
 * 48 kHz stereo s16. Keeps the fractional phase and the last input frame across calls, so
 * packet boundaries cause no clicks and no rate error (unlike resampling each chunk separately).
 * Not thread-safe: one instance per capture thread.
 */
final class StereoResampler {
    static final int OUT_RATE = 48000;

    private final int inRate;
    private final double step;      // input frames per output frame
    private double t;               // next output position, in input frames relative to block start
    private float prevL, prevR;     // last input frame of the previous block (index -1)
    private boolean hasPrev;
    private short[] out = new short[0];

    StereoResampler(int inRate) {
        if (inRate <= 0) throw new IllegalArgumentException("rate " + inRate);
        this.inRate = inRate;
        this.step = (double) inRate / OUT_RATE;
    }

    int inRate() {
        return inRate;
    }

    void reset() {
        t = 0;
        hasPrev = false;
    }

    /**
     * Converts {@code frames} interleaved stereo frames from {@code in} and appends the result to
     * {@code fifo}.
     */
    void process(float[] in, int frames, PcmFifo fifo) {
        int n = process(in, frames);
        fifo.write(out, 0, n);
    }

    /** Converts; the result is in {@link #output()} and the return value is the frame count. */
    int process(float[] in, int frames) {
        if (frames <= 0) return 0;
        if (inRate == OUT_RATE) {
            ensure(frames);
            for (int i = 0; i < frames * 2; i++) out[i] = (short) Pcm.clip(Math.round(in[i]));
            return frames;
        }
        if (!hasPrev) {
            prevL = in[0];
            prevR = in[1];
            hasPrev = true;
            t = 0;
        }
        int maxOut = (int) ((frames + 1) / step) + 2;
        ensure(maxOut);
        int o = 0;
        // Valid positions: t in [-1, frames-1); index -1 is the previous block's last frame.
        while (t < frames - 1) {
            int i = (int) Math.floor(t);
            double frac = t - i;
            float aL, aR;
            if (i < 0) {
                aL = prevL;
                aR = prevR;
            } else {
                aL = in[2 * i];
                aR = in[2 * i + 1];
            }
            float bL = in[2 * (i + 1)];
            float bR = in[2 * (i + 1) + 1];
            out[2 * o] = (short) Pcm.clip((int) Math.round(aL + (bL - aL) * frac));
            out[2 * o + 1] = (short) Pcm.clip((int) Math.round(aR + (bR - aR) * frac));
            o++;
            t += step;
        }
        t -= frames;
        prevL = in[2 * (frames - 1)];
        prevR = in[2 * (frames - 1) + 1];
        return o;
    }

    short[] output() {
        return out;
    }

    private void ensure(int frames) {
        if (out.length < frames * 2) out = new short[frames * 2];
    }
}
