package com.srd.server.recording;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Records the server's microphone to a raw 48 kHz stereo s16le file (the v2 "mix server mic"
 * track of peer recordings, previously 16 kHz mono big-endian). Samples captured before the
 * recording's first video frame are discarded so the track starts in sync with the video.
 */
final class MicCapture {
    private final File out;
    private final LongSupplier zeroNanos;
    private volatile boolean running = true;
    private volatile TargetDataLine line;
    private final Thread thread;

    private MicCapture(File out, LongSupplier zeroNanos, String name) {
        this.out = out;
        this.zeroNanos = zeroNanos;
        this.thread = Threads.start(name, this::loop);
    }

    static MicCapture start(File out, LongSupplier zeroNanos, String name) {
        return new MicCapture(out, zeroNanos, name);
    }

    private void loop() {
        TargetDataLine l = null;
        boolean mono = false;
        try {
            l = open(AudioPacket.FORMAT);
            if (l == null) {
                l = open(new AudioFormat(AudioPacket.SAMPLE_RATE, 16, 1, true, false));
                mono = l != null;
            }
            if (l == null) {
                Log.info("Mic capture unavailable (no 48 kHz input line); recording without server mic");
                return;
            }
            line = l;
            l.start();
            if (!running) return;
            File dir = out.getParentFile();
            if (dir != null) dir.mkdirs();
            byte[] buf = new byte[mono ? AudioPacket.FRAME_BYTES / 2 : AudioPacket.FRAME_BYTES];
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
                while (running) {
                    int n = l.read(buf, 0, buf.length);
                    if (n <= 0 || zeroNanos.getAsLong() == 0) continue;
                    if (mono) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        os.write(Pcm.toStereo48k(chunk, AudioPacket.SAMPLE_RATE, 1));
                    } else {
                        os.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            if (running) Log.warn("Mic capture failed: " + e);
        } finally {
            if (l != null) {
                try {
                    l.stop();
                    l.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static TargetDataLine open(AudioFormat fmt) {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) return null;
            TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(fmt, AudioPacket.FRAME_BYTES * 10);
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    void stop() {
        running = false;
        TargetDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (Exception ignored) {
            }
        }
    }

    void join(long ms) {
        try {
            thread.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
