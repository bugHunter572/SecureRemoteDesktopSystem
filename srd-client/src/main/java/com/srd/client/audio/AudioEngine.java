package com.srd.client.audio;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.sun.jna.Library;
import com.sun.jna.Native;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Process-wide host audio capture, shared by every consumer (server connections, peer viewers).
 *
 * <h2>Why it is built like this</h2>
 * The old capture loop had several faults that made audio "keep dropping out and coming back":
 * <ul>
 *   <li>Audio was global, so any consumer's STOP killed it for everyone. Here capture is
 *       <b>reference-counted</b>: it runs while at least one {@link #subscribe subscriber}
 *       exists.</li>
 *   <li>The microphone's blocking read drove the clock, and loopback audio was mixed in when it
 *       happened to be there, truncated to {@code min(len)}. The two device clocks drifted, the
 *       loopback queue overflowed or starved, and leftover bytes were thrown away. Here a
 *       <b>timer-driven mixer</b> ticks every 20 ms on {@link System#nanoTime()}. Each source
 *       fills its own {@link PcmFifo}. Every tick takes exactly 960 frames from each FIFO
 *       (padding with silence when a FIFO is short) and trims FIFOs that grow past ~120 ms
 *       back to their prime level (drift correction).</li>
 *   <li>Mixing averaged the sources ({@code sum/count}), so volume jumped up and down as sources
 *       came and went. Here sources are <b>summed</b> with per-source gain and then passed through
 *       {@link Pcm#softClip}.</li>
 *   <li>Sources now heal themselves: WASAPI reinitialises on device loss or a change of the
 *       default output device, and microphone lines reopen.</li>
 * </ul>
 * The output is 48 kHz stereo s16le in 20 ms {@link AudioPacket}s.
 *
 * <p>Threading: {@code subscribe}/{@code unsubscribe} only flip state under a short lock. Devices
 * are opened and closed on the mixer thread, so callers never wait on audio hardware. A stop
 * followed at once by a start creates a new session. That session first waits (on its own
 * thread) for the previous one to release its devices.
 */
public final class AudioEngine {
    static final int TICK_FRAMES = AudioPacket.SAMPLES_PER_FRAME;          // 960
    static final long TICK_NANOS = AudioPacket.FRAME_MS * 1_000_000L;     // 20 ms
    static final long RESYNC_NANOS = 100_000_000L;                         // 100 ms behind -> resync
    static final int HIGH_WATER_EXTRA_FRAMES = 48 * 80;                    // prime + 80 ms -> trim
    static final int FIFO_CAPACITY_FRAMES = 48000;                         // 1 s

    private static final AudioEngine INSTANCE = new AudioEngine();

    public static AudioEngine get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Map<Object, Consumer<byte[]>> subscribers = new IdentityHashMap<>(); // guarded by lock
    @SuppressWarnings("unchecked")
    private volatile Consumer<byte[]>[] sinks = new Consumer[0];
    private Session session;                                                     // guarded by lock
    private volatile Supplier<List<AudioSource>> sourceFactory = AudioEngine::createDefaultSources;
    /** Packet sequence numbers continue across sessions so receivers never see seq jump back. */
    private final AtomicInteger seqGen = new AtomicInteger();

    AudioEngine() {
    }

    // ------------------------------------------------------------------ public API

    /**
     * Registers {@code sink} to receive AUDIO message payloads ({@link AudioPacket#encode()}) every
     * 20 ms. Starts capture on the first subscriber. Subscribing again with the same consumer
     * (identity) replaces its sink. The payload array is shared between all sinks and must not
     * be modified. Sinks are called on the mixer thread and must not block.
     */
    public void subscribe(Object consumer, Consumer<byte[]> sink) {
        if (consumer == null || sink == null) throw new NullPointerException();
        synchronized (lock) {
            subscribers.put(consumer, sink);
            publishSinks();
            if (session == null || session.isDead()) startLocked();
        }
    }

    /** Removes the consumer; stops capture when it was the last one. Unknown consumers are ignored. */
    public void unsubscribe(Object consumer) {
        if (consumer == null) return;
        synchronized (lock) {
            if (subscribers.remove(consumer) == null) return;
            publishSinks();
            if (subscribers.isEmpty()) stopLocked();
        }
    }

    /** True while a capture session is active (at least one subscriber). */
    public boolean isRunning() {
        synchronized (lock) {
            return session != null && !session.isDead();
        }
    }

    public int subscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }

    /** One-line description of the active sources and their state, for logs and status UIs. */
    public String describeSources() {
        Session s;
        synchronized (lock) {
            s = session;
        }
        if (s == null) return "audio stopped";
        return s.describe();
    }

    /** Recreates the sources (e.g. after AudioConfig enable flags change) if capture is running. */
    public void restartSources() {
        synchronized (lock) {
            if (session == null) return;
            Log.info("audio: restarting sources (config changed)");
            stopLocked();
            startLocked();
        }
    }

    // ------------------------------------------------------------------ test hooks

    /** Replaces how sources are created (tests). Takes effect on the next session. */
    void setSourceFactory(Supplier<List<AudioSource>> factory) {
        sourceFactory = factory != null ? factory : AudioEngine::createDefaultSources;
    }

    /** Packets produced by the current session, or -1 when stopped (tests/probe). */
    long packetsProduced() {
        synchronized (lock) {
            return session == null ? -1 : session.seq;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("unchecked")
    private void publishSinks() {
        sinks = subscribers.values().toArray(new Consumer[0]);
    }

    private void startLocked() {
        Session prev = session;
        if (prev != null) prev.requestStop();
        session = new Session(prev, sourceFactory);
        session.start();
    }

    private void stopLocked() {
        Session s = session;
        session = null;
        if (s != null) s.requestStop();
    }

    // ------------------------------------------------------------------ default sources

    static List<AudioSource> createDefaultSources() {
        List<AudioSource> list = new ArrayList<>();
        if (AudioConfig.isSystemAudioEnabled()) {
            AudioSource sys = createSystemSource();
            if (sys != null) list.add(sys);
        }
        // macOS shares the computer's audio only; the microphone is never opened there.
        if (AudioConfig.isMicEnabled() && !Os.MAC) {
            list.add(new JavaSoundMicSource());
        }
        return list;
    }

    private static AudioSource createSystemSource() {
        try {
            if (Os.WINDOWS && WasapiLoopbackSource.isSupported()) return new WasapiLoopbackSource();
            if (Os.LINUX && PulseMonitorSource.isAvailable()) return new PulseMonitorSource();
            if (Os.MAC) {
                // The bundled ScreenCaptureKit helper; nothing to install. No loopback-device
                // fallback here: on macOS those are input devices and would need the microphone.
                AudioSource mac = MacSystemAudioSource.createIfAvailable();
                if (mac == null) {
                    Log.warn("audio: the bundled " + MacSystemAudioSource.HELPER + " helper was not found "
                            + "(run the app from SecureRemoteDesktopClient.app); no computer audio.");
                }
                return mac;
            }
        } catch (Throwable t) {
            Log.warn("audio: system-audio source unavailable: " + t);
            if (Os.MAC) return null;
        }
        // Fallback: a virtual loopback capture device (Stereo Mix, VB-Cable...), if one exists.
        AudioSource loop = LoopbackDeviceDetector.createSystemSource();
        if (loop != null) return loop;
        if (Os.LINUX) {
            Log.warn("audio: no system-audio source on Linux (need parec/pw-record from "
                    + "PulseAudio/PipeWire on PATH). Streaming microphone only.");
        } else {
            Log.warn("audio: no system-audio source available. Streaming microphone only.");
        }
        return null;
    }

    // ------------------------------------------------------------------ mixing (pure, testable)

    /** Mixer-side state of one source: its FIFO, gain and priming state. Mixer-thread only. */
    static final class MixInput {
        final PcmFifo fifo;
        final int primeFrames;
        final int highWaterFrames;
        volatile float gain;
        boolean primed;
        long underruns;
        long trimmedFrames;

        MixInput(PcmFifo fifo, float gain, int primeFrames) {
            this.fifo = fifo;
            this.gain = gain;
            this.primeFrames = Math.max(0, primeFrames);
            this.highWaterFrames = Math.max(48 * 120, this.primeFrames + HIGH_WATER_EXTRA_FRAMES);
        }
    }

    /** Convenience overload for tests: allocates scratch buffers. */
    static byte[] mixTick(MixInput[] inputs) {
        return mixTick(inputs, new float[TICK_FRAMES * 2], new short[TICK_FRAMES * 2]);
    }

    /**
     * One 20 ms mixer tick: takes exactly {@value #TICK_FRAMES} frames from every input (silence
     * for any shortfall or for inputs still priming), sums them with gain, soft-clips and returns
     * 3,840 bytes of s16le stereo. Inputs whose FIFO stays above their high-water mark after the
     * read are trimmed from the oldest end back to their prime level (clock-drift correction).
     */
    static byte[] mixTick(MixInput[] inputs, float[] acc, short[] scratch) {
        final int samples = TICK_FRAMES * 2;
        java.util.Arrays.fill(acc, 0, samples, 0f);
        for (MixInput in : inputs) {
            if (in == null) continue;
            if (!in.primed) {
                if (in.fifo.available() < in.primeFrames) continue;    // still buffering: silence
                in.primed = true;
            }
            int n = in.fifo.read(TICK_FRAMES, scratch);
            if (n < TICK_FRAMES) {
                // Underrun: use what there is, pad silence, and re-prime before reading again.
                in.primed = false;
                if (n > 0) in.underruns++;
            }
            float g = in.gain;
            int ns = n * 2;
            if (g == 1f) {
                for (int i = 0; i < ns; i++) acc[i] += scratch[i];
            } else if (g != 0f) {
                for (int i = 0; i < ns; i++) acc[i] += scratch[i] * g;
            }
            if (in.fifo.available() > in.highWaterFrames) {
                in.trimmedFrames += in.fifo.trimTo(in.primeFrames);
            }
        }
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) Pcm.putLE(pcm, 2 * i, Pcm.softClip(acc[i]));
        return pcm;
    }

    // ------------------------------------------------------------------ session

    private final class Session implements Runnable {
        private final Session prev;
        private final Supplier<List<AudioSource>> factory;
        private final CountDownLatch terminated = new CountDownLatch(1);
        private volatile boolean stopRequested;
        private volatile Thread thread;
        private volatile List<AudioSource> sources = new ArrayList<>();
        private volatile MixInput[] inputs = new MixInput[0];
        volatile long seq;
        private volatile long resyncs;

        Session(Session prev, Supplier<List<AudioSource>> factory) {
            this.prev = prev;
            this.factory = factory;
        }

        void start() {
            thread = Threads.start("audio-mixer", Thread.MAX_PRIORITY, this);
        }

        void requestStop() {
            stopRequested = true;
            Thread t = thread;
            if (t != null) LockSupport.unpark(t);
        }

        boolean isDead() {
            Thread t = thread;
            return stopRequested || (t != null && !t.isAlive() && terminated.getCount() == 0);
        }

        boolean awaitTerminated(long ms) {
            try {
                return terminated.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        String describe() {
            List<AudioSource> src = sources;
            MixInput[] in = inputs;
            if (src.isEmpty()) return stopRequested ? "audio stopping" : "audio running: no sources (silence)";
            StringBuilder sb = new StringBuilder("audio running: ");
            for (int i = 0; i < src.size(); i++) {
                if (i > 0) sb.append(" + ");
                AudioSource s = src.get(i);
                sb.append(s.kind() == AudioSource.Kind.SYSTEM ? "system[" : "mic[").append(s.status());
                if (i < in.length && in[i] != null) {
                    sb.append(", buf ").append(in[i].fifo.available() / 48).append("ms");
                    if (in[i].underruns > 0) sb.append(", underruns ").append(in[i].underruns);
                    if (in[i].trimmedFrames > 0) sb.append(", trimmed ").append(in[i].trimmedFrames / 48).append("ms");
                }
                sb.append(']');
            }
            if (resyncs > 0) sb.append(" (timer resyncs ").append(resyncs).append(')');
            return sb.toString();
        }

        @Override
        public void run() {
            boolean timerRaised = false;
            boolean opened = false;
            try {
                if (prev != null && !prev.awaitTerminated(3000)) {
                    Log.warn("audio: previous session still releasing devices; continuing");
                }
                if (stopRequested) return;
                openSources();
                opened = true;
                timerRaised = WinTimer.begin();
                Log.info("audio: capture started, " + describe());
                loop();
            } catch (Throwable t) {
                Log.error("audio: mixer session failed", t);
            } finally {
                closeSources();
                if (timerRaised) WinTimer.end();
                terminated.countDown();
                if (opened) Log.info("audio: capture stopped (" + seq + " packets)");
            }
        }

        private void openSources() {
            List<AudioSource> created;
            try {
                created = factory.get();
            } catch (Throwable t) {
                Log.error("audio: creating sources failed", t);
                created = new ArrayList<>();
            }
            List<AudioSource> ok = new ArrayList<>();
            List<MixInput> mix = new ArrayList<>();
            for (AudioSource s : created) {
                if (stopRequested) break;
                PcmFifo fifo = new PcmFifo(FIFO_CAPACITY_FRAMES);
                try {
                    s.start(fifo);
                } catch (Throwable t) {
                    Log.warn("audio: source " + s.name() + " failed to start: " + t);
                    safeStop(s);
                    continue;
                }
                ok.add(s);
                mix.add(new MixInput(fifo, gainFor(s), s.primeMs() * 48));
            }
            sources = ok;
            inputs = mix.toArray(new MixInput[0]);
            if (ok.isEmpty()) Log.warn("audio: no capture sources; sending silence");
        }

        private void closeSources() {
            for (AudioSource s : sources) safeStop(s);
        }

        private void safeStop(AudioSource s) {
            try {
                s.stop();
            } catch (Throwable t) {
                Log.warn("audio: stopping " + s.name() + " failed: " + t);
            }
        }

        private float gainFor(AudioSource s) {
            return s.kind() == AudioSource.Kind.SYSTEM ? AudioConfig.getSystemGain() : AudioConfig.getMicGain();
        }

        private void loop() {
            float[] acc = new float[TICK_FRAMES * 2];
            short[] scratch = new short[TICK_FRAMES * 2];
            long next = System.nanoTime() + TICK_NANOS;
            long lastErrorLog = 0;
            while (!stopRequested) {
                long now = System.nanoTime();
                long wait = next - now;
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                    continue;                       // re-check stop flag and remaining time
                }
                if (-wait > RESYNC_NANOS) {
                    // Way behind (debugger, suspend, starved CPU): don't burst; restart the clock.
                    next = now;
                    resyncs++;
                }
                next += TICK_NANOS;
                try {
                    MixInput[] in = inputs;
                    List<AudioSource> src = sources;
                    for (int i = 0; i < in.length; i++) in[i].gain = gainFor(src.get(i));
                    byte[] pcm = mixTick(in, acc, scratch);
                    seq++;
                    fanOut(new AudioPacket(seqGen.getAndIncrement(), AudioPacket.SAMPLE_RATE, AudioPacket.CHANNELS, pcm).encode());
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis();
                    if (ms - lastErrorLog > 5000) {
                        lastErrorLog = ms;
                        Log.error("audio: mixer tick failed", t);
                    }
                }
            }
        }

        private void fanOut(byte[] payload) {
            if (stopRequested) return;
            for (Consumer<byte[]> sink : sinks) {
                try {
                    sink.accept(payload);
                } catch (Throwable t) {
                    Log.debug("audio: sink threw " + t);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Windows timer resolution

    /**
     * Raises the Windows timer resolution to 1 ms while capture runs, so the 20 ms mixer timer
     * and the WASAPI polling sleeps are accurate (the default can be 15.6 ms).
     */
    private static final class WinTimer {
        interface Winmm extends Library {
            int timeBeginPeriod(int ms);

            int timeEndPeriod(int ms);
        }

        private static volatile Winmm winmm;

        static boolean begin() {
            if (!Os.WINDOWS) return false;
            try {
                if (winmm == null) winmm = Native.load("winmm", Winmm.class);
                return winmm.timeBeginPeriod(1) == 0;
            } catch (Throwable t) {
                Log.debug("audio: timeBeginPeriod unavailable: " + t);
                return false;
            }
        }

        static void end() {
            try {
                if (winmm != null) winmm.timeEndPeriod(1);
            } catch (Throwable ignored) {
            }
        }
    }
}
