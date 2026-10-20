package com.srd.common.audio;

/**
 * Small PCM helpers. Everything here works on interleaved signed 16-bit little-endian
 * unless the name says otherwise.
 */
public final class Pcm {
    private Pcm() {
    }

    public static short sampleLE(byte[] b, int i) {
        return (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
    }

    public static void putLE(byte[] b, int i, int s) {
        b[i] = (byte) s;
        b[i + 1] = (byte) (s >> 8);
    }

    /** Applies linear gain with clipping. Returns {@code scratch} if it fits, else a new array. */
    public static byte[] gain(byte[] in, float g, byte[] scratch) {
        byte[] out = scratch != null && scratch.length == in.length ? scratch : new byte[in.length];
        for (int i = 0; i + 1 < in.length; i += 2) {
            int s = Math.round(sampleLE(in, i) * g);
            putLE(out, i, clip(s));
        }
        return out;
    }

    public static int clip(int s) {
        return s > Short.MAX_VALUE ? Short.MAX_VALUE : s < Short.MIN_VALUE ? Short.MIN_VALUE : s;
    }

    /**
     * Soft-knee limiter for mixed samples: transparent below about 75% of full scale, then
     * compresses smoothly instead of hard-clipping when several loud sources add up.
     */
    public static int softClip(float s) {
        float a = Math.abs(s);
        final float knee = 24576f;
        if (a > knee) {
            float over = a - knee;
            a = knee + over / (1f + over / (32767f - knee));
        }
        return clip(Math.round(Math.copySign(a, s)));
    }

    /** Converts s16le PCM at any rate/channel count to 48 kHz stereo s16le (linear interpolation). */
    public static byte[] toStereo48k(byte[] pcm, int rate, int channels) {
        if (channels <= 0 || rate <= 0) return new byte[0];
        int inFrames = pcm.length / (2 * channels);
        if (inFrames == 0) return new byte[0];
        float[] l = new float[inFrames];
        float[] r = new float[inFrames];
        for (int f = 0; f < inFrames; f++) {
            int base = f * channels * 2;
            l[f] = sampleLE(pcm, base);
            r[f] = channels > 1 ? sampleLE(pcm, base + 2) : l[f];
        }
        int outFrames = (int) ((long) inFrames * AudioPacket.SAMPLE_RATE / rate);
        byte[] out = new byte[outFrames * 4];
        double step = (double) rate / AudioPacket.SAMPLE_RATE;
        for (int o = 0; o < outFrames; o++) {
            double pos = o * step;
            int i = (int) pos;
            double frac = pos - i;
            int j = Math.min(i + 1, inFrames - 1);
            putLE(out, o * 4, clip((int) Math.round(l[i] + (l[j] - l[i]) * frac)));
            putLE(out, o * 4 + 2, clip((int) Math.round(r[i] + (r[j] - r[i]) * frac)));
        }
        return out;
    }
}
