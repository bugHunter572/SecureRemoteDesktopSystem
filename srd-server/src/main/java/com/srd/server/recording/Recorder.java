package com.srd.server.recording;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import com.srd.common.ui.ViewerSession;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import com.srd.common.video.FrameBuffer;
import com.srd.server.util.Images;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Records one remote screen to a video file.
 *
 * <ul>
 *   <li>Video: {@link ViewerSession#frameBuffer()} is sampled at a fixed {@value #TARGET_FPS} fps on
 *       a scheduler thread; each snapshot is JPEG-encoded (q 0.85) on the IO executor as
 *       {@code frame_%06d.jpg}. If disk I/O falls behind, ticks are skipped instead of queueing
 *       unbounded memory; the real rate (frames / elapsed seconds) is what ffmpeg is told, so the
 *       video always plays at real speed.</li>
 *   <li>Audio: every AudioPacket from the session goes through a bounded queue to a writer thread
 *       that appends raw 48 kHz stereo s16le, padding gaps with silence to stay in sync.</li>
 *   <li>Optional server microphone track, mixed by ffmpeg.</li>
 * </ul>
 * Nothing here runs on the EDT or a network thread except the (non-blocking) audio enqueue.
 */
public final class Recorder {
    public static final int TARGET_FPS = 10;
    static final int MAX_PENDING_WRITES = 30;
    static final float JPEG_QUALITY = 0.85f;
    static final int AUDIO_BYTES_PER_SEC = AudioPacket.SAMPLE_RATE * AudioPacket.CHANNELS * 2;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public interface FinishListener {
        /** @param output the video file, or null if nothing usable was produced */
        void onFinished(Recorder r, File output);
    }

    private static final class Chunk {
        final long arrivalNanos;
        final byte[] pcm;

        Chunk(long arrivalNanos, byte[] pcm) {
            this.arrivalNanos = arrivalNanos;
            this.pcm = pcm;
        }
    }

    private static final Chunk POISON = new Chunk(0, new byte[0]);

    private final String key;
    private final ViewerSession viewer;
    private final File dateDir;
    private final File workDir;
    private final boolean mixMic;
    private final ExecutorService io;
    private final ScheduledExecutorService sampler;
    private final FinishListener listener;
    private final String startTs;

    private final AtomicInteger pendingWrites = new AtomicInteger();
    private volatile int framesWritten;
    private volatile int skippedTicks;
    private volatile int lockW;
    private volatile int lockH;
    private volatile long firstFrameNanos;
    private volatile long stopNanos;
    private volatile boolean stopped;
    private volatile boolean writeErrorLogged;
    private ScheduledFuture<?> samplerTask;

    private final BlockingQueue<Chunk> audioQueue = new ArrayBlockingQueue<>(512); // ~10 s of 20 ms packets
    private final Consumer<AudioPacket> audioListener = this::onAudio;
    private final File audioFile;
    private final File micFile;
    private Thread audioWriter;
    private MicCapture mic;
    private volatile int audioDropped;

    /**
     * @param dateDir where the finished video goes (v2 layout: {@code <base>/<id>_<name>/<yyyyMMdd>/})
     */
    public Recorder(String key, ViewerSession viewer, File dateDir, boolean mixMic, ExecutorService io,
                    ScheduledExecutorService sampler, FinishListener listener) {
        this.key = key;
        this.viewer = viewer;
        this.dateDir = dateDir;
        this.mixMic = mixMic;
        this.io = io;
        this.sampler = sampler;
        this.listener = listener;
        this.startTs = LocalDateTime.now().format(TS);
        this.workDir = new File(dateDir, "frames_" + startTs);
        this.audioFile = new File(workDir, "remote_audio.raw");
        this.micFile = new File(workDir, "mic_audio.raw");
    }

    public String key() {
        return key;
    }

    public ViewerSession viewer() {
        return viewer;
    }

    public File dateDir() {
        return dateDir;
    }

    public synchronized void start() {
        io.execute(() -> {
            if (!workDir.isDirectory() && !workDir.mkdirs()) Log.warn("Cannot create recording dir " + workDir);
        });
        audioWriter = Threads.start("rec-audio-" + key, this::audioLoop);
        viewer.addAudioListener(audioListener);
        if (mixMic) mic = MicCapture.start(micFile, () -> firstFrameNanos, "rec-mic-" + key);
        samplerTask = sampler.scheduleAtFixedRate(this::sample, 0, 1000 / TARGET_FPS, TimeUnit.MILLISECONDS);
        Log.info("Recording started for " + key + " -> " + dateDir.getAbsolutePath());
    }

    public boolean isStopped() {
        return stopped;
    }

    // ------------------------------------------------------------------ video

    private void sample() {
        if (stopped) return;
        try {
            FrameBuffer fb = viewer.frameBuffer();
            if (fb == null) return;
            if (pendingWrites.get() >= MAX_PENDING_WRITES) {
                skippedTicks++;
                return;
            }
            BufferedImage img = fb.snapshot();
            if (firstFrameNanos == 0) firstFrameNanos = System.nanoTime();
            pendingWrites.incrementAndGet();
            io.execute(() -> writeFrame(img));
        } catch (Throwable t) {
            // A throwing periodic task would be cancelled silently; log and keep sampling.
            Log.error("Recorder sample failed for " + key, t);
        }
    }

    /** Runs on the (single-threaded) IO executor, so frame numbers are assigned in order and gap-free. */
    private void writeFrame(BufferedImage img) {
        try {
            BufferedImage out = img;
            if (lockW == 0) {
                lockW = img.getWidth();
                lockH = img.getHeight();
            } else if (img.getWidth() != lockW || img.getHeight() != lockH) {
                out = Images.scale(img, lockW, lockH); // monitor/resolution switch mid-recording
            }
            if (!workDir.isDirectory()) workDir.mkdirs();
            File f = new File(workDir, String.format("frame_%06d.jpg", framesWritten));
            Images.writeJpeg(out, f, JPEG_QUALITY);
            framesWritten++;
        } catch (IOException e) {
            if (!writeErrorLogged) {
                writeErrorLogged = true;
                Log.warn("Error saving frame for " + key + ": " + e.getMessage());
            }
        } finally {
            pendingWrites.decrementAndGet();
        }
    }

    /** Real frame rate of a recording: frames / elapsed seconds, clamped to a sane range. */
    public static double measuredFps(int frames, long elapsedNanos) {
        if (frames <= 0) return TARGET_FPS;
        double secs = elapsedNanos / 1e9;
        if (secs < 0.2) return TARGET_FPS;
        return Math.max(0.5, Math.min(60.0, frames / secs));
    }

    // ------------------------------------------------------------------ audio

    /** Network reader thread: must not block. */
    private void onAudio(AudioPacket p) {
        if (stopped) return;
        byte[] pcm = (p.sampleRate == AudioPacket.SAMPLE_RATE && p.channels == AudioPacket.CHANNELS)
                ? p.pcm : Pcm.toStereo48k(p.pcm, p.sampleRate, p.channels);
        if (!audioQueue.offer(new Chunk(System.nanoTime(), pcm))) audioDropped++;
    }

    /**
     * Bytes of silence to insert before a chunk so the audio track stays aligned with wall-clock
     * time since {@code zeroNanos}: only gaps longer than 100 ms are filled (jitter is ignored).
     */
    static long silenceBefore(long writtenBytes, long zeroNanos, long arrivalNanos, int chunkBytes) {
        long expectedEnd = (long) ((arrivalNanos - zeroNanos) / 1e9 * AUDIO_BYTES_PER_SEC);
        long expectedStart = expectedEnd - chunkBytes;
        long gap = expectedStart - writtenBytes;
        if (gap <= AUDIO_BYTES_PER_SEC / 10) return 0;
        return gap - (gap % 4);
    }

    private void audioLoop() {
        OutputStream os = null;
        long written = 0;
        byte[] silence = new byte[AudioPacket.FRAME_BYTES];
        try {
            while (true) {
                Chunk c = audioQueue.poll(200, TimeUnit.MILLISECONDS);
                if (c == POISON) break;
                if (c == null) continue;
                long zero = firstFrameNanos;
                if (zero == 0 || c.arrivalNanos < zero) continue; // before the first video frame
                if (os == null) {
                    workDir.mkdirs();
                    os = new BufferedOutputStream(new FileOutputStream(audioFile), 64 * 1024);
                }
                long pad = silenceBefore(written, zero, c.arrivalNanos, c.pcm.length);
                while (pad > 0) {
                    int n = (int) Math.min(pad, silence.length);
                    os.write(silence, 0, n);
                    pad -= n;
                    written += n;
                }
                os.write(c.pcm);
                written += c.pcm.length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.warn("Audio recording failed for " + key + ": " + e.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ stop / encode

    /** Stops capturing and encodes in the background; safe to call from any thread, idempotent. */
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        stopNanos = System.nanoTime();
        if (samplerTask != null) samplerTask.cancel(false);
        viewer.removeAudioListener(audioListener);
        while (!audioQueue.offer(POISON)) audioQueue.poll();
        if (mic != null) mic.stop();
        Threads.start("rec-finish-" + key, this::finish);
    }

    private void finish() {
        File result = null;
        try {
            io.submit(() -> {
            }).get(); // single-threaded IO executor: every queued frame write has completed
            if (audioWriter != null) audioWriter.join(5000);
            if (mic != null) mic.join(3000);

            int frames = framesWritten;
            if (frames == 0) {
                Log.info("Recording for " + key + " produced no frames; nothing to encode.");
                deleteRecursively(workDir);
                return;
            }
            double fps = measuredFps(frames, stopNanos - firstFrameNanos);
            Log.info(String.format("Stopped recording for %s: %d frames, %.2f fps measured, %d ticks skipped, %d audio packets dropped",
                    key, frames, fps, skippedTicks, audioDropped));
            String endTs = LocalDateTime.now().format(TS);
            List<File> tracks = new ArrayList<>();
            if (audioFile.length() > 0) tracks.add(audioFile);
            if (micFile.length() > 0) tracks.add(micFile);
            String ffmpeg = FfmpegEncoder.locate();

            File out = new File(dateDir, startTs + "_" + endTs + (tracks.isEmpty() ? ".avi" : ".mp4"));
            int code = FfmpegEncoder.run(FfmpegEncoder.buildCommand(ffmpeg, fps, workDir, tracks, out));
            if (code != 0 && !tracks.isEmpty()) {
                Log.warn("FFmpeg audio+video encode failed — falling back to video-only");
                out = new File(dateDir, startTs + "_" + endTs + ".avi");
                code = FfmpegEncoder.run(FfmpegEncoder.buildCommand(ffmpeg, fps, workDir, null, out));
            }
            if (code == 0) {
                Log.info("Video created successfully: " + out.getAbsolutePath());
                deleteRecursively(workDir);
                result = out;
            } else {
                Log.warn("Video encoding failed; frames kept in " + workDir.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.error("Error converting frames to video for " + key, e);
        } finally {
            try {
                listener.onFinished(this, result);
            } catch (Exception e) {
                Log.error("Recorder finish listener failed", e);
            }
        }
    }

    static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        if (f.exists() && !f.delete()) Log.warn("Failed to delete: " + f.getAbsolutePath());
    }
}
