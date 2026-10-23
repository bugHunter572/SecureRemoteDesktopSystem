package com.srd.common.update;

import com.srd.common.util.BuildInfo;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an installed Windows exe current: checks the signed release manifest now and then,
 * stages a newer package and hands over to {@code apply-update.ps1}, which swaps the files once
 * the app has exited and rolls back if the new version does not come up healthy.
 *
 * <p>Only active when running from the packaged exe (not from an IDE/jar). Optional
 * {@code update.properties} next to the exe: {@code update.enabled} (default true),
 * {@code update.baseUrl} (release download folder), {@code update.intervalMinutes} (default 240).
 */
public final class UpdateService {
    public static final String DEFAULT_BASE_URL =
            "https://github.com/bugHunter572/SecureRemoteDesktopSystem/releases/latest/download/";
    private static final long HEALTHY_AFTER_SECONDS = 60;

    /** What the app provides to the updater. */
    public interface Host {
        /** "client", "server" or "observer" (selects update-&lt;app&gt;.properties). */
        String app();

        /** The exe next to the working directory, for example SecureRemoteDesktopClient.exe. */
        String exeName();

        /** Quits the app promptly and without any confirmation dialog (the swap script takes over). */
        void exitForUpdate();

        /** False while something must not be interrupted (an active recording); retried at the next check. */
        default boolean canRestartNow() {
            return true;
        }
    }

    private static boolean started;

    private UpdateService() {
    }

    public static synchronized void start(Host host) {
        if (started) return;
        started = true;
        if (!Os.WINDOWS) {
            Log.info("Auto-update: only on Windows");
            return;
        }
        File appDir = new File(System.getProperty("user.dir"));
        if (!new File(appDir, host.exeName()).isFile()) {
            Log.info("Auto-update off: not running from " + host.exeName());
            return;
        }
        // An app that stays up this long after a swap is healthy; the swap script waits for this file.
        Threads.scheduler().schedule(() -> writeHealthMarker(appDir), HEALTHY_AFTER_SECONDS, TimeUnit.SECONDS);

        Properties cfg = loadConfig(appDir);
        if (!Boolean.parseBoolean(cfg.getProperty("update.enabled", "true"))) {
            Log.info("Auto-update disabled in update.properties");
            return;
        }
        String base = cfg.getProperty("update.baseUrl", DEFAULT_BASE_URL).trim();
        if (!base.endsWith("/")) base += "/";
        long every = Math.max(15, parseLong(cfg.getProperty("update.intervalMinutes"), 240));
        long first = 2 + ThreadLocalRandom.current().nextLong(0, 10); // spread 25 PCs out
        final String baseUrl = base;
        Threads.scheduler().scheduleWithFixedDelay(() -> checkNow(host, appDir, baseUrl), first, every, TimeUnit.MINUTES);
        Log.info("Auto-update on (" + host.app() + " " + BuildInfo.VERSION + ", every " + every + " min, " + baseUrl + ")");
    }

    /** One check; any failure is logged and retried at the next interval. */
    static synchronized void checkNow(Host host, File appDir, String baseUrl) {
        try {
            PublicKey key = UpdateSignature.bundledPublicKey();
            Updater up = new Updater(key);
            UpdateManifest m = up.check(baseUrl, host.app(), BuildInfo.VERSION);
            if (m == null) return;
            File failed = new File(new File(appDir, "update"), "failed-" + m.version);
            if (failed.exists()) {
                Log.debug("Update " + m.version + " failed here before; skipping until a newer release");
                return;
            }
            if (!host.canRestartNow()) {
                Log.info("Update " + m.version + " is ready but the app is busy; will retry");
                return;
            }
            Log.info("Update " + BuildInfo.VERSION + " -> " + m.version + ": downloading " + m.url);
            File work = new File(new File(appDir, "update"), "work");
            Updater.Prepared p = up.prepare(m, work, host.exeName());
            launchApply(appDir, p.root, host.exeName(), m.version);
            Log.info("Update " + m.version + " staged; restarting to apply it");
            host.exitForUpdate();
        } catch (Throwable t) {
            Log.warn("Auto-update check failed: " + t);
        }
    }

    static void launchApply(File appDir, File stageRoot, String exe, String version) throws IOException {
        File upd = new File(appDir, "update");
        upd.mkdirs();
        File script = new File(upd, "apply-update.ps1");
        try (InputStream in = UpdateService.class.getResourceAsStream("/apply-update.ps1")) {
            if (in == null) throw new IOException("apply-update.ps1 missing from the jar");
            Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-WindowStyle");
        cmd.add("Hidden");
        cmd.add("-File");
        cmd.add(script.getAbsolutePath());
        cmd.add("-AppDir");
        cmd.add(appDir.getAbsolutePath());
        cmd.add("-Stage");
        cmd.add(stageRoot.getAbsolutePath());
        cmd.add("-Exe");
        cmd.add(exe);
        cmd.add("-Version");
        cmd.add(version);
        new ProcessBuilder(cmd).directory(upd).redirectErrorStream(true)
                .redirectOutput(new File(upd, "apply-console.log")).start();
    }

    private static void writeHealthMarker(File appDir) {
        File upd = new File(appDir, "update");
        if (!upd.isDirectory()) return; // no update has ever been applied here
        try {
            Files.write(new File(upd, "ok.txt").toPath(), BuildInfo.VERSION.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Cannot write update health marker: " + e);
        }
    }

    private static Properties loadConfig(File appDir) {
        Properties p = new Properties();
        File f = new File(appDir, "update.properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                Log.warn("Cannot read update.properties: " + e);
            }
        }
        return p;
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
