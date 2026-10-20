package com.srd.client.audio;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Captures a Java Sound {@link TargetDataLine}: the best microphone, or a named device such as
 * BlackHole used as the system-audio source.
 *
 * <p>Microphone mode skips loopback-like devices ("Stereo Mix", BlackHole and so on), so system
 * audio is not captured twice when a real loopback source is active. The line is opened in the
 * best 16-bit PCM format it offers (48 kHz stereo/mono preferred) with a ~100 ms buffer. It is
 * read in 10 ms chunks on its own thread and converted to 48 kHz stereo (big-endian is
 * byte-swapped; rates are resampled with phase kept across chunks).
 *
 * <p>Self-healing: if the line dies (read returns -1, an exception, or no data for 3 s, which a
 * watchdog detects by closing the line to unblock the read), it is closed and reopened with
 * backoff (250 ms, 500 ms, 1 s, 2 s). Each reopen re-runs device selection, so a newly plugged
 * headset can be picked up.
 */
public final class JavaSoundMicSource implements AudioSource {
    private static final String[] MIC_WORDS = {"microphone", "mic", "headset", "array", "input", "capture", "line in"};
    private static final long[] BACKOFF_MS = {250, 500, 1000, 2000};
    private static final long STALL_NANOS = 3_000_000_000L;

    private final String fixedMixerName;   // null = auto-select a microphone
    private final Kind kind;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile TargetDataLine line;
    private volatile long lastDataNanos;
    private volatile String status;
    private ScheduledFuture<?> watchdog;
    private PcmFifo out;

    /** Auto-selects the best real microphone. */
    public JavaSoundMicSource() {
        this(null, Kind.MIC);
    }

    /**
     * @param mixerName exact Java Sound mixer name to capture (e.g. "BlackHole 2ch"); null = auto mic
     * @param kind      MIC or SYSTEM (selects which gain applies)
     */
    public JavaSoundMicSource(String mixerName, Kind kind) {
        this.fixedMixerName = mixerName;
        this.kind = kind;
        this.status = name() + " (starting)";
    }

    @Override
    public synchronized void start(PcmFifo fifo) {
        if (running) return;
        out = fifo;
        running = true;
        thread = Threads.start(kind == Kind.SYSTEM ? "audio-loopback-dev" : "audio-mic", Thread.MAX_PRIORITY, this::loop);
        watchdog = Threads.scheduler().scheduleWithFixedDelay(this::checkStall, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
            if (watchdog != null) watchdog.cancel(false);
            watchdog = null;
        }
        closeLine(line);       // unblocks a pending read
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String name() {
        return fixedMixerName != null ? "Java Sound '" + fixedMixerName + "'" : "microphone";
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int primeMs() {
        // DirectSound delivers steady 10 ms chunks; ALSA/CoreAudio lines can be burstier.
        return Os.WINDOWS ? 40 : 60;
    }

    // ------------------------------------------------------------------ capture thread

    private void loop() {
        int failures = 0;
        String lastError = null;
        while (running) {
            Opened o = null;
            try {
                o = open();
            } catch (Throwable t) {
                lastError = logOnce(lastError, "cannot open: " + t);
            }
            if (o == null) {
                if (lastError == null) lastError = logOnce(null, "no usable capture device");
                status = name() + " (unavailable, retrying)";
                if (!backoff(failures++)) break;
                continue;
            }
            line = o.line;
            lastDataNanos = System.nanoTime();
            status = o.description;
            Log.info("audio: " + name() + " opened: " + o.description);
            lastError = null;
            long openedAt = System.nanoTime();
            String err;
            try {
                err = capture(o);
            } catch (Throwable t) {
                err = "read failed: " + t;
            } finally {
                line = null;
                closeLine(o.line);
            }
            if (!running) break;
            if (err != null) {
                lastError = logOnce(lastError, err + "; reopening");
                status = name() + " (recovering: " + err + ")";
                // A line that ran fine for a while restarts the backoff; one that dies at once keeps growing it.
                if (System.nanoTime() - openedAt > 5_000_000_000L) failures = 0;
                if (!backoff(failures++)) break;
            }
        }
        status = name() + " (stopped)";
    }

    /** Reads until stopped (returns null) or the line dies (returns the reason). */
    private String capture(Opened o) {
        AudioFormat f = o.format;
        int ch = f.getChannels();
        int frameBytes = f.getFrameSize();
        int chunkFrames = Math.max(1, Math.round(f.getSampleRate() / 100f));   // 10 ms
        byte[] buf = new byte[chunkFrames * frameBytes];
        float[] stereo = new float[chunkFrames * 2];
        StereoResampler rs = new StereoResampler(Math.round(f.getSampleRate()));
        boolean be = f.isBigEndian();
        int zeroReads = 0;
        while (running) {
            int n = o.line.read(buf, 0, buf.length);
            if (n < 0) return "line closed (read returned -1)";
            if (n == 0) {
                if (!o.line.isOpen()) return "line closed";
                if (++zeroReads > 200) return "line returns no data";
                Threads.sleepQuietly(5);
                continue;
            }
            zeroReads = 0;
            lastDataNanos = System.nanoTime();
            int frames = n / frameBytes;
            toStereoFloat(buf, frames, ch, be, stereo);
            rs.process(stereo, frames, out);
        }
        return null;
    }

    /** s16 (LE or BE), 1..n channels -> interleaved stereo float at s16 scale. */
    static void toStereoFloat(byte[] b, int frames, int ch, boolean bigEndian, float[] stereo) {
        int fb = ch * 2;
        for (int f = 0; f < frames; f++) {
            int base = f * fb;
            float l = sample(b, base, bigEndian);
            float r = ch > 1 ? sample(b, base + 2, bigEndian) : l;
            stereo[2 * f] = l;
            stereo[2 * f + 1] = r;
        }
    }

    private static float sample(byte[] b, int i, boolean bigEndian) {
        return bigEndian ? (short) ((b[i] << 8) | (b[i + 1] & 0xFF)) : (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
    }

    private void checkStall() {
        TargetDataLine l = line;
        if (!running || l == null) return;
        if (System.nanoTime() - lastDataNanos > STALL_NANOS) {
            Log.warn("audio: " + name() + " stalled (no data for 3 s), closing line to reopen");
            lastDataNanos = System.nanoTime();
            closeLine(l);
        }
    }

    private boolean backoff(int failures) {
        try {
            Thread.sleep(BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)]);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private String logOnce(String last, String msg) {
        if (!msg.equals(last)) Log.warn("audio: " + name() + ": " + msg);
        return msg;
    }

    // ------------------------------------------------------------------ device selection

    private static final class Opened {
        final TargetDataLine line;
        final AudioFormat format;
        final String description;

        Opened(TargetDataLine line, AudioFormat format, String description) {
            this.line = line;
            this.format = format;
            this.description = description;
        }
    }

    private Opened open() {
        for (Mixer.Info info : candidates()) {
            if (!running) return null;
            Opened o = tryOpen(info);
            if (o != null) return o;
        }
        return null;
    }

    /** Mixers to try, best first. */
    private List<Mixer.Info> candidates() {
        List<Mixer.Info> list = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (fixedMixerName != null) {
                if (info.getName().equals(fixedMixerName)) list.add(info);
                continue;
            }
            if (LoopbackDeviceDetector.isLoopbackName(info.getName() + " " + info.getDescription())) continue;
            if (!LoopbackDeviceDetector.hasCaptureLine(info)) continue;
            list.add(info);
        }
        list.sort(Comparator.comparingInt(JavaSoundMicSource::score).reversed());
        return list;
    }

    /** Higher is better: the OS default capture device first, then names that look like microphones. */
    static int score(Mixer.Info info) {
        String n = (info.getName() + " " + info.getDescription()).toLowerCase(Locale.ROOT);
        String name = info.getName().toLowerCase(Locale.ROOT);
        if (name.contains("primary sound capture")) return 100;            // Windows: follows default device
        if (name.equals("default") || name.startsWith("default [") || name.startsWith("default audio")) return 90;
        for (String w : MIC_WORDS) {
            if (n.contains(w)) return 50;
        }
        return 10;
    }

    private Opened tryOpen(Mixer.Info info) {
        Mixer mixer;
        try {
            mixer = AudioSystem.getMixer(info);
        } catch (Throwable t) {
            return null;
        }
        for (AudioFormat f : formats(mixer)) {
            DataLine.Info li = new DataLine.Info(TargetDataLine.class, f);
            try {
                if (!mixer.isLineSupported(li)) continue;
                TargetDataLine l = (TargetDataLine) mixer.getLine(li);
                int bytes100ms = Math.round(f.getSampleRate() / 10f) * f.getFrameSize();
                l.open(f, bytes100ms);
                l.start();
                return new Opened(l, l.getFormat(), info.getName() + " " + (int) f.getSampleRate() + "Hz/"
                        + f.getChannels() + "ch" + (f.isBigEndian() ? " BE" : ""));
            } catch (Throwable ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** Candidate 16-bit signed PCM formats, preferred first. */
    static List<AudioFormat> formats(Mixer mixer) {
        // AudioFormat has identity equals(); dedupe on a key instead.
        Set<String> seen = new LinkedHashSet<>();
        List<AudioFormat> out = new ArrayList<>();
        java.util.function.Consumer<AudioFormat> add = f -> {
            if (seen.add(f.getSampleRate() + "/" + f.getChannels() + "/" + f.isBigEndian())) out.add(f);
        };
        float[] rates = {48000f, 44100f};
        for (float r : rates) {
            for (int ch = 2; ch >= 1; ch--) {
                add.accept(new AudioFormat(r, 16, ch, true, false));
                add.accept(new AudioFormat(r, 16, ch, true, true));
            }
        }
        try {
            for (Line.Info li : mixer.getTargetLineInfo()) {
                if (!(li instanceof DataLine.Info)) continue;
                AudioFormat[] fs = ((DataLine.Info) li).getFormats();
                if (fs == null) continue;
                for (AudioFormat f : fs) {
                    if (f == null || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.getSampleSizeInBits() != 16) continue;
                    if (f.getChannels() < 1 || f.getChannels() > 2) continue;
                    if (f.getSampleRate() == AudioSystem.NOT_SPECIFIED || f.getSampleRate() <= 0) {
                        for (float r : new float[]{48000f, 44100f, 32000f, 16000f}) {
                            add.accept(new AudioFormat(r, 16, f.getChannels(), true, f.isBigEndian()));
                        }
                    } else {
                        add.accept(new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, f.isBigEndian()));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void closeLine(TargetDataLine l) {
        if (l == null) return;
        try {
            l.stop();
        } catch (Throwable ignored) {
        }
        try {
            l.close();
        } catch (Throwable ignored) {
        }
    }
}
