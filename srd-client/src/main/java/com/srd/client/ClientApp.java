package com.srd.client;

import com.srd.client.audio.AudioConfig;
import com.srd.client.audio.AudioEngine;
import com.srd.client.net.ControlEndpoint;
import com.srd.client.net.PeerHost;
import com.srd.client.net.ServerLink;
import com.srd.client.net.ServerLinks;
import com.srd.client.viewer.PeerViewerWindow;
import com.srd.common.ui.Icons;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Client agent entry point: tray UI, configuration, single instance, watchdog, server links
 * and broadcasting.
 */
public final class ClientApp {
    private static final String EXE_NAME = "SecureRemoteDesktopClient.exe";
    private static final String WATCHDOG_SCRIPT = "client-watchdog.vbs";
    private static final String STOP_FLAG_FILE = ".client-stop";
    /** client-config.properties key behind the tray's "Show Notifications" switch. */
    private static final String NOTIFY_KEY = "notifications";
    private static final String HIDE_ICON_KEY = "trayIconHidden";
    /** Loopback port held for the process lifetime: a second, OS-enforced single-instance guard. */
    private static final int INSTANCE_PORT = Integer.getInteger("srd.instancePort", 2599);

    // Kept in static fields so they're never garbage-collected while the app runs.
    private static FileChannel lockChannel;
    private static FileLock lock;
    private static ServerSocket instanceSocket;

    private static ClientContext ctx;
    private static PeerHost peerHost;
    private static ControlEndpoint controlEndpoint;
    private static TrayIcon trayIcon;
    private static Image greenIcon;
    private static Image redIcon;
    /** Fully transparent image: the tray slot stays (so the menu still opens on right-click) but nothing is drawn. */
    private static Image hiddenIcon;
    private static volatile boolean iconHidden;

    private static Image iconFor(boolean online) {
        if (iconHidden && hiddenIcon != null) return hiddenIcon;
        return online ? greenIcon : redIcon;
    }
    private static Menu serverStatusMenu;
    private static MenuItem startBroadcastItem;
    private static MenuItem stopBroadcastItem;

    private ClientApp() {
    }

    public static void main(String[] args) throws Exception {
        Log.init(new File("logs"), "client", 10_000_000, 5);
        Log.info("SecureRemoteDesktop client " + ClientContext.VERSION + " (updated " + com.srd.common.util.BuildInfo.BUILT + ") starting on " + Os.describe()
                + ", Java " + System.getProperty("java.version") + ", dir " + new File(".").getAbsolutePath());
        javax.imageio.ImageIO.setUseCache(false); // no temp files per decoded/encoded image
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        if (!acquireSingleInstance()) {
            Log.info("Another instance is already running; exiting.");
            System.exit(0);
            return;
        }
        try {
            startUp();
        } catch (Throwable t) {
            Log.error("Fatal error during client startup", t);
            fatalStartup(t);
        }
    }

    /**
     * Everything after the single-instance guard. Wrapped in a try/catch in {@link #main} so a
     * startup failure (for example a capture, input or audio subsystem that cannot initialise)
     * still leaves a usable menu-bar item to quit and a logged, visible error — instead of the JVM
     * dying before the tray is ever created, which looked like "the icon crashed and there is no
     * tray icon".
     */
    private static void startUp() throws Exception {
        warnIfTranslocatedMac();
        if (Os.WINDOWS) installWatchdog(new File(".").getAbsoluteFile().getParentFile());

        ClientConfig config = ClientConfig.loadDefault();
        AudioConfig.load(config.raw());

        ctx = new ClientContext(config);
        Log.info("Screen layout: " + ctx.streamer.layout() + "; input: " + ctx.injector.name());
        peerHost = new PeerHost(ctx, (who, connected) -> {
            refreshTray();
        });

        // The tray/menu-bar icon comes first: it must exist even while the first-run question is
        // open, so the user always has a menu (on macOS a menu-bar app's dialog can otherwise
        // open hidden behind other windows, leaving no icon and no way to quit).
        SwingUtilities.invokeAndWait(ClientApp::installTray);
        // After the menu-bar UI exists (the app is then registered as running), so the
        // LaunchAgent's "open -W" attaches to this instance instead of starting another.
        if (Os.MAC) Threads.start("mac-launch-agent", ClientApp::installMacLaunchAgent);

        if (Os.MAC) Threads.start("mac-permissions", ClientApp::checkMacPermissions);

        ctx.vpn.start();
        ServerLinks.init(ctx, link -> refreshTray());
        if (config.serverIp().isEmpty()) askServerAddress(true);
        if (!config.serverIp().isEmpty()) {
            ServerLinks.connect(config.serverIp(), config.serverPort(), ServerLinks.MAIN);
        } else {
            Log.info("No server configured yet; use the tray menu \"Set Server Address...\".");
        }
        for (String alt : config.alternativeIps()) {
            ServerLinks.connect(alt, ClientConfig.DEFAULT_SERVER_PORT, "Alternative Server: " + alt);
        }

        if (config.autoBroadcast()) startBroadcasting();
        startControlEndpoint();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ServerLinks.stopAll();
            if (controlEndpoint != null) controlEndpoint.stop();
            if (peerHost != null) peerHost.stop();
        }, "shutdown"));

        Threads.scheduler().scheduleWithFixedDelay(ClientApp::refreshTray, 5, 5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ macOS permissions

    /**
     * On macOS, screen capture and remote control do nothing until the user grants Screen
     * Recording and Accessibility permission. This asks for them (the Screen Recording prompt is
     * shown automatically the first time), then, while either is missing, shows a dialog that
     * opens the right Settings pane, and keeps re-checking until both are granted.
     */
    private static void checkMacPermissions() {
        com.srd.client.mac.MacPermissions.requestScreenRecording(); // triggers the system prompt once
        boolean warned = false;
        while (true) {
            boolean screen = com.srd.client.mac.MacPermissions.screenRecordingGranted();
            boolean access = com.srd.client.mac.MacPermissions.accessibilityGranted();
            if (screen && access) {
                return;
            }
            warned = true;
            String missing = (!screen ? "• Screen Recording — lets the server see this Mac\n" : "")
                    + (!access ? "• Accessibility — allows remote mouse and keyboard\n" : "");
            // macOS caches Screen Recording per process: once turned on it only takes effect after
            // the app restarts. So we offer an explicit "Quit & Reopen" instead of polling forever
            // (the old loop never saw the grant and re-showed the dialog endlessly). Accessibility
            // does update live, so "Re-check" works for it.
            String note = !screen
                    ? "\nmacOS applies Screen Recording only after a restart: turn it on in Settings, "
                            + "then click \"Quit & Reopen\".\n"
                    : "\nTurn it on under Privacy & Security, then click \"Re-check\".\n";
            List<String> opts = new ArrayList<>();
            if (!screen) opts.add("Open Screen Recording");
            if (!access) opts.add("Open Accessibility");
            opts.add("Re-check");
            if (!screen) opts.add("Quit & Reopen");
            opts.add("Later");
            int choice = optionDialog(
                    "Secure Remote Desktop needs permission on this Mac:\n\n" + missing + note,
                    "Permission needed", opts.toArray(new String[0]));
            String picked = choice >= 0 && choice < opts.size() ? opts.get(choice) : "Later";
            switch (picked) {
                case "Open Screen Recording":
                    com.srd.client.mac.MacPermissions.requestScreenRecording();
                    com.srd.client.mac.MacPermissions.openScreenRecordingSettings();
                    break;
                case "Open Accessibility":
                    com.srd.client.mac.MacPermissions.openAccessibilitySettings();
                    break;
                case "Quit & Reopen":
                    relaunchMac();
                    return;
                case "Later":
                    return;
                default: // Re-check: fall through to the loop
                    break;
            }
            // Brief pause so the user can toggle the setting before we re-check (Accessibility is live).
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Relaunches the .app (used to pick up a just-granted Screen Recording permission on macOS). */
    private static void relaunchMac() {
        String app = System.getProperty("srd.appBundle");
        try {
            if (app != null && app.endsWith(".app")) {
                // Launch after we exit so the single-instance guard doesn't reject the new process.
                new ProcessBuilder("/bin/sh", "-c",
                        "sleep 1; /usr/bin/open \"" + app.replace("\"", "") + "\"").start();
            }
        } catch (Exception e) {
            Log.warn("relaunch failed: " + e);
        }
        ServerLinks.stopAll();
        if (peerHost != null) peerHost.stop();
        releaseSingleInstance();
        System.exit(0);
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * Input dialog that is guaranteed to be visible: always-on-top and (macOS) brings this
     * menu-bar app to the foreground first. Plain JOptionPane dialogs of an LSUIElement app open
     * behind the active app's windows.
     */
    static String ask(String message, String initial) {
        bringToFront();
        JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        pane.setInitialSelectionValue(initial);
        javax.swing.JDialog dlg = pane.createDialog(null, "Secure Remote Desktop");
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true); // modal
        dlg.dispose();
        Object v = pane.getInputValue();
        return v == JOptionPane.UNINITIALIZED_VALUE || v == null ? null : v.toString().trim();
    }

    static void tell(String message, String title, int type) {
        bringToFront();
        JOptionPane pane = new JOptionPane(message, type);
        javax.swing.JDialog dlg = pane.createDialog(null, title);
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
        dlg.dispose();
    }

    /** Always-on-top modal dialog with custom buttons; returns the index of the chosen option. */
    static int optionDialog(String message, String title, String[] options) {
        bringToFront();
        JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
                null, options, options[options.length - 1]);
        javax.swing.JDialog dlg = pane.createDialog(null, title);
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
        dlg.dispose();
        Object v = pane.getValue();
        for (int i = 0; i < options.length; i++) if (options[i].equals(v)) return i;
        return options.length - 1;
    }

    private static void bringToFront() {
        if (!Os.MAC) return;
        try {
            java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (d.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) d.requestForeground(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Asks for the main server address and (re)connects. On first run a cancel no longer exits:
     * the app keeps running with its tray menu, and "Set Server Address..." can be used later.
     */
    private static void askServerAddress(boolean firstRun) {
        String current = ctx.config.serverIp();
        String ip = ask(firstRun ? "Welcome to Secure Remote Desktop.\nEnter the server IP address:" : "Server IP address:",
                current.isEmpty() ? "192.168.110.33" : current);
        if (ip == null || ip.isEmpty()) return;
        String portStr = ask("Server port:", String.valueOf(ctx.config.serverPort()));
        if (portStr == null) return;
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            tell("Invalid port: " + portStr, "Secure Remote Desktop", JOptionPane.ERROR_MESSAGE);
            return;
        }
        ctx.config.set("server.ip", ip);
        ctx.config.set("server.port", String.valueOf(port));
        Log.info("Main server set to " + ip + ":" + port);
        if (!firstRun) ServerLinks.replaceMain(ip, port);
        refreshTray();
    }

    // ------------------------------------------------------------------ broadcasting

    private static void startBroadcasting() {
        try {
            peerHost.start();
        } catch (IOException e) {
            Log.warn("Cannot start broadcasting: " + e.getMessage());
        }
        refreshTray();
    }

    private static void stopBroadcasting() {
        peerHost.stop();
        refreshTray();
    }

    /** HTTP {@code /broadcast} on the control port: an authorised request turns broadcasting on, like the tray item. */
    private static void startControlEndpoint() {
        int port = ctx.config.controlPort();
        if (port <= 0) return;
        controlEndpoint = new ControlEndpoint(port, ctx.config.psk(), () -> {
            ctx.config.set("autoBroadcast", "true"); // remembered for the next start, as from the tray
            startBroadcasting();
            return peerHost.isRunning();
        });
        try {
            controlEndpoint.start();
        } catch (IOException e) {
            Log.warn("Cannot start control endpoint on port " + port + ": " + e.getMessage());
            controlEndpoint = null;
        }
    }

    // ------------------------------------------------------------------ tray

    private static void installTray() {
        if (!SystemTray.isSupported()) {
            Log.warn("System tray not supported; running headless.");
            return;
        }
        try {
            greenIcon = scaled(Icons.image("client-connected.png"));
            redIcon = scaled(Icons.image("client-disconnected.png"));
            int px = greenIcon != null ? Math.max(16, greenIcon.getWidth(null)) : 16;
            hiddenIcon = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        } catch (Exception e) {
            Log.warn("tray icons: " + e);
        }
        iconHidden = ctx.config.getBool(HIDE_ICON_KEY, false);
        trayIcon = new TrayIcon(iconHidden && hiddenIcon != null ? hiddenIcon : redIcon != null ? redIcon : fallbackIcon(),
                "Secure Remote Desktop Client");
        trayIcon.setImageAutoSize(true);

        PopupMenu popup = new PopupMenu();
        serverStatusMenu = new Menu("Server Status");
        serverStatusMenu.add(disabled("(updating...)"));

        MenuItem serverItem = new MenuItem("Set Server Address...");
        serverItem.addActionListener(e -> Threads.start("ask-server", () -> askServerAddress(false)));

        MenuItem connectItem = new MenuItem("Connect to Another PC...");
        connectItem.addActionListener(e -> Threads.start("ask-peer", () -> {
            String ip = ask("Enter peer IP to connect:", "192.168.1.100");
            if (ip != null && !ip.isEmpty()) PeerViewerWindow.open(ctx, ip, ctx.config.peerPort());
        }));

        startBroadcastItem = new MenuItem("Start Broadcasting");
        startBroadcastItem.addActionListener(e -> {
            ctx.config.set("autoBroadcast", "true"); // the choice is remembered for the next start
            startBroadcasting();
        });
        stopBroadcastItem = new MenuItem("Stop Broadcasting");
        stopBroadcastItem.addActionListener(e -> {
            ctx.config.set("autoBroadcast", "false");
            stopBroadcasting();
        });

        CheckboxMenuItem notifyItem = new CheckboxMenuItem("Show Notifications", ctx.config.getBool(NOTIFY_KEY, true));
        notifyItem.addItemListener(e -> ctx.config.set(NOTIFY_KEY, String.valueOf(e.getStateChange() == ItemEvent.SELECTED)));

        CheckboxMenuItem hideIconItem = new CheckboxMenuItem("Hide Tray Icon", iconHidden);
        hideIconItem.addItemListener(e -> {
            iconHidden = e.getStateChange() == ItemEvent.SELECTED;
            ctx.config.set(HIDE_ICON_KEY, String.valueOf(iconHidden));
            refreshTray(); // swaps the image now; the invisible icon is still there to right-click
        });

        MenuItem recheckPerms = new MenuItem("Re-check Permissions…");
        recheckPerms.addActionListener(e -> Threads.start("mac-permissions", ClientApp::checkMacPermissions));

        MenuItem about = new MenuItem("About");
        about.addActionListener(e -> Threads.start("about", () -> tell(
                "Secure Remote Desktop Client\nVersion " + ClientContext.VERSION
                        + "\nUpdated " + com.srd.common.util.BuildInfo.BUILT + "\n\n" + Os.describe()
                        + "\nServer: " + (ctx.config.serverIp().isEmpty() ? "(not set)" : ctx.config.serverIp() + ":" + ctx.config.serverPort())
                        + "\nInput: " + ctx.injector.name() + "\nAudio: " + AudioEngine.get().describeSources()
                        + "\nVPN Guard: " + ctx.vpn.current().summary()
                        + (Os.MAC ? "\nDrawing: off (viewers can't draw on a Mac)"
                                : "\nDrawing stealth: " + (com.srd.client.overlay.OverlayNative.isCaptureExclusionSupported()
                                ? "available (hidden from screen sharing)" : "not available on this OS/version"))
                        + "\n\n© 2026 All rights reserved.", "About", JOptionPane.INFORMATION_MESSAGE)));

        // "Exit" restarts the agent: it is meant to keep running (v2 behaviour). On macOS the
        // LaunchAgent also brings it back if it is closed any other way (Force Quit, crash).
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(e -> {
            if (Os.MAC) relaunchMac();
            else restartSelf();
        });

        popup.add(serverStatusMenu);
        popup.add(serverItem);
        popup.addSeparator();
        popup.add(connectItem);
        popup.addSeparator();
        popup.add(startBroadcastItem);
        popup.add(stopBroadcastItem);
        popup.addSeparator();
        popup.add(notifyItem);
        popup.add(hideIconItem);
        if (Os.MAC) popup.add(recheckPerms);
        popup.add(about);
        popup.addSeparator();
        popup.add(exit);
        trayIcon.setPopupMenu(popup);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            Log.warn("Cannot add tray icon: " + e);
        }
    }

    /**
     * Tray image as a fully rendered BufferedImage at the size the platform asks for (macOS menu
     * bar ~22 px, Windows 16 px, x2 for Retina/HiDPI). {@code getScaledInstance} images load
     * lazily, and the macOS tray can show them blank.
     */
    private static Image scaled(java.awt.image.BufferedImage img) {
        if (img == null) return null;
        java.awt.Dimension d = SystemTray.getSystemTray().getTrayIconSize();
        int size = Math.max(16, Math.min(d.width, d.height));
        double ui = 1.0;
        try {
            ui = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (Exception ignored) {
        }
        if (Os.MAC) {
            // AWT's macOS tray treats a plain image as 1x and resamples it to the menu-bar height,
            // which is what made the icon blurry on Retina. A multi-resolution image lets it pick
            // the 2x/3x bitmap natively; every variant is rendered here from the 512 px source.
            int max = (int) Math.max(2, Math.ceil(ui));
            java.util.List<Image> variants = new java.util.ArrayList<>();
            for (int s = 1; s <= max; s++) variants.add(render(img, size * s, true));
            return new java.awt.image.BaseMultiResolutionImage(variants.toArray(new Image[0]));
        }
        return render(img, (int) Math.round(size * Math.max(1.0, ui)), false);
    }

    /** Renders {@code src} into a square {@code px} bitmap, halving in steps so the 512 px source doesn't alias. */
    private static java.awt.image.BufferedImage render(java.awt.image.BufferedImage src, int px, boolean pad) {
        int inset = pad ? Math.max(1, px / 10) : 0; // macOS menu bar icons look right with a little padding
        int target = px - 2 * inset;
        java.awt.image.BufferedImage cur = src;
        while (cur.getWidth() / 2 >= target) {
            cur = stepScale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cur, inset, inset, target, target, null);
        g.dispose();
        return out;
    }

    private static java.awt.image.BufferedImage stepScale(java.awt.image.BufferedImage src, int w, int h) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static MenuItem disabled(String text) {
        MenuItem m = new MenuItem(text);
        m.setEnabled(false);
        return m;
    }

    /** A visible last-resort tray image, drawn in code, for when the icon PNGs fail to load. */
    private static Image fallbackIcon() {
        int px = 18;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(40, 110, 210));
        g.fillRoundRect(1, 1, px - 2, px - 2, 6, 6);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 11f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String s = "R";
        g.drawString(s, (px - fm.stringWidth(s)) / 2f, (px + fm.getAscent() - fm.getDescent()) / 2f);
        g.dispose();
        return img;
    }

    /**
     * Last-resort handler when {@link #startUp()} throws before the normal tray is up. It logs and
     * shows the error, and (macOS) stops the LaunchAgent so KeepAlive can't relaunch into the same
     * crash. It then installs a minimal "Quit" tray so the user is never left with a running agent
     * and no way out — the symptom reported as "the icon crashed and there's no tray icon".
     */
    private static void fatalStartup(Throwable t) {
        if (Os.MAC) {
            try {
                new ProcessBuilder("/bin/sh", "-c",
                        "launchctl bootout gui/$(id -u)/com.srd.client 2>/dev/null; "
                                + "launchctl remove com.srd.client 2>/dev/null; true")
                        .start().waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        String logDir = new File("logs").getAbsolutePath();
        String msg = "Secure Remote Desktop could not start:\n\n" + t
                + "\n\nDetails were written to:\n" + logDir + "\n\n"
                + "Use \"Quit\" in the menu bar to stop it.";
        boolean trayUp = false;
        try {
            if (SystemTray.isSupported()) {
                TrayIcon icon = new TrayIcon(fallbackIcon(), "Secure Remote Desktop (startup error)");
                icon.setImageAutoSize(true);
                PopupMenu popup = new PopupMenu();
                MenuItem openLogs = new MenuItem("Open Log Folder");
                openLogs.addActionListener(e -> {
                    try {
                        java.awt.Desktop.getDesktop().open(new File("logs"));
                    } catch (Throwable ex) {
                        Log.warn("open logs: " + ex);
                    }
                });
                MenuItem showErr = new MenuItem("Show Error…");
                showErr.addActionListener(e -> Threads.start("err", () -> tell(msg, "Startup error", JOptionPane.ERROR_MESSAGE)));
                MenuItem quit = new MenuItem(Os.MAC ? "Quit Secure Remote Desktop" : "Exit");
                quit.addActionListener(e -> {
                    releaseSingleInstance();
                    System.exit(1);
                });
                popup.add(showErr);
                popup.add(openLogs);
                popup.addSeparator();
                popup.add(quit);
                icon.setPopupMenu(popup);
                SystemTray.getSystemTray().add(icon);
                trayIcon = icon; // so later dialogs that null-check the tray still work
                trayUp = true;
            }
        } catch (Throwable ignored) {
        }
        Threads.start("fatal-dialog", () -> tell(msg, "Startup error", JOptionPane.ERROR_MESSAGE));
        if (!trayUp) {
            // No tray to keep us alive or let the user quit: show the error, then exit cleanly.
            try {
                Thread.sleep(8000);
            } catch (InterruptedException ignored) {
            }
            releaseSingleInstance();
            System.exit(1);
        }
    }

    /**
     * macOS Gatekeeper "App Translocation": an unsigned app opened straight from a download/DMG is
     * run from a random read-only path, which breaks the bundled Java and the login item. If we
     * detect it, tell the user to install properly (Install.command / move to Applications).
     */
    private static void warnIfTranslocatedMac() {
        if (!Os.MAC) return;
        String app = System.getProperty("srd.appBundle", "");
        if (app.contains("/AppTranslocation/") || app.startsWith("/private/var/folders/")) {
            Log.warn("Running from a translocated path: " + app);
            Threads.start("translocation", () -> tell(
                    "Secure Remote Desktop is running from a temporary, read-only location, so it may be "
                            + "unstable and won't start at login.\n\n"
                            + "Please quit, then run \"Install SecureRemoteDesktop.command\" from the disk image "
                            + "(or drag the app to Applications and reopen it).",
                    "Please install the app", JOptionPane.WARNING_MESSAGE));
        }
    }

    /** How the main server sees this PC, as far as the client can tell. */
    private enum MainState { UNKNOWN, ONLINE, NOT_REGISTERED, OFFLINE }

    private static MainState lastMainState = MainState.UNKNOWN;
    private static boolean everConnected;
    private static boolean unreachableNotified;
    private static volatile boolean offlineNoticeShown;
    private static java.util.concurrent.ScheduledFuture<?> offlineNotice;

    private static void cancelOfflineNotice() {
        java.util.concurrent.ScheduledFuture<?> f = offlineNotice;
        if (f != null) f.cancel(false);
        offlineNotice = null;
    }

    /**
     * Shows a system notification from the tray icon. Silent when the user turned notifications off
     * in the tray menu, or the server owner turned them off for client PCs (Notification Settings).
     * Also used for the notices the server sends (screen not received, VPN Guard off, recovery).
     */
    public static void notifyUser(String title, String text, boolean warning) {
        TrayIcon t = trayIcon;
        if (t == null || ctx == null || !ctx.serverNotify || !ctx.config.getBool(NOTIFY_KEY, true)) return;
        t.displayMessage(title, text, warning ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
    }

    private static String describe(ServerLink l) {
        if (l.isNotRegistered()) return "Connected (not registered on server)";
        return l.isConnected() ? "Connected" : "Disconnected";
    }

    /**
     * Updates the server status submenu, tray icon and tooltip, and shows a balloon whenever the
     * main server's view of this PC changes: connected, disconnected (the server now shows this
     * PC offline), or connected but not on the server's client list. Edge-triggered, so
     * reconnect retries don't spam notifications.
     */
    private static synchronized void refreshTray() {
        if (trayIcon == null) return;
        List<ServerLink> links = new ArrayList<>(ServerLinks.all());
        links.sort((a, b) -> {
            boolean am = ServerLinks.MAIN.equals(a.label), bm = ServerLinks.MAIN.equals(b.label);
            return am != bm ? (am ? -1 : 1) : a.label.compareToIgnoreCase(b.label);
        });
        ServerLink main = null;
        for (ServerLink l : links) if (ServerLinks.MAIN.equals(l.label)) main = l;
        MainState state = main == null ? MainState.OFFLINE
                : main.isNotRegistered() ? MainState.NOT_REGISTERED
                : main.isConnected() ? MainState.ONLINE : MainState.OFFLINE;
        String server = main == null ? "server" : main.host + ":" + main.port;
        String lastError = main == null ? null : main.lastError();

        // ---- notifications on state edges (all through notifyUser: the tray toggle and the server's switch apply)
        if (state != lastMainState) {
            // Set the icon first: the balloon shows whatever the tray icon is at that moment, so a
            // "Connected" notice would otherwise carry the red "disconnected" image.
            Image now = iconFor(state == MainState.ONLINE);
            if (now != null && trayIcon.getImage() != now) trayIcon.setImage(now);
            switch (state) {
                case ONLINE: {
                    cancelOfflineNotice();
                    boolean reconnect = everConnected;
                    everConnected = true;
                    unreachableNotified = false;
                    if (!reconnect) {
                        notifyUser("Connected", "Connected to the main server", false);
                    } else if (offlineNoticeShown) {
                        notifyUser("Connected", "Connected to the main server", false);
                    }
                    offlineNoticeShown = false;
                    break;
                }
                case NOT_REGISTERED:
                    cancelOfflineNotice();
                    everConnected = true;
                    break;
                case OFFLINE:
                    if (lastMainState == MainState.ONLINE || lastMainState == MainState.NOT_REGISTERED) {
                        // Only say so if it lasts: a quick reconnect stays silent (and so does its "Reconnected").
                        offlineNotice = Threads.scheduler().schedule(() -> {
                            offlineNoticeShown = true;
                            notifyUser("Disconnected", "Lost connection to the main server", true);
                        }, Math.max(0, ctx.notifyDelaySec), TimeUnit.SECONDS);
                    }
                    break;
                default:
                    break;
            }
            Log.info("Main server state: " + lastMainState + " -> " + state);
            lastMainState = state;
        }
        if (state == MainState.OFFLINE && !everConnected && !unreachableNotified && lastError != null) {
            unreachableNotified = true;
            notifyUser("Cannot reach server", "Main Server is not reachable", true);
        }

        boolean broadcasting = peerHost != null && peerHost.isRunning();
        int viewers = PeerHost.viewerCount();
        SwingUtilities.invokeLater(() -> {
            serverStatusMenu.removeAll();
            if (links.isEmpty()) serverStatusMenu.add(disabled("No servers configured"));
            for (ServerLink l : links) serverStatusMenu.add(disabled(l.label + " : " + describe(l)));
            Image target = iconFor(state == MainState.ONLINE);
            if (target != null && trayIcon.getImage() != target) trayIcon.setImage(target);
            String tip = state == MainState.ONLINE ? "Connected to " + server
                    : state == MainState.NOT_REGISTERED ? "Connected to " + server + " - NOT registered"
                    : "Disconnected from " + server + " - reconnecting";
            trayIcon.setToolTip(tip + (broadcasting ? " · broadcasting" + (viewers > 0 ? " (" + viewers + " viewing)" : "") : ""));
            startBroadcastItem.setEnabled(!broadcasting);
            stopBroadcastItem.setEnabled(broadcasting);
        });
    }

    // ------------------------------------------------------------------ process management

    /** The tray "Exit" restarts the agent (it is meant to keep running), exactly as before. */
    private static void restartSelf() {
        ServerLinks.stopAll();
        peerHost.stop();
        releaseSingleInstance();
        File exe = new File(EXE_NAME).getAbsoluteFile();
        if (Os.WINDOWS && exe.isFile()) {
            try {
                new ProcessBuilder(exe.getPath()).directory(exe.getParentFile()).start();
            } catch (IOException ex) {
                Log.warn("Relaunch failed: " + ex.getMessage());
            }
        }
        System.exit(0);
    }

    /**
     * Two independent guards, both released by the OS when the process dies:
     * a file lock under %LOCALAPPDATA% and a bound loopback port. Two copies of the agent had
     * been found running at once on one PC, fighting over audio devices and over the server's
     * one-connection-per-IP slot.
     */
    private static boolean acquireSingleInstance() {
        try {
            instanceSocket = new ServerSocket(INSTANCE_PORT, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            return false;
        }
        try {
            String base = System.getenv("LOCALAPPDATA");
            File dir = base != null && !base.isEmpty() ? new File(base, "SecureRemoteDesktopClient")
                    : new File(System.getProperty("user.home"), ".srd-client");
            dir.mkdirs();
            lockChannel = new RandomAccessFile(new File(dir, System.getProperty("srd.instance", ".client") + ".lock"), "rw").getChannel();
            lock = lockChannel.tryLock();
            if (lock == null) {
                lockChannel.close();
                return false;
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (Exception e) {
            Log.warn("Instance file lock unavailable (port guard still active): " + e);
        }
        return true;
    }

    private static void releaseSingleInstance() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
        }
        try {
            if (instanceSocket != null) instanceSocket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * macOS equivalent of the Windows watchdog: a per-user LaunchAgent that starts the app at
     * login and brings it back whenever it stops (Exit, Force Quit, crash): KeepAlive, throttled to
     * every 10 s. It goes through {@code open -W -a <app>} so the app is launched by
     * LaunchServices: macOS then attributes the Screen Recording / Accessibility permissions to
     * this .app, and {@code -W} keeps the job alive for as long as the app runs (if the app is
     * already running, {@code open -W} just waits for it).
     *
     * <p>The job is loaded into the current session right away, not only at the next login, so
     * the app comes back after being closed today too. Only installed when running from the .app
     * (the launcher passes {@code srd.appBundle}); rewritten (and reloaded) when the app moved.
     */
    private static void installMacLaunchAgent() {
        String app = System.getProperty("srd.appBundle");
        if (app == null || !app.endsWith(".app")) return;
        if (app.contains("/AppTranslocation/") || app.startsWith("/Volumes/")) return; // not installed yet
        try {
            File dir = new File(System.getProperty("user.home"), "Library/LaunchAgents");
            dir.mkdirs();
            File plist = new File(dir, "com.srd.client.plist");
            String esc = app.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String xml = String.join("\n",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
                    "<plist version=\"1.0\">",
                    "<dict>",
                    "    <key>Label</key><string>com.srd.client</string>",
                    "    <key>ProgramArguments</key>",
                    "    <array>",
                    "        <string>/usr/bin/open</string><string>-W</string><string>-a</string><string>" + esc + "</string>",
                    "    </array>",
                    "    <key>RunAtLoad</key><true/>",
                    "    <key>KeepAlive</key><true/>",
                    "    <key>ThrottleInterval</key><integer>10</integer>",
                    "    <key>ProcessType</key><string>Interactive</string>",
                    "</dict>",
                    "</plist>",
                    "");
            String old = plist.isFile() ? new String(java.nio.file.Files.readAllBytes(plist.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
            boolean changed = !xml.equals(old);
            if (changed) {
                try (FileWriter fw = new FileWriter(plist)) {
                    fw.write(xml);
                }
                Log.info("LaunchAgent written: " + plist + " (starts " + app + " at login and keeps it running)");
            }
            String domain = "gui/" + uid();
            boolean loaded = launchctl("print", domain + "/com.srd.client") == 0;
            if (loaded && changed) {
                launchctl("bootout", domain + "/com.srd.client");
                loaded = false;
            }
            if (!loaded) {
                launchctl("enable", domain + "/com.srd.client");
                int rc = launchctl("bootstrap", domain, plist.getPath());
                Log.info("LaunchAgent loaded into this session" + (rc == 0 ? "" : " (launchctl bootstrap exit " + rc + ")"));
            }
        } catch (IOException e) {
            Log.warn("Could not install LaunchAgent: " + e.getMessage());
        }
    }

    private static String uid() {
        try {
            Process p = new ProcessBuilder("/usr/bin/id", "-u").start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String s = r.readLine();
                return s == null ? "" : s.trim();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** Runs {@code launchctl <args>} quietly; returns its exit code (-1 if it could not run). */
    private static int launchctl(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("/bin/launchctl");
            java.util.Collections.addAll(cmd, args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Keeps the agent running across crashes: a VBScript loop registered under HKCU Run launches
     * the exe and waits for it to exit. Launch4j's {@code stayAlive=true} makes the exe wait for
     * the JVM. The old build exited immediately, so the watchdog re-spawned a fresh JVM every 5
     * seconds, forever.
     */
    private static void installWatchdog(File appDir) {
        Os.tryNative("install watchdog", () -> {
            File exe = new File(appDir, EXE_NAME);
            if (!exe.isFile()) {
                Log.info("Watchdog not installed (running from " + appDir + ", no " + EXE_NAME + ")");
                return;
            }
            File vbs = new File(appDir, WATCHDOG_SCRIPT);
            String exeEsc = exe.getPath().replace("\"", "");
            String stopEsc = new File(appDir, STOP_FLAG_FILE).getPath().replace("\"", "");
            String script = "Set oFSO = CreateObject(\"Scripting.FileSystemObject\")\r\n"
                    + "Set oShell = CreateObject(\"WScript.Shell\")\r\n"
                    + "Do\r\n"
                    + "    If oFSO.FileExists(\"" + stopEsc + "\") Then\r\n"
                    + "        oFSO.DeleteFile \"" + stopEsc + "\"\r\n"
                    + "        WScript.Quit\r\n"
                    + "    End If\r\n"
                    + "    oShell.Run Chr(34) & \"" + exeEsc + "\" & Chr(34), 0, True\r\n"
                    + "    WScript.Sleep 5000\r\n"
                    + "Loop\r\n";
            try (FileWriter fw = new FileWriter(vbs)) {
                fw.write(script);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "SecureRemoteDesktopClient", "wscript.exe \"" + vbs.getPath() + "\"");
            Log.info("Watchdog installed: " + vbs);
        });
    }
}
