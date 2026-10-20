package com.srd.server.observer;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.server.ServerApp;
import com.srd.server.ui.ObserverWindow;
import com.srd.server.util.Images;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Entry point of the watch-only server ("observer"): started by {@code SecureRemoteDesktopObserver.exe}
 * (the server jar with {@code --observer}). It has its own folder, log, lock file and
 * {@code observer.properties}, and none of the main server's features: no database, no saved
 * screenshots, no recording, no alerts, no menus, no remote control or audio.
 */
public final class ObserverApp {
    // Held for the whole JVM lifetime so a second copy can't run.
    private static FileChannel lockChannel;
    private static FileLock lock;

    private ObserverApp() {
    }

    public static void main(String[] args) {
        Log.init(new File("logs"), "observer", 10_000_000, 5);
        String version = com.srd.common.util.BuildInfo.VERSION;
        Log.info("Secure Remote Desktop Observer " + version + " (updated " + com.srd.common.util.BuildInfo.BUILT + ") starting in "
                + System.getProperty("user.dir") + " (Java " + System.getProperty("java.version") + ", " + Os.describe() + ")");
        ImageIO.setUseCache(false);
        File dir = new File(System.getProperty("user.dir"));
        if (!acquireLock(dir)) {
            Log.info("Another observer is already running here - exiting this copy.");
            System.exit(0);
            return;
        }
        registerAutostart(dir);
        ObserverSettings cfg = ObserverSettings.load(dir);
        Images.thumbMaxWidth = cfg.thumbWidth;
        Log.info("Observer settings: port " + cfg.port + ", picture every " + cfg.intervalSec + " s, thumbnails up to " + cfg.thumbWidth + " px");
        ServerApp.preload();
        SwingUtilities.invokeLater(() -> {
            try {
                ObserverController controller = new ObserverController(cfg);
                ObserverWindow window = new ObserverWindow("Secure Remote Desktop Observer v" + version, controller::openLiveView);
                controller.attach(window);
                String err = controller.setBroadcast(true); // always on
                if (err != null) Log.warn("Broadcasting this screen is not available: " + err);
                window.setVisible(true);
                startAutoUpdate();
            } catch (Exception e) {
                Log.error("Error starting the observer window", e);
            }
        });
    }

    private static void startAutoUpdate() {
        com.srd.common.update.UpdateService.start(new com.srd.common.update.UpdateService.Host() {
            @Override
            public String app() {
                return "observer";
            }

            @Override
            public String exeName() {
                return "SecureRemoteDesktopObserver.exe";
            }

            @Override
            public void exitForUpdate() {
                System.exit(0);
            }
        });
    }

    /** Starts with Windows (HKCU Run), like the server, unless {@code -Dsrd.autostart=false}. */
    private static void registerAutostart(File dir) {
        if (!Os.WINDOWS || "false".equalsIgnoreCase(System.getProperty("srd.autostart"))) return;
        String appPath = new File(dir, "SecureRemoteDesktopObserver.exe").getPath();
        if (!new File(appPath).isFile()) return; // running from an IDE / jar
        Os.tryNative("auto-start registry", () -> {
            com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "SecureRemoteDesktopObserver",
                    appPath);
            Log.info("Registry entry set for auto-start: " + appPath);
        });
    }

    private static boolean acquireLock(File dir) {
        try {
            lockChannel = new RandomAccessFile(new File(dir, ".observer.lock"), "rw").getChannel();
            lock = lockChannel.tryLock();
            if (lock == null) {
                try {
                    lockChannel.close();
                } catch (IOException ignored) {
                }
                lockChannel = null;
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (Exception e) {
            Log.warn("Single-instance check failed, allowing start: " + e.getMessage());
            return true;
        }
    }
}
