package com.srd.client.audio;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * macOS system-output (computer audio) capture, with nothing to install: it runs the
 * {@code srd-system-audio} helper bundled in {@code SecureRemoteDesktopClient.app/Contents/MacOS}
 * (ScreenCaptureKit, macOS 13+) and reads raw 48 kHz stereo s16le from its stdout, like
 * {@link PulseMonitorSource} does on Linux. The helper uses the app's Screen Recording permission.
 *
 * <p>If the helper exits it is restarted with backoff. Exit code 2 (macOS older than 13) is
 * permanent; exit code 3 (no permission yet) is retried slowly, so audio starts once the
 * permission is granted.
 */
public final class MacSystemAudioSource implements AudioSource {
    static final String HELPER = "srd-system-audio";
    private static final long[] BACKOFF_MS = {500, 1000, 2000, 5000};
    private static final long NO_PERMISSION_RETRY_MS = 10_000;

    private final File helper;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Process process;
    private volatile String status = "macOS system audio (starting)";
    private PcmFifo out;

    private MacSystemAudioSource(File helper) {
        this.helper = helper;
    }

    /** The bundled helper's source, or null when it can't be found (e.g. run from a bare jar). */
    public static MacSystemAudioSource createIfAvailable() {
        File h = findHelper();
        if (h == null) return null;
        return new MacSystemAudioSource(h);
    }

    static File findHelper() {
        String app = System.getProperty("srd.appBundle");
        if (app != null && !app.isEmpty()) {
            File f = new File(app, "Contents/MacOS/" + HELPER);
            if (f.isFile() && f.canExecute()) return f;
        }
        String override = System.getProperty("srd.audioHelper");
        if (override != null) {
            File f = new File(override);
            if (f.isFile() && f.canExecute()) return f;
        }
        return null;
    }

    @Override
    public synchronized void start(PcmFifo fifo) {
        if (running) return;
        out = fifo;
        running = true;
        thread = Threads.start("audio-mac-system", Thread.MAX_PRIORITY, this::loop);
    }

    @Override
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        kill(process);
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
        return "macOS system audio";
    }

    @Override
    public Kind kind() {
        return Kind.SYSTEM;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int primeMs() {
        return 60; // ScreenCaptureKit delivers ~10-20 ms buffers, sometimes in pairs
    }

    private void loop() {
        int failures = 0;
        String lastError = null;
        while (running) {
            long startedAt = System.nanoTime();
            int code = runOnce();
            if (!running) break;
            if (code == 2) {
                status = name() + " (needs macOS 13 or later)";
                Log.warn("audio: system audio capture needs macOS 13 or later; no computer audio on this Mac");
                return;
            }
            long wait;
            String msg;
            if (code == 3) {
                msg = "no Screen Recording permission yet";
                wait = NO_PERMISSION_RETRY_MS;
            } else {
                if (System.nanoTime() - startedAt > 5_000_000_000L) failures = 0;
                msg = "helper exited (code " + code + ")";
                wait = BACKOFF_MS[Math.min(failures++, BACKOFF_MS.length - 1)];
            }
            if (!msg.equals(lastError)) Log.warn("audio: " + msg + "; retrying");
            lastError = msg;
            status = name() + " (" + msg + ")";
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
        }
        status = name() + " (stopped)";
    }

    /** Runs the helper once; returns its exit code (or -1). */
    private int runOnce() {
        Process p;
        try {
            p = new ProcessBuilder(helper.getAbsolutePath()).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        } catch (Throwable t) {
            Log.warn("audio: cannot start " + helper + ": " + t);
            return -1;
        }
        process = p;
        if (!running) {
            kill(p);
            return 0;
        }
        Threads.start("audio-mac-stderr", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) Log.info("audio: " + l);
            } catch (Throwable ignored) {
            }
        });
        status = name() + " (ScreenCaptureKit)";
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[3840];
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                out.writeLE(buf, 0, n);
            }
        } catch (Throwable t) {
            if (running) Log.warn("audio: reading system audio failed: " + t);
        } finally {
            if (!running) kill(p);
        }
        try {
            if (p.waitFor(1, TimeUnit.SECONDS)) return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        kill(p);
        process = null;
        return -1;
    }

    private static void kill(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }
}
