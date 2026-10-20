package com.srd.client.audio;

/**
 * Thread-safe ring buffer of interleaved 48 kHz stereo s16 samples, counted in stereo
 * <em>frames</em> (one frame = 2 shorts). One capture thread writes, the mixer thread reads.
 *
 * <p>When a write does not fit, the oldest frames are overwritten: for live audio, the newest
 * data matters most. The mixer additionally trims the FIFO (see {@link #trimTo}) to correct clock
 * drift between a capture device and the mixer's 20 ms timer.
 */
public final class PcmFifo {
    public static final int CHANNELS = 2;

    private final short[] buf;
    private final int capacityFrames;
    private int readPos;     // index into buf (in shorts)
    private int sizeFrames;
    private long overflowFrames;

    // Carry for a partial frame from writeLE (byte streams may split frames anywhere).
    private final byte[] carry = new byte[4];
    private int carryLen;

    /** @param capacityFrames maximum frames held (e.g. 48000 for one second) */
    public PcmFifo(int capacityFrames) {
        if (capacityFrames <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacityFrames = capacityFrames;
        this.buf = new short[capacityFrames * CHANNELS];
    }

    /** One second at 48 kHz. */
    public PcmFifo() {
        this(48000);
    }

    public int capacity() {
        return capacityFrames;
    }

    /** Appends {@code frames} interleaved stereo frames from {@code src[offset...]}. */
    public synchronized void write(short[] src, int offset, int frames) {
        if (frames <= 0) return;
        if (frames > capacityFrames) {
            // Only the newest capacity frames can survive anyway.
            int skip = frames - capacityFrames;
            overflowFrames += skip;
            offset += skip * CHANNELS;
            frames = capacityFrames;
        }
        int free = capacityFrames - sizeFrames;
        if (frames > free) {
            int drop = frames - free;
            dropOldest(drop);
            overflowFrames += drop;
        }
        int writePos = (readPos + sizeFrames * CHANNELS) % buf.length;
        int samples = frames * CHANNELS;
        int first = Math.min(samples, buf.length - writePos);
        System.arraycopy(src, offset, buf, writePos, first);
        if (samples > first) System.arraycopy(src, offset + first, buf, 0, samples - first);
        sizeFrames += frames;
    }

    /**
     * Appends interleaved stereo s16le bytes. Partial frames at the end are kept and joined with
     * the next call, so byte streams may be split anywhere.
     */
    public void writeLE(byte[] src, int offset, int len) {
        if (len <= 0) return;
        short[] tmp;
        int frames;
        synchronized (this) {
            int total = carryLen + len;
            frames = total / 4;
            tmp = new short[frames * CHANNELS];
            int si = 0;
            int pos = offset;
            int end = offset + len;
            // Complete a carried partial frame first.
            if (carryLen > 0) {
                if (frames == 0) {
                    System.arraycopy(src, offset, carry, carryLen, len);
                    carryLen += len;
                    return;
                }
                int need = 4 - carryLen;
                System.arraycopy(src, pos, carry, carryLen, need);
                pos += need;
                tmp[si++] = (short) ((carry[0] & 0xFF) | (carry[1] << 8));
                tmp[si++] = (short) ((carry[2] & 0xFF) | (carry[3] << 8));
                carryLen = 0;
            }
            while (pos + 4 <= end) {
                tmp[si++] = (short) ((src[pos] & 0xFF) | (src[pos + 1] << 8));
                tmp[si++] = (short) ((src[pos + 2] & 0xFF) | (src[pos + 3] << 8));
                pos += 4;
            }
            int rest = end - pos;
            if (rest > 0) {
                System.arraycopy(src, pos, carry, 0, rest);
                carryLen = rest;
            }
            write(tmp, 0, si / CHANNELS);
        }
    }

    /**
     * Reads up to {@code frames} frames into {@code out} (interleaved, from index 0).
     *
     * @return number of frames actually read (may be less than requested, never negative)
     */
    public synchronized int read(int frames, short[] out) {
        int n = Math.min(Math.min(frames, sizeFrames), out.length / CHANNELS);
        if (n <= 0) return 0;
        int samples = n * CHANNELS;
        int first = Math.min(samples, buf.length - readPos);
        System.arraycopy(buf, readPos, out, 0, first);
        if (samples > first) System.arraycopy(buf, 0, out, first, samples - first);
        readPos = (readPos + samples) % buf.length;
        sizeFrames -= n;
        return n;
    }

    /** Frames currently buffered. */
    public synchronized int available() {
        return sizeFrames;
    }

    /**
     * Drops the <em>oldest</em> frames so that at most {@code frames} remain.
     *
     * @return number of frames dropped
     */
    public synchronized int trimTo(int frames) {
        int keep = Math.max(0, frames);
        if (sizeFrames <= keep) return 0;
        int drop = sizeFrames - keep;
        dropOldest(drop);
        return drop;
    }

    public synchronized void clear() {
        readPos = 0;
        sizeFrames = 0;
        carryLen = 0;
    }

    /** Total frames lost because the FIFO was full when written. */
    public synchronized long overflowFrames() {
        return overflowFrames;
    }

    private void dropOldest(int frames) {
        int n = Math.min(frames, sizeFrames);
        readPos = (readPos + n * CHANNELS) % buf.length;
        sizeFrames -= n;
    }
}
