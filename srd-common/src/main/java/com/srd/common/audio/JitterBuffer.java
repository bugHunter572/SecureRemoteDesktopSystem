package com.srd.common.audio;

import java.util.ArrayDeque;

/**
 * Adaptive playout buffer of fixed-size PCM frames.
 *
 * <p>This fixes the old "audio cuts out and comes back" playback. The old players started the
 * device empty and fed it one chunk at a time, so any network jitter longer than one chunk
 * caused an audible gap. Here:
 * <ul>
 *   <li>playback starts only after {@code target} frames are queued (prefill);</li>
 *   <li>on underrun it returns a faded copy of the last frame once (concealment), then silence,
 *       and re-enters prefill. The caller keeps the device running on silence; it never stops
 *       the line;</li>
 *   <li>each underrun raises the target by one frame (max {@link #MAX_TARGET}), and 20 s without
 *       an underrun lowers it again, so latency follows the actual network;</li>
 *   <li>if more than target+3 frames pile up (clock drift, a burst after a stall), the oldest
 *       are dropped so latency can't creep up.</li>
 * </ul>
 * Thread-safe: one producer (network) and one consumer (playback).
 */
public final class JitterBuffer {
    public static final int MIN_TARGET = 2;
    public static final int MAX_TARGET = 8;
    private static final long RELAX_AFTER_MS = 20_000;

    private final int frameBytes;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final byte[] silence;
    private byte[] last;
    private boolean prefilling = true;
    private boolean concealed;
    private int target = 3;
    private long lastUnderrunMs = System.currentTimeMillis();
    private long underruns;
    private long dropped;

    public JitterBuffer(int frameBytes) {
        this.frameBytes = frameBytes;
        this.silence = new byte[frameBytes];
    }

    public synchronized void offer(byte[] frame) {
        if (frame.length != frameBytes) return;
        frames.addLast(frame);
        while (frames.size() > target + 3) {
            frames.pollFirst();
            dropped++;
        }
        notifyAll();
    }

    /**
     * Next frame to play. Never null: returns concealment/silence while starved or prefilling.
     * Waits up to {@code waitMs} for data while prefilling, to avoid emitting needless silence.
     */
    public synchronized byte[] poll(long waitMs) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (target > MIN_TARGET && now - lastUnderrunMs > RELAX_AFTER_MS) {
            target--;
            lastUnderrunMs = now;
        }
        if (prefilling) {
            if (frames.size() < target && waitMs > 0) wait(waitMs);
            if (frames.size() < target) return silence;
            prefilling = false;
        }
        byte[] f = frames.pollFirst();
        if (f != null) {
            last = f;
            concealed = false;
            return f;
        }
        // Underrun.
        underruns++;
        lastUnderrunMs = now;
        if (target < MAX_TARGET) target++;
        prefilling = true;
        if (!concealed && last != null) {
            concealed = true;
            return fadeOut(last);
        }
        return silence;
    }

    private static byte[] fadeOut(byte[] src) {
        byte[] out = new byte[src.length];
        int samples = src.length / 2;
        for (int i = 0; i < samples; i++) {
            int s = (short) ((src[2 * i] & 0xFF) | (src[2 * i + 1] << 8));
            s = (int) (s * (1.0 - (double) i / samples));
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    public synchronized void clear() {
        frames.clear();
        prefilling = true;
        last = null;
    }

    public synchronized int bufferedFrames() {
        return frames.size();
    }

    public synchronized int targetFrames() {
        return target;
    }

    public synchronized long underruns() {
        return underruns;
    }

    public synchronized long dropped() {
        return dropped;
    }
}
