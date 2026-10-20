package com.srd.server;

import com.srd.common.crypto.SessionCrypto;
import com.srd.common.input.KeyboardGrabber;
import com.srd.common.input.KeyboardGrabbers;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.ViewerSession;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.srd.server.alerts.AlertCenter;
import com.srd.server.alerts.AlertService;
import com.srd.server.alerts.VpnAlertLogic;
import com.srd.server.config.NotifySettings;
import com.srd.server.config.ServerConfig;
import com.srd.server.db.BackupService;
import com.srd.server.db.ClientRepository;
import com.srd.server.db.ClientRow;
import com.srd.server.db.ConfigRepository;
import com.srd.server.db.Database;
import com.srd.server.db.EventLogRepository;
import com.srd.server.db.VpnStatusRepository;
import com.srd.server.net.ClientListener;
import com.srd.server.net.ClientSession;
import com.srd.server.net.NetUtil;
import com.srd.server.net.PeerSession;
import com.srd.server.net.RemoteSession;
import com.srd.server.net.SessionEvents;
import com.srd.server.recording.Recorder;
import com.srd.server.ui.ClientDirectoryDialog;
import com.srd.server.ui.ClientTableModel;
import com.srd.server.ui.LiveViewWindow;
import com.srd.server.ui.RecordsWindow;
import com.srd.server.ui.NotificationSettingsDialog;
import com.srd.server.ui.ServerConfigDialog;
import com.srd.server.sync.SyncPayload;
import com.srd.server.sync.SyncSender;
import com.srd.server.ui.ServerWindow;
import com.srd.server.ui.Toast;
import com.srd.server.util.Images;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * The server's brain: client sessions, status, VPN Guard, live view, recording, persistence.
 *
 * <p>Threading rules (the v2 code broke all of them):
 * <ul>
 *   <li>Swing state (table model, thumbnails, windows) is only touched on the EDT — network and
 *       worker threads hop via {@link #ui};</li>
 *   <li>SQL runs only on the db thread ({@link Database#execute}/{@link Database#submit});</li>
 *   <li>disk writes (screenshots, recorder frames) run on {@link #ioExecutor};</li>
 *   <li>maps shared between threads are concurrent.</li>
 * </ul>
 */
public final class ServerController implements ClientListener.Handler, SessionEvents {
    private static final DateTimeFormatter SCREENSHOT_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String PEER_KEY_PREFIX = "peer:";

    private Database db;
    private ClientRepository clientRepo;
    private EventLogRepository eventRepo;
    private ConfigRepository configRepo;
    private VpnStatusRepository vpnRepo;
    private BackupService backup;
    private volatile ServerConfig config = new ServerConfig();
    private volatile byte[] pskKey = SessionCrypto.pskKey(ServerConfig.DEFAULT_PSK);
    private final Hello localHello;
    private final String serverName;
    private final AlertCenter alerts = new AlertCenter();
    private AlertService notifier;
    private ServerWindow window;
    private ClientListener listener;

    /** Disk writes (idle screenshots, recorder frames); single thread keeps write order. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(Threads.factory("disk-io", Thread.NORM_PRIORITY));
    private final ExecutorService thumbPool = Threads.pool("thumb-decode", 2);
    private final ScheduledExecutorService recScheduler =
            Executors.newSingleThreadScheduledExecutor(Threads.factory("rec-sampler", Thread.NORM_PRIORITY));

    // ---- shared state (concurrent) ----
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PeerSession> peers = new ConcurrentHashMap<>();
    /** Known clients (rows in the table) → display name; readable off the EDT. */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    /** Computer name each client announced in its Hello (shown next to the display name in alerts). */
    private final Map<String, String> hostNames = new ConcurrentHashMap<>();
    /** lastSeen (epoch ms) of each known client as stored when the server started. */
    private final Map<String, Long> lastSeenAtStart = new ConcurrentHashMap<>();
    private final Map<String, Boolean> clientNotifications = new ConcurrentHashMap<>();
    private final Map<String, String> clientScreenshotDirs = new ConcurrentHashMap<>();
    private final Map<String, String> clientRecordingDirs = new ConcurrentHashMap<>();
    private final Map<String, String> peerRecordingDirs = new ConcurrentHashMap<>();
    private final Map<String, VpnGuardStatus> vpn = new ConcurrentHashMap<>();
    private final Map<String, String> lastNotifiedStatus = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sameHostCache = new ConcurrentHashMap<>();
    private final Map<String, Long> undecodableLogged = new ConcurrentHashMap<>();
    private volatile boolean notificationsOn = true;

    // ---- EDT-owned (read off-EDT only through the concurrent maps) ----
    private final Map<String, LiveViewWindow> liveWindows = new ConcurrentHashMap<>();
    private final Map<String, ActiveRecording> recorders = new ConcurrentHashMap<>();

    private static final class ActiveRecording {
        final Recorder recorder;
        final RemoteSession owner;

        ActiveRecording(Recorder recorder, RemoteSession owner) {
            this.recorder = recorder;
            this.owner = owner;
        }
    }

    public ServerController() {
        serverName = NetUtil.hostName();
        localHello = Hello.builder(Hello.ROLE_SERVER)
                .put(Hello.K_NAME, serverName)
                .put(Hello.K_ID, serverName)
                .put(Hello.K_VERSION, ServerApp.VERSION)
                .put(Hello.K_OS, Os.tag())
                .build();
    }

    // ================================================================== startup

    /** Opens the DB and loads config + clients. Runs on the main thread before the UI exists. */
    public List<ClientRow> init() throws Exception {
        db = new Database(Database.DEFAULT_FILE);
        clientRepo = new ClientRepository(db);
        eventRepo = new EventLogRepository(db);
        configRepo = new ConfigRepository(db);
        vpnRepo = new VpnStatusRepository(db);
        backup = new BackupService(db);
        try {
            setConfig(configRepo.load());
            Log.info("Loaded server configuration: " + config);
        } catch (Exception e) {
            Log.error("Error loading config; using defaults", e);
            setConfig(new ServerConfig());
        }
        notifier = new AlertService(new AlertHost(), () -> config.getNotify());
        notifier.start();
        return db.submit(this::loadRowsForUi).get();
    }

    /** What {@link AlertService} needs to know about clients; all reads are from concurrent maps. */
    private final class AlertHost implements AlertService.Host {
        @Override
        public boolean enabled(String clientId) {
            return notificationsOn && clientNotifications.getOrDefault(clientId, true);
        }

        @Override
        public String displayName(String clientId) {
            return nameOf(clientId);
        }

        @Override
        public String hostName(String clientId) {
            return hostNames.getOrDefault(clientId, "");
        }

        @Override
        public void sendToClient(String clientId, String control) {
            ClientSession s = sessions.get(clientId);
            if (s != null) s.sendControl(control);
        }

        @Override
        public void forEachConnected(AlertService.ConnectedVisitor v) {
            for (Map.Entry<String, ClientSession> e : sessions.entrySet()) {
                if (names.containsKey(e.getKey())) v.visit(e.getKey(), e.getValue().connectedAtMs(), e.getValue().lastFrameMs());
            }
        }

        @Override
        public boolean reachable(String clientId) {
            return NetUtil.isHostReachable(clientId);
        }

        @Override
        public List<String> knownNotConnected(long maxAgeMs) {
            long now = System.currentTimeMillis();
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Long> e : lastSeenAtStart.entrySet()) {
                String id = e.getKey();
                if (names.containsKey(id) && !sessions.containsKey(id) && now - e.getValue() <= maxAgeMs) out.add(id);
            }
            return out;
        }

        @Override
        public void logEvent(String clientId, String eventType) {
            ServerController.this.logEvent(clientId, eventType);
        }

        @Override
        public int snapshotIntervalSec() {
            return config.getScreenshotInterval();
        }

        @Override
        public String serverName() {
            return serverName;
        }
    }

    /** db thread: rows + their "Today's Records" cell; refreshes the in-memory client maps. */
    private List<ClientRow> loadRowsForUi() throws Exception {
        List<ClientRow> rows = clientRepo.loadAll();
        names.clear();
        lastSeenAtStart.clear();
        clientNotifications.clear();
        clientScreenshotDirs.clear();
        clientRecordingDirs.clear();
        for (ClientRow r : rows) {
            r.recordsCell = eventRepo.todaysRecordsCell(r.clientId);
            names.put(r.clientId, r.displayName == null ? r.clientId : r.displayName);
            lastSeenAtStart.put(r.clientId, r.lastSeen);
            clientNotifications.put(r.clientId, r.notificationEnabled && notificationsOn);
            if (r.screenshotDir != null) clientScreenshotDirs.put(r.clientId, r.screenshotDir);
            if (r.recordingDir != null) clientRecordingDirs.put(r.clientId, r.recordingDir);
            if (r.vpnState != null && !vpn.containsKey(r.clientId)) {
                vpn.put(r.clientId, VpnGuardStatus.of(VpnGuardStatus.State.parse(r.vpnState)));
            }
        }
        return rows;
    }

    /** EDT: attaches the window, fills it and starts listening. */
    public void attach(ServerWindow w, List<ClientRow> rows) {
        this.window = w;
        w.loadRows(rows, vpn);
        listener = new ClientListener(ClientListener.configuredPort(), this);
        listener.start();
    }

    public boolean notificationsOn() {
        return notificationsOn;
    }

    public AlertCenter alerts() {
        return alerts;
    }

    private void setConfig(ServerConfig c) {
        config = c;
        Images.thumbMaxWidth = c.getThumbMaxWidth();
        pskKey = SessionCrypto.pskKey(c.getPsk());
    }

    private static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private static String lastSeenNow() {
        return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date());
    }

    private String nameOf(String clientId) {
        String n = names.get(clientId);
        return n == null || n.isEmpty() ? clientId : n;
    }

    /** Folder-safe version of a display name (Windows forbids \ / : * ? " < > |). */
    static String safeName(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // ================================================================== ClientListener.Handler

    @Override
    public Hello localHello() {
        return localHello;
    }

    @Override
    public byte[] pskKey() {
        return pskKey;
    }

    @Override
    public void onBindFailed(int port, Exception e) {
        ui(() -> JOptionPane.showMessageDialog(window, "Cannot listen on port " + port + ": " + e.getMessage()
                + "\nIs another server already running?", "Server error", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * CONTROL sent to a client after it connects: REGISTERED (listed in this server's table) or
     * NOT_REGISTERED (auto-add off and unknown). The client reflects it in its tray icon.
     */
    static final String CLIENT_STATE_PREFIX = "CLIENT_STATE:";

    @Override
    public void onConnected(MuxConnection conn) {
        Hello h = conn.remoteHello();
        if (!Hello.ROLE_AGENT.equals(h.role())) {
            Log.warn("Connection from " + conn.remoteAddress() + " announced role '" + h.role() + "' (expected agent); treating it as a client");
        }
        ClientSession s = new ClientSession(conn, this, thumbPool);
        String id = s.clientId();
        hostNames.put(id, s.helloName());
        // This connection now owns the client; close the previous (stale/half-open) one.
        ClientSession previous = sessions.put(id, s);
        if (previous != null && previous != s) {
            Log.info("[CONN] " + id + " reconnected; closing previous connection.");
            previous.close("replaced by a newer connection");
        }
        conn.start(s);
        s.sendControl(config.toServerConfigCommand());
        s.sendControl(config.toIntervalCommand());
        Log.info("[CONN] Client connected: " + id + " (" + s.helloName() + ") " + h);

        long now = System.currentTimeMillis();
        boolean known = names.containsKey(id);
        boolean add = !known && config.isAutoAddClients();
        if (known) {
            db.execute(() -> clientRepo.updateSeen(id, id, now));
        } else if (add) {
            names.put(id, s.helloName());
            clientNotifications.put(id, notificationsOn);
            db.execute(() -> clientRepo.insert(id, s.helloName(), id, now));
        } else {
            Log.info("Auto-add disabled. Client " + id + " connected but not added to database.");
            // Tell the client it is not on this server's list, so its tray doesn't look "connected".
            s.sendControl(CLIENT_STATE_PREFIX + "NOT_REGISTERED");
            return;
        }
        s.sendControl(CLIENT_STATE_PREFIX + "REGISTERED");
        String lastSeen = lastSeenNow();
        ui(() -> {
            if (!window.hasClient(id)) {
                window.addClient(id, s.helloName(), ClientTableModel.STATUS_DISCONNECTED, lastSeen, "", vpn.get(id), s.helloScreen());
            }
            if (sessions.get(id) != s) return; // replaced meanwhile
            window.setLastSeen(id, lastSeen);
            window.setScreenSize(id, s.helloScreen());
            window.setStatus(id, ClientTableModel.STATUS_CONNECTED);
            // Flip the tile out of offline mode now, not on the first thumbnail.
            window.setOffline(id, false, null);
            notifyClientStatus(id, ClientTableModel.STATUS_CONNECTED);
        });
        logEvent(id, EventLogRepository.CONNECT);
    }

    @Override
    public void onOutdatedClient(String ip) {
        if (sessions.containsKey(ip) || !names.containsKey(ip)) return;
        ui(() -> {
            if (sessions.containsKey(ip)) return;
            if (!ClientTableModel.STATUS_OUTDATED.equals(window.status(ip))) {
                window.setStatus(ip, ClientTableModel.STATUS_OUTDATED);
                window.setOffline(ip, true, "Outdated client");
            }
        });
    }

    // ================================================================== SessionEvents

    @Override
    public void onThumbnail(ClientSession s, byte[] jpeg) {
        String id = s.clientId();
        if (sessions.get(id) != s) return;
        BufferedImage full;
        try {
            full = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            full = null;
        }
        if (full == null) {
            Long last = undecodableLogged.get(id);
            if (last == null || System.currentTimeMillis() - last > 600_000) {
                undecodableLogged.put(id, System.currentTimeMillis());
                Log.warn("Failed to decode thumbnail from client: " + id + " (" + jpeg.length + " bytes)");
            }
            return;
        }
        if (Images.isBlank(full)) {
            // A black picture from a client that is connected is a failed capture, not the screen:
            // keep showing the previous screenshot (or "Waiting for screen..." if there is none).
            return;
        }
        Dimension size = new Dimension(full.getWidth(), full.getHeight());
        BufferedImage tile = Images.boundWidth(full, window.tileTargetWidth());
        ui(() -> {
            if (sessions.get(id) == s) window.setThumbnail(id, tile, size);
        });
        // Idle screenshot: only when not recording and no live view is open (v2 rule).
        String name = names.get(id);
        if (name != null && config.isScreenshotSaveEnabled() && !recorders.containsKey(id) && !liveWindows.containsKey(id)) {
            String customDir = clientScreenshotDirs.get(id);
            File screenDir = customDir != null ? new File(customDir) : new File("Screenshots");
            ioExecutor.execute(() -> saveIdleScreenshot(screenDir, id, name, jpeg));
        }
    }

    /**
     * Writes the received JPEG as-is (no re-encode). Layout as in v2:
     * {@code Screenshots/<name>_<clientId>/<yyyyMMdd>/<yyyyMMdd_HHmmss>.jpg}. IO executor only.
     */
    private void saveIdleScreenshot(File screenDir, String clientId, String dName, byte[] jpeg) {
        try {
            File localDir = new File(screenDir, safeName(dName) + "_" + clientId);
            File dateDir = new File(localDir, new SimpleDateFormat("yyyyMMdd").format(new Date()));
            if (!dateDir.isDirectory() && !dateDir.mkdirs()) throw new IOException("cannot create " + dateDir);
            File frameFile = new File(dateDir, LocalDateTime.now().format(SCREENSHOT_TS_FORMAT) + ".jpg");
            Files.write(frameFile.toPath(), jpeg);
        } catch (IOException e) {
            Log.warn("Error saving idle screenshot for " + clientId + ": " + e.getMessage());
        }
    }

    @Override
    public void onStatus(ClientSession s, Map<String, String> status) {
        VpnGuardStatus st = VpnGuardStatus.fromMap(status);
        if (st == null) {
            Log.debug("[" + s.clientId() + "] STATUS " + status);
            return;
        }
        String id = s.clientId();
        if (sessions.get(id) != s) return;
        VpnGuardStatus prev = vpn.put(id, st);
        if (!names.containsKey(id)) return; // not a table client (auto-add off)
        if (!st.sameAs(prev)) {
            Log.info("[VPN] " + nameOf(id) + " (" + id + "): " + st.summary());
            db.execute(() -> vpnRepo.record(id, prev, st));
        }
        boolean notify = notificationsOn && clientNotifications.getOrDefault(id, true);
        VpnAlertLogic.Decision d = alerts.onVpnStatus(id, nameOf(id), prev, st, notify);
        notifier.vpn(id, st);
        ui(() -> {
            window.setVpn(id, st);
            if (d != VpnAlertLogic.Decision.NONE) window.repaintTable();
        });
    }

    @Override
    public void onClientClosed(ClientSession s, String reason) {
        String id = s.clientId();
        boolean owner = sessions.remove(id, s);
        if (owner) {
            Log.info("Client disconnected: " + id + " (" + reason + ")");
        } else {
            Log.info("[CONN] Stale connection for " + id + " ended (" + reason + ")");
        }
        ui(() -> {
            ActiveRecording a = recorders.get(id);
            if (a != null && a.owner == s) stopRecording(id);
            LiveViewWindow w = liveWindows.get(id);
            if (w != null && w.session().connection() == s.connection()) w.setStatusMessage("Connection lost: " + reason);
        });
        if (!owner || !names.containsKey(id)) return;
        boolean userQuit = reason != null && reason.contains("client shutting down");
        notifier.clientClosed(id, userQuit);
        evaluateAndSetAwayStatus(id, userQuit);
        ui(() -> {
            if (sessions.containsKey(id)) return; // reconnected meanwhile
            window.setOffline(id, true, null);
            window.clearSelectionIf(id);
        });
    }

    /**
     * A client's connection ended (closed, or MuxConnection's 10 s liveness timeout): the status is
     * Disconnected and it is recorded in Today's Records. (A ping used to guess "Power Off" for
     * unreachable hosts; it was not reliable, so every loss is just Disconnected now.)
     */
    private void evaluateAndSetAwayStatus(String clientId, boolean userQuit) {
        if (sessions.containsKey(clientId)) return;
        Log.info("[CONN] " + clientId + " away -> status Disconnected");
        logEvent(clientId, EventLogRepository.DISCONNECT);
        ui(() -> {
            if (sessions.containsKey(clientId)) return;
            window.setStatus(clientId, ClientTableModel.STATUS_DISCONNECTED);
            window.setOffline(clientId, true, null);
            notifyClientStatus(clientId, ClientTableModel.STATUS_DISCONNECTED);
        });
    }

    @Override
    public void onPeerClosed(PeerSession s, String reason) {
        peers.remove(s.ip(), s);
        Log.info("Peer connection " + s.ip() + " closed: " + reason);
        String key = PEER_KEY_PREFIX + s.ip();
        ui(() -> {
            ActiveRecording a = recorders.get(key);
            if (a != null && a.owner == s) stopRecording(key);
            LiveViewWindow w = liveWindows.get(key);
            if (w != null && w.session().connection() == s.connection()) w.setStatusMessage("Connection lost: " + reason);
        });
    }

    // ================================================================== events / notifications

    private void logEvent(String clientId, String type) {
        db.execute(() -> {
            if (eventRepo.log(clientId, type)) {
                String cell = eventRepo.todaysRecordsCell(clientId);
                ui(() -> window.setRecordsCell(clientId, cell));
            }
        });
    }

    private void refreshRecordsCell(String clientId) {
        db.execute(() -> {
            String cell = eventRepo.todaysRecordsCell(clientId);
            ui(() -> window.setRecordsCell(clientId, cell));
        });
    }

    private final List<String> connectedBalloon = new ArrayList<>();
    private javax.swing.Timer connectedBalloonTimer;

    /**
     * "Client Connected" balloons are gathered for 3 s and shown once: 150 clients reconnecting after
     * a server restart used to raise 150 Windows notifications from the event thread.
     */
    private void queueConnectedBalloon(String clientId) {
        connectedBalloon.add(window.displayName(clientId));
        if (connectedBalloonTimer != null && connectedBalloonTimer.isRunning()) return;
        connectedBalloonTimer = new javax.swing.Timer(3000, e -> {
            int n = connectedBalloon.size();
            if (n > 0) {
                window.trayMessage("Client Connected", n == 1 ? connectedBalloon.get(0) : n + " clients", TrayIcon.MessageType.INFO);
            }
            connectedBalloon.clear();
        });
        connectedBalloonTimer.setRepeats(false);
        connectedBalloonTimer.start();
    }

    /** One concise tray notification per status transition (EDT). */
    private void notifyClientStatus(String clientId, String status) {
        try {
            if (!notificationsOn) return;
            if (!clientNotifications.getOrDefault(clientId, true)) return;
            // Problems (disconnected, power off, ...) are announced by AlertService after its delay,
            // and a client coming back from one gets a recovery notice once its picture arrives.
            if (!ClientTableModel.STATUS_CONNECTED.equals(status) || notifier.hasProblem(clientId)) return;
            if (status.equals(lastNotifiedStatus.get(clientId))) return;
            lastNotifiedStatus.put(clientId, status);
            String title;
            TrayIcon.MessageType type;
            switch (status) {
                case ClientTableModel.STATUS_CONNECTED:
                    title = "Client Connected";
                    type = TrayIcon.MessageType.INFO;
                    break;
                case ClientTableModel.STATUS_POWER_OFF:
                    title = "Client Power Off";
                    type = TrayIcon.MessageType.WARNING;
                    break;
                default:
                    title = "Client Disconnected";
                    type = TrayIcon.MessageType.WARNING;
            }
            if (ClientTableModel.STATUS_CONNECTED.equals(status)) {
                queueConnectedBalloon(clientId);
            } else {
                window.trayMessage(title, window.displayName(clientId), type);
            }
        } catch (Exception ex) {
            Log.warn("[NOTIFY] error for " + clientId + ": " + ex.getMessage());
        }
    }

    // ================================================================== queries for the UI

    public boolean isConnected(String clientId) {
        return sessions.containsKey(clientId);
    }

    public boolean isRecording(String key) {
        return recorders.containsKey(key);
    }

    public boolean isNotificationEnabled(String clientId) {
        return clientNotifications.getOrDefault(clientId, true);
    }

    public boolean hasCustomDirectory(String clientId) {
        return clientScreenshotDirs.containsKey(clientId) || clientRecordingDirs.containsKey(clientId);
    }

    private boolean isSameHost(String ip) {
        return sameHostCache.computeIfAbsent(ip, NetUtil::isSameHost);
    }

    // ================================================================== live view (EDT)

    /** Thumbnail double-click: one live window per client, sharing the connection's viewer session. */
    public void openLiveView(String clientId) {
        ClientSession s = sessions.get(clientId);
        LiveViewWindow existing = liveWindows.get(clientId);
        if (existing != null) {
            if (s != null && existing.session().connection() == s.connection() && s.viewer() == existing.session()) {
                existing.toFront();
                return;
            }
            existing.dispose(); // belongs to a dead connection; its onClose releases it
            liveWindows.remove(clientId, existing);
        }
        if (s == null) {
            JOptionPane.showMessageDialog(window, "Client " + window.displayName(clientId) + " (" + clientId + ") is not connected.",
                    "Not connected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ViewerSession v = s.acquireViewer(RemoteSession.USER_LIVE);
        attachKeyboardGrabber(v);
        String title = "Full Screen - " + clientId + " - " + window.displayName(clientId);
        LiveViewWindow w = new LiveViewWindow(v, title, () -> startRecording(clientId), () -> stopRecording(clientId));
        w.setRecording(recorders.containsKey(clientId));
        applyControlPolicy(w, clientId);
        w.frame().setOnClose(() -> {
            liveWindows.remove(clientId, w);
            s.releaseViewer(RemoteSession.USER_LIVE);
            window.clearSelectionIf(clientId); // title goes back from "Controlling: ..." to normal
            Log.info("Closed live view of " + clientId);
        });
        liveWindows.put(clientId, w);
        w.show();
        Log.info("Opened live view for client: " + clientId + " (sameHost=" + isSameHost(clientId) + ")");
    }

    /** System-key capture (Win, Alt+Tab, ...) for a live window; the panel activates it only while focused in control mode. */
    private static void attachKeyboardGrabber(ViewerSession v) {
        KeyboardGrabber g = KeyboardGrabbers.create();
        if (g != null) v.setKeyboardGrabber(g);
    }

    private void applyControlPolicy(LiveViewWindow w, String ip) {
        boolean sameHost = isSameHost(ip);
        boolean allowed = config.isScreenControlEnabled() && !sameHost;
        w.setRemoteAllowed(allowed, sameHost ? "Remote control disabled: this is the server's own screen"
                : "Remote control disabled in Server Configuration (Screen Control)");
        w.setSoundAllowed(!sameHost, "Sound disabled: this is the server's own machine (audio feedback)");
    }

    // ================================================================== peer connect

    public void connectToBroadcastingClient() {
        JPanel panel = new JPanel(new GridLayout(1, 2));
        JTextField ipField = new JTextField(20);
        panel.add(new JLabel("Client IP Address:"));
        panel.add(ipField);
        int result = JOptionPane.showConfirmDialog(window, panel, "Connect to Broadcasting Client",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String ip = ipField.getText().trim();
        Log.info("Attempting to connect to broadcasting client at IP: " + ip);
        if (ip.isEmpty()) {
            JOptionPane.showMessageDialog(window, "IP address cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        LiveViewWindow open = liveWindows.get(PEER_KEY_PREFIX + ip);
        PeerSession live = peers.get(ip);
        if (open != null && live != null && live.isOpen()) {
            open.toFront();
            return;
        }
        Threads.start("peer-connect-" + ip, () -> connectToPeer(ip));
    }

    private void connectToPeer(String ip) {
        try {
            PeerSession ps = PeerSession.connect(ip, serverName, pskKey, this);
            PeerSession old = peers.put(ip, ps);
            if (old != null && old != ps) old.close("replaced by a new peer connection");
            ps.start();
            Log.info("Connected to broadcasting client at " + ip + ":" + PeerSession.PEER_PORT + " " + ps.connection().remoteHello());
            ui(() -> openPeerWindow(ps));
        } catch (IOException e) {
            Log.info("Failed to connect to broadcasting client " + ip + ": " + e.getMessage());
            ui(() -> JOptionPane.showMessageDialog(window, "Failed to connect to " + ip + ": " + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void openPeerWindow(PeerSession ps) {
        String key = PEER_KEY_PREFIX + ps.ip();
        LiveViewWindow old = liveWindows.remove(key);
        if (old != null) old.dispose();
        ViewerSession v = ps.acquireViewer(RemoteSession.USER_LIVE);
        attachKeyboardGrabber(v);
        LiveViewWindow w = new LiveViewWindow(v, "Peer View - " + ps.displayName() + " (" + ps.ip() + ")",
                () -> startPeerRecording(ps), () -> stopRecording(key));
        applyControlPolicy(w, ps.ip());
        w.frame().setOnClose(() -> {
            liveWindows.remove(key, w);
            ActiveRecording a = recorders.get(key);
            if (a != null && a.owner == ps) stopRecording(key);
            ps.releaseViewer(RemoteSession.USER_LIVE);
            ps.close("viewer closed");
            peers.remove(ps.ip(), ps);
        });
        liveWindows.put(key, w);
        w.show();
        Log.info("Opened peer view for: " + ps.ip());
    }

    // ================================================================== recording (EDT)

    /** Start recording a monitored client (table buttons or its live window). */
    public void startRecording(String clientId) {
        if (recorders.containsKey(clientId)) return;
        ClientSession s = sessions.get(clientId);
        if (s == null) {
            JOptionPane.showMessageDialog(window, "No connected client selected!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String custom = clientRecordingDirs.get(clientId);
        File baseDir = custom != null ? new File(custom) : new File("Recordings");
        File dateDir = new File(new File(baseDir, clientId + "_" + safeName(window.displayName(clientId))),
                new SimpleDateFormat("yyyyMMdd").format(new Date()));
        startRecorder(clientId, s, dateDir, false);
    }

    private void startPeerRecording(PeerSession ps) {
        String key = PEER_KEY_PREFIX + ps.ip();
        if (recorders.containsKey(key)) return;
        LiveViewWindow w = liveWindows.get(key);
        JFileChooser chooser = new JFileChooser(peerRecordingDirs.getOrDefault(ps.ip(), new File("PeerRecordings").getAbsolutePath()));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Recording Save Directory");
        if (chooser.showOpenDialog(w != null ? w.frame() : window) != JFileChooser.APPROVE_OPTION) return;
        if (!ps.isOpen()) return;
        String dir = chooser.getSelectedFile().getAbsolutePath();
        peerRecordingDirs.put(ps.ip(), dir);
        File dateDir = new File(new File(new File(dir), ps.ip() + "_" + safeName(ps.displayName())),
                new SimpleDateFormat("yyyyMMdd").format(new Date()));
        // v2 peer recordings also captured the server microphone ("mix server mic").
        startRecorder(key, ps, dateDir, true);
    }

    private void startRecorder(String key, RemoteSession owner, File dateDir, boolean mixMic) {
        ViewerSession v = owner.acquireViewer(RemoteSession.USER_RECORD);
        Recorder r = new Recorder(key, v, dateDir, mixMic, ioExecutor, recScheduler, this::onRecordingFinished);
        recorders.put(key, new ActiveRecording(r, owner));
        r.start();
        updateRecordingUi(key);
        Toast.show("Recording started", key + "\n" + dateDir.getAbsolutePath(), new Color(0xC62828), 3000);
    }

    /** Stop recording (table, live window, disconnect). Idempotent. */
    public void stopRecording(String key) {
        ActiveRecording a = recorders.remove(key);
        if (a == null) return;
        a.recorder.stop();
        a.owner.releaseViewer(RemoteSession.USER_RECORD);
        updateRecordingUi(key);
    }

    private void onRecordingFinished(Recorder r, File output) {
        if (output != null) {
            Toast.show("Recording saved", output.getAbsolutePath(), new Color(0x2E7D32), 6000);
        } else {
            Toast.show("Recording not saved", r.key() + ": see the server log (frames are kept in " + r.dateDir() + ")",
                    new Color(0xF57C00), 8000);
        }
    }

    private void updateRecordingUi(String key) {
        boolean rec = recorders.containsKey(key);
        if (!key.startsWith(PEER_KEY_PREFIX)) window.setRecording(key, rec);
        LiveViewWindow w = liveWindows.get(key);
        if (w != null) w.setRecording(rec);
    }

    // ================================================================== commands to clients

    public void refreshClientScreen(String clientId) {
        ClientSession s = clientId == null ? null : sessions.get(clientId);
        if (s == null) {
            JOptionPane.showMessageDialog(window, "No connected client selected for refresh!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        s.sendControl("REFRESH_SCREEN");
        Log.info("Sent REFRESH_SCREEN command to client: " + clientId);
    }

    public void refreshAllScreens() {
        for (ClientSession s : sessions.values()) s.sendControl("REFRESH_SCREEN");
    }

    private void broadcastConfig() {
        String cfg = config.toServerConfigCommand();
        String interval = config.toIntervalCommand();
        for (ClientSession s : sessions.values()) {
            s.sendControl(cfg);
            s.sendControl(interval);
        }
    }

    private void saveConfig() {
        ServerConfig c = config;
        db.execute(() -> {
            configRepo.save(c);
            Log.info("Saved server configuration: " + c);
        });
    }

    // ================================================================== menus (EDT)

    public void showServerConfigurationDialog() {
        ServerConfig edited = ServerConfigDialog.show(window, config);
        if (edited == null) return;
        boolean pskChanged = !edited.getPsk().equals(config.getPsk());
        boolean thumbChanged = edited.getImageQuality() != config.getImageQuality()
                || edited.getThumbMaxWidth() != config.getThumbMaxWidth();
        setConfig(edited);
        saveConfig();
        broadcastConfig();
        // The new quality/size only shows on the next picture from each client (up to a whole
        // interval away), so ask for fresh ones now.
        if (thumbChanged) refreshAllScreens();
        for (Map.Entry<String, LiveViewWindow> e : liveWindows.entrySet()) {
            String ip = e.getKey().startsWith(PEER_KEY_PREFIX) ? e.getKey().substring(PEER_KEY_PREFIX.length()) : e.getKey();
            applyControlPolicy(e.getValue(), ip);
        }
        JOptionPane.showMessageDialog(window, "Server configuration saved successfully!" + (pskChanged
                ? "\n\nThe new pre-shared key applies to new connections. Every client must be configured with the same key."
                : ""));
    }

    public void showNotificationSettingsDialog() {
        NotifySettings edited = NotificationSettingsDialog.show(window, config.getNotify(), serverName, config.getScreenshotInterval());
        if (edited == null) return;
        ServerConfig c = config.copy();
        c.setNotify(edited);
        setConfig(c);
        saveConfig();
        broadcastConfig();
        Log.info("Notification settings saved");
    }

    public void setDelayTime() {
        JPanel panel = new JPanel(new GridLayout(1, 2));
        JTextField delayField = new JTextField(20);
        delayField.setText(String.valueOf(config.getScreenshotInterval()));
        panel.add(new JLabel("Interval Time (s):"));
        panel.add(delayField);
        int results = JOptionPane.showConfirmDialog(window, panel, "Interval Time", JOptionPane.OK_CANCEL_OPTION);
        if (results != JOptionPane.OK_OPTION) return;
        String delayStr = delayField.getText().trim();
        if (delayStr.isEmpty()) return;
        int interval;
        try {
            interval = Integer.parseInt(delayStr);
            if (interval < 1 || interval > 3600) {
                JOptionPane.showMessageDialog(window, "Interval must be between 1 and 3600 seconds!", "Invalid Interval",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(window, "Invalid interval value! Please enter a number.", "Invalid Interval",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        ServerConfig c = config.copy();
        c.setScreenshotInterval(interval);
        setConfig(c);
        saveConfig();
        for (ClientSession s : sessions.values()) s.sendControl(c.toIntervalCommand());
        JOptionPane.showMessageDialog(window, "Interval time updated to " + interval + " seconds.", "Success",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Global notifications switch; returns the new state. */
    public boolean toggleNotifications() {
        notificationsOn = !notificationsOn;
        boolean on = notificationsOn;
        clientNotifications.replaceAll((k, v) -> on);
        window.repaintTable();
        JOptionPane.showMessageDialog(window, "Notifications turned " + (on ? "ON" : "OFF"), "Notifications",
                JOptionPane.INFORMATION_MESSAGE);
        Log.info("Notification setting changed to: " + (on ? 1 : 0));
        return on;
    }

    public void addManualClient(String displayName, String ip) {
        if (names.containsKey(ip)) return;
        names.put(ip, displayName);
        clientNotifications.put(ip, true);
        long now = System.currentTimeMillis();
        db.execute(() -> clientRepo.insert(ip, displayName, ip, now));
        window.addClient(ip, displayName, sessions.containsKey(ip) ? ClientTableModel.STATUS_CONNECTED
                : ClientTableModel.STATUS_DISCONNECTED, lastSeenNow(), "", vpn.get(ip), new Dimension(1920, 1080));
        if (sessions.containsKey(ip)) window.setOffline(ip, false, null);
    }

    /**
     * Sync button: sends the client list (names) and the tag ranges to every alternative server
     * (observers listen on {@link SyncPayload#DEFAULT_PORT}); shows what each one answered.
     */
    private static final ExecutorService SYNC = Threads.pool("sync", 1);

    public void syncAlternativeServers() {
        List<String> targets = new ArrayList<>(config.getAlternativeIPs());
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(window, "No alternative servers are set.\nAdd them under Edit > Server Configuration > Alternative IPs.",
                    "Sync", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SyncPayload payload = new SyncPayload(new java.util.TreeMap<>(names), config.getNotify().tags);
        String psk = config.getPsk();
        SYNC.execute(() -> {
            List<String> results = SyncSender.send(targets, Integer.getInteger("srd.syncPort", SyncPayload.DEFAULT_PORT), psk, payload);
            Log.info("[SYNC] " + names.size() + " client(s) sent: " + results);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window,
                    names.size() + " client(s) sent:\n\n" + String.join("\n", results), "Sync to alternative servers",
                    JOptionPane.INFORMATION_MESSAGE));
        });
    }

    public void renameClient(String clientId, String newName) {
        if (clientId == null) return;
        Log.info("Updating display name for client " + clientId + " to " + newName);
        names.put(clientId, newName.isEmpty() ? clientId : newName);
        db.execute(() -> clientRepo.updateDisplayName(clientId, newName));
    }

    public void toggleClientNotifications(String clientId) {
        boolean newState = !clientNotifications.getOrDefault(clientId, true);
        clientNotifications.put(clientId, newState);
        db.execute(() -> clientRepo.updateNotification(clientId, newState));
        window.repaintTable();
        JOptionPane.showMessageDialog(window, "Notifications " + (newState ? "enabled" : "disabled") + " for client: " + clientId);
    }

    public void editClientDirectory(String clientId) {
        String[] dirs = ClientDirectoryDialog.show(window, clientId, clientScreenshotDirs.get(clientId), clientRecordingDirs.get(clientId));
        if (dirs == null) return;
        String screenshotDir = dirs[0];
        String recordingDir = dirs[1];
        if (screenshotDir.isEmpty()) clientScreenshotDirs.remove(clientId);
        else clientScreenshotDirs.put(clientId, screenshotDir);
        if (recordingDir.isEmpty()) clientRecordingDirs.remove(clientId);
        else clientRecordingDirs.put(clientId, recordingDir);
        db.execute(() -> clientRepo.updateDirectories(clientId, screenshotDir.isEmpty() ? null : screenshotDir,
                recordingDir.isEmpty() ? null : recordingDir));
        JOptionPane.showMessageDialog(window, "Directory settings saved for client: " + clientId, "Success",
                JOptionPane.INFORMATION_MESSAGE);
        window.repaintTable();
    }

    public void deleteClient(String clientId) {
        stopRecording(clientId);
        LiveViewWindow w = liveWindows.remove(clientId);
        if (w != null) w.dispose();
        ClientSession s = sessions.remove(clientId);
        if (s != null) s.close("client deleted on the server");
        names.remove(clientId);
        clientNotifications.remove(clientId);
        clientScreenshotDirs.remove(clientId);
        clientRecordingDirs.remove(clientId);
        vpn.remove(clientId);
        hostNames.remove(clientId);
        lastSeenAtStart.remove(clientId);
        lastNotifiedStatus.remove(clientId);
        alerts.clear(clientId);
        notifier.forget(clientId);
        db.execute(() -> clientRepo.delete(clientId));
        window.removeClient(clientId);
        Log.info("Deleted client " + clientId);
    }

    public void showClientRecords(String clientId) {
        if (clientId != null) RecordsWindow.show(window, clientId, db, eventRepo, this::refreshRecordsCell);
    }

    public void clearAllRecords() {
        int confirm = JOptionPane.showConfirmDialog(window, "Clear all logs for all clients?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        db.execute(() -> {
            eventRepo.clearAll();
            ui(() -> {
                for (String id : window.clientIds()) window.setRecordsCell(id, "");
                JOptionPane.showMessageDialog(window, "All records cleared.", "Info", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    public void clearAllScreenshots() {
        int confirm = JOptionPane.showConfirmDialog(window, "Clear all screenshots for all clients?", "Confirm",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        List<String> roots = new ArrayList<>();
        roots.add("Screenshots");
        roots.addAll(clientScreenshotDirs.values());
        roots.add("Recordings");
        roots.addAll(clientRecordingDirs.values());
        Threads.start("clear-all-screenshots", () -> {
            for (String root : roots) {
                if (root == null || root.isEmpty()) continue;
                File[] clientDirs = new File(root).listFiles(File::isDirectory);
                if (clientDirs == null) continue;
                for (File clientDir : clientDirs) {
                    File[] dateDirs = clientDir.listFiles(File::isDirectory);
                    if (dateDirs == null) continue;
                    for (File dateDir : dateDirs) Images.deleteJpgFiles(dateDir.getAbsolutePath());
                }
            }
            ui(() -> JOptionPane.showMessageDialog(window, "All screenshots cleared.", "Info", JOptionPane.INFORMATION_MESSAGE));
        });
    }

    public void exportDatabase() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Database");
        fileChooser.setSelectedFile(new File("clients_backup.db"));
        if (fileChooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return;
        File destFile = fileChooser.getSelectedFile();
        // On the db thread: no write can be in progress while the file is copied.
        db.execute(() -> {
            File sourceFile = new File(db.path());
            try {
                if (!sourceFile.exists()) {
                    ui(() -> JOptionPane.showMessageDialog(window, "Source database file not found!", "Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }
                Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                ui(() -> JOptionPane.showMessageDialog(window, "Database exported successfully to: " + destFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> JOptionPane.showMessageDialog(window, "Error exporting database: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    public void importDatabase() {
        int confirm = JOptionPane.showConfirmDialog(window, "Importing database will overwrite current data. Continue?",
                "Confirm Import", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Import Database");
        if (fileChooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
        File sourceFile = fileChooser.getSelectedFile();
        db.execute(() -> {
            try {
                backup.backupTo(new File("clients_backup_" + System.currentTimeMillis() + ".db"));
                if (!backup.importFrom(sourceFile)) {
                    ui(() -> JOptionPane.showMessageDialog(window, "Source database does not contain a clients table!",
                            "Import Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }
                vpn.clear();
                List<ClientRow> rows = loadRowsForUi();
                ui(() -> {
                    reloadWindow(rows);
                    JOptionPane.showMessageDialog(window, "Database imported successfully!");
                });
            } catch (Exception e) {
                Log.error("Import failed", e);
                ui(() -> JOptionPane.showMessageDialog(window, "Error importing database: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    /** EDT: rebuild the table/thumbnails after an import, keeping live connections' state. */
    private void reloadWindow(List<ClientRow> rows) {
        Map<String, VpnGuardStatus> v = new HashMap<>(vpn);
        window.loadRows(rows, v);
        for (ClientSession s : sessions.values()) {
            if (window.hasClient(s.clientId())) {
                window.setStatus(s.clientId(), ClientTableModel.STATUS_CONNECTED);
                window.setOffline(s.clientId(), false, null);
            }
        }
        for (String key : recorders.keySet()) if (!key.startsWith(PEER_KEY_PREFIX)) window.setRecording(key, true);
    }

    /** True while any recording is running (an update restart would lose it). */
    public boolean hasActiveRecordings() {
        return !recorders.isEmpty();
    }

    public void exit() {
        Log.info("Server exiting");
        if (notifier != null) notifier.stop();
        for (String key : new ArrayList<>(recorders.keySet())) {
            Log.warn("Exit while recording " + key + "; the recording is not encoded");
        }
        for (ClientSession s : sessions.values()) s.close("server shutting down");
        for (PeerSession p : peers.values()) p.close("server shutting down");
        if (listener != null) listener.stop();
        if (window != null) {
            window.removeTrayIcon();
            window.dispose();
        }
        System.exit(0);
    }
}
