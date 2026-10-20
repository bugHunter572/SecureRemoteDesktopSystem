package com.srd.common.audio;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays a remote audio stream through the default output device.
 *
 * <p>One playback thread pulls 20 ms frames from a {@link JitterBuffer} and writes them to a
 * {@link SourceDataLine} opened with a small (~80 ms) device buffer. Blocking writes pace the
 * thread at the device clock. The line is <b>never</b> stopped or drained while the player
 * lives: when the network starves, silence is written instead. Stopping and restarting the line
 * was what made audio "keep resuming". If the device disappears (headset unplugged, default
 * device changed), the line is reopened on the new default after a short backoff.
 */
public final class AudioPlayer implements AutoCloseable {
    private static final int DEVICE_BUFFER_FRAMES = 4; // 80 ms

    private final String name;
    private final JitterBuffer jitter = new JitterBuffer(AudioPacket.FRAME_BYTES);
    private volatile boolean running = true;
    private volatile boolean muted;
    private volatile float volume = 1f;
    private volatile SourceDataLine line;
    private volatile String lastError;

    public AudioPlayer(String name) {
        this.name = name;
        Threads.start("audio-play-" + name, Thread.MAX_PRIORITY, this::loop);
    }

    /** Feeds one received packet. Non-48k-stereo packets are converted. */
    public void offer(AudioPacket p) {
        if (!running) return;
        byte[] pcm = p.pcm;
        if (p.sampleRate != AudioPacket.SAMPLE_RATE || p.channels != AudioPacket.CHANNELS) {
            pcm = Pcm.toStereo48k(pcm, p.sampleRate, p.channels);
        }
        // Re-slice into exact frames (senders always send whole frames; this is defensive).
        for (int off = 0; off + AudioPacket.FRAME_BYTES <= pcm.length; off += AudioPacket.FRAME_BYTES) {
            byte[] f = new byte[AudioPacket.FRAME_BYTES];
            System.arraycopy(pcm, off, f, 0, f.length);
            jitter.offer(f);
        }
    }

    public void setMuted(boolean m) {
        muted = m;
        if (m) jitter.clear();
    }

    public boolean isMuted() {
        return muted;
    }

    /** 0..2 linear gain. */
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(2f, v));
    }

    public int bufferedMs() {
        return jitter.bufferedFrames() * AudioPacket.FRAME_MS;
    }

    public long underruns() {
        return jitter.underruns();
    }

    public String lastError() {
        return lastError;
    }

    private SourceDataLine open() {
        try {
            SourceDataLine l = AudioSystem.getSourceDataLine(AudioPacket.FORMAT);
            l.open(AudioPacket.FORMAT, AudioPacket.FRAME_BYTES * DEVICE_BUFFER_FRAMES);
            l.start();
            lastError = null;
            Log.info("audio[" + name + "]: output opened (" + l.getBufferSize() + " B device buffer)");
            return l;
        } catch (Exception e) {
            if (!String.valueOf(e).equals(lastError)) Log.warn("audio[" + name + "]: cannot open output: " + e);
            lastError = String.valueOf(e);
            return null;
        }
    }

    private void loop() {
        byte[] scratch = new byte[AudioPacket.FRAME_BYTES];
        try {
            while (running) {
                SourceDataLine l = line;
                if (l == null || !l.isOpen()) {
                    l = open();
                    line = l;
                    if (l == null) {
                        Threads.sleepQuietly(1000);
                        jitter.clear();
                        continue;
                    }
                }
                byte[] frame = jitter.poll(AudioPacket.FRAME_MS);
                if (muted) {
                    frame = new byte[frame.length];
                } else {
                    float v = volume;
                    if (v != 1f) frame = Pcm.gain(frame, v, scratch);
                }
                try {
                    int n = l.write(frame, 0, frame.length);
                    if (n < frame.length && running) throw new IllegalStateException("short write " + n);
                } catch (Exception e) {
                    Log.warn("audio[" + name + "]: output failed (" + e + "), reopening");
                    closeLine(l);
                    line = null;
                    Threads.sleepQuietly(300);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeLine(line);
            line = null;
        }
    }

    private static void closeLine(SourceDataLine l) {
        if (l == null) return;
        try {
            l.stop();
        } catch (Exception ignored) {
        }
        try {
            l.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        jitter.clear();
        jitter.offer(new byte[AudioPacket.FRAME_BYTES]); // wake the poller
    }
}
