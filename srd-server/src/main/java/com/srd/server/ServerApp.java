package com.srd.server;

import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.srd.server.db.ClientRow;
import com.srd.server.ui.ServerWindow;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

/**
 * Entry point of the server console.
 *
 * <p>System properties (for tests / a second instance next to production):
 * <ul>
 *   <li>{@code srd.port} — listen port (default 2582);</li>
 *   <li>{@code srd.instance} — instance name; the single-instance lock becomes
 *       {@code .server-<name>.lock} and the Windows auto-start entry is not touched;</li>
 *   <li>{@code srd.autostart=false} — don't write the HKCU Run auto-start entry.</li>
 * </ul>
 */
public final class ServerApp {
    public static final String VERSION = com.srd.common.util.BuildInfo.VERSION;
    private static final String INSTANCE_LOCK_FILE = ".server.lock";

    // Held for the whole JVM lifetime so a second copy of the server can't run. The OS releases
    // it when this process dies, so the guard self-heals across restarts.
    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;

    private ServerApp() {
    }

    static String lockFileName() {
        String inst = System.getProperty("srd.instance");
        return (inst == null || inst.trim().isEmpty()) ? INSTANCE_LOCK_FILE : ".server-" + inst.trim().replaceAll("[^A-Za-z0-9_.-]", "_") + ".lock";
    }

    public static void main(String[] args) {
        Log.init(new File("logs"), "server", 10_000_000, 5);
        Log.info("Secure Remote Desktop Server " + VERSION + " (updated " + com.srd.common.util.BuildInfo.BUILT + ") starting in " + System.getProperty("user.dir")
                + " (Java " + System.getProperty("java.version") + ", " + Os.describe() + ")");
        // Thumbnails are decoded from memory; never spool ImageIO streams to temp files.
        ImageIO.setUseCache(false);

        if (!acquireSingleInstanceLock(System.getProperty("user.dir"))) {
            Log.info("Another server instance is already running — exiting this copy.");
            System.exit(0);
            return;
        }
        registerAutostart();

        ToolTipManager.sharedInstance().setEnabled(true);
        ToolTipManager.sharedInstance().setInitialDelay(100);
        ToolTipManager.sharedInstance().setDismissDelay(5000);

        preload();

        ServerController controller = new ServerController();
        List<ClientRow> rows;
        try {
            rows = controller.init();
        } catch (Exception e) {
            Log.error("Error initializing server", e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "The server could not open its database (clients.db):\n" + e, "Server error", JOptionPane.ERROR_MESSAGE));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                ServerWindow window = new ServerWindow(controller, controller.notificationsOn());
                controller.attach(window, rows);
                window.open();
                startAutoUpdate(controller);
            } catch (Exception e) {
                Log.error("Error initializing server window", e);
            }
        });
    }

    private static void startAutoUpdate(ServerController controller) {
        com.srd.common.update.UpdateService.start(new com.srd.common.update.UpdateService.Host() {
            @Override
            public String app() {
                return "server";
            }

            @Override
            public String exeName() {
                return "SecureRemoteDesktopServer.exe";
            }

            @Override
            public boolean canRestartNow() {
                return !controller.hasActiveRecordings();
            }

            @Override
            public void exitForUpdate() {
                SwingUtilities.invokeLater(controller::exit);
            }
        });
    }

    /**
     * Loads every icon once (renderers must never touch the disk on the event thread) and warms the
     * Java2D anti-aliasing and ImageIO JPEG paths on a background thread, so the first burst of
     * clients does not pay those one-time start-up costs inside the GUI.
     */
    public static void preload() {
        for (String icon : new String[] {"server-icon.png", "connected.png", "disconnected.png", "poweroff.png", "computer-off.png",
                "folder.png", "bell.png", "alarm-bell.png", "delete.png", "rec-start.png", "rec-stop.png",
                "rec-in-progress.png", "full-screen.png", "minimize.png", "remote-on.png", "remote-off.png", "no-screen.png", "vpn-off.png",
                "sound-on.png", "sound-off.png"}) {
            com.srd.common.ui.Icons.image(icon);
        }
        Threads.start("warm-up", () -> {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                p.moveTo(32, 2);
                p.curveTo(60, 20, 50, 50, 32, 62);
                p.curveTo(14, 50, 4, 20, 32, 2);
                g.fill(p);
                g.draw(p);
                g.fillRoundRect(3, 3, 40, 16, 8, 8);
                g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 11));
                g.drawString("VPN Protected", 4, 14);
                g.dispose();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                ImageIO.write(new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", bos);
                ImageIO.read(new java.io.ByteArrayInputStream(bos.toByteArray()));
            } catch (Exception e) {
                Log.debug("warm-up skipped: " + e);
            }
        });
    }

    /** Writes the HKCU Run entry so the console starts with Windows (unless disabled / a test instance). */
    private static void registerAutostart() {
        if (!Os.WINDOWS || "false".equalsIgnoreCase(System.getProperty("srd.autostart"))
                || System.getProperty("srd.instance") != null) {
            return;
        }
        String appPath = System.getProperty("user.dir") + File.separator + "SecureRemoteDesktopServer.exe";
        Os.tryNative("auto-start registry", () -> {
            com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "SecureRemoteDesktopServer",
                    appPath);
            Log.info("Registry entry set for auto-start: " + appPath);
        });
    }

    /**
     * Acquires an exclusive, JVM-lifetime lock on a lock file in the app directory. Returns true if
     * this process is the only instance. Fails open on unexpected I/O errors.
     */
    private static boolean acquireSingleInstanceLock(String appDir) {
        try {
            File lockFile = new File(appDir, lockFileName());
            instanceLockChannel = new RandomAccessFile(lockFile, "rw").getChannel();
            instanceLock = instanceLockChannel.tryLock();
            if (instanceLock == null) {
                try {
                    instanceLockChannel.close();
                } catch (IOException ignored) {
                }
                instanceLockChannel = null;
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
