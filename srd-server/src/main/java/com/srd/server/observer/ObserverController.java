package com.srd.server.observer;

import com.srd.client.ClientConfig;
import com.srd.client.ClientContext;
import com.srd.client.audio.AudioConfig;
import com.srd.client.net.PeerHost;
import com.srd.common.crypto.SessionCrypto;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.VpnGuardStatus;
import com.srd.common.ui.RemoteViewerFrame;
import com.srd.common.ui.ViewerSession;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.srd.server.ServerApp;
import com.srd.server.net.ClientListener;
import com.srd.server.net.ClientSession;
import com.srd.server.net.NetUtil;
import com.srd.server.net.PeerSession;
import com.srd.server.net.RemoteSession;
import com.srd.server.net.SessionEvents;
import com.srd.server.recording.Recorder;
import com.srd.server.sync.SyncPayload;
import com.srd.server.ui.ObserverWindow;
import com.srd.server.ui.Toast;
import com.srd.server.util.Images;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The watch-only server: accepts clients that list this PC as an alternative server, keeps their
 * latest picture and shows it. It never sends the clients any setting except the picture interval
 * (per connection), so it cannot disturb the main server's configuration; nothing is stored, no
 * alerts are raised, nothing is recorded and no live view can control or hear the client.
 */
public final class ObserverController implements ClientListener.Handler, SessionEvents {
    private final ObserverSettings cfg;
    private final byte[] pskKey;
    private final Hello localHello;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, RemoteViewerFrame> live = new ConcurrentHashMap<>(); // EDT
    private final ExecutorService thumbPool = Threads.pool("thumb-decode", 2);
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(Threads.factory("disk-io", Thread.NORM_PRIORITY));
    private final ScheduledExecutorService recScheduler =
            Executors.newSingleThreadScheduledExecutor(Threads.factory("rec-sampler", Thread.NORM_PRIORITY));
    private final Map<String, Recorder> recorders = new ConcurrentHashMap<>(); // EDT
    private volatile ObserverWindow window;
    /** Display names pushed by the main server's Sync (client id to name); survive restarts in {@link #SYNC_FILE}. */
    private final Map<String, String> syncedNames = new ConcurrentHashMap<>();
    private static final String SYNC_FILE = "synced-clients.properties";
    private ObserverSyncApi syncApi;
    private ClientListener listener;
    private ClientContext shareCtx;
    private PeerHost peerHost;

    ObserverController(ObserverSettings cfg) {
        this.cfg = cfg;
        this.pskKey = SessionCrypto.pskKey(cfg.psk);
        String name = NetUtil.hostName();
        this.localHello = Hello.builder(Hello.ROLE_SERVER)
                .put(Hello.K_NAME, name)
                .put(Hello.K_ID, name)
                .put(Hello.K_VERSION, ServerApp.VERSION)
                .put(Hello.K_OS, Os.tag())
                .build();
    }

    /** EDT: attaches the window and starts listening. */
    void attach(ObserverWindow w) {
        this.window = w;
        w.setRecordingHandlers(this::startRecording, this::stopRecording, recorders::containsKey);
        listener = new ClientListener(cfg.port, this);
        listener.start();
        loadSynced();
        try {
            syncApi = new ObserverSyncApi(cfg.syncPort, cfg.psk, this::applySync);
        } catch (IOException e) {
            Log.warn("Sync endpoint not available on port " + cfg.syncPort + ": " + e);
        }
    }

    // ------------------------------------------------------------------ sync from the main server

    private void loadSynced() {
        File f = new File(SYNC_FILE);
        if (!f.isFile()) return;
        try {
            SyncPayload p = SyncPayload.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            syncedNames.putAll(p.clients);
            SwingUtilities.invokeLater(() -> window.applySync(syncedNames, sessions.keySet()));
            Log.info("Loaded " + p.clients.size() + " synced client(s) from " + SYNC_FILE);
        } catch (IOException | RuntimeException e) {
            Log.warn("Cannot read " + SYNC_FILE + ": " + e);
        }
    }

    /** HTTP thread: the main server's client list arrived. */
    private void applySync(SyncPayload p) {
        try {
            java.nio.file.Files.write(new File(SYNC_FILE).toPath(), p.toText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Cannot save " + SYNC_FILE + ": " + e);
        }
        syncedNames.clear();
        syncedNames.putAll(p.clients);
        Log.info("[SYNC] received " + p.clients.size() + " client(s) from the main server");
        SwingUtilities.invokeLater(() -> window.applySync(syncedNames, sessions.keySet()));
    }

    private String nameOf(String id, String hello) {
        String n = syncedNames.get(id);
        return n == null || n.isEmpty() ? hello : n;
    }

    // ------------------------------------------------------------------ recording (EDT)

    /** Start Recording button: video of the selected client in {@code Recordings/<ip>_<name>/<yyyyMMdd>/}. */
    void startRecording(String clientId) {
        if (recorders.containsKey(clientId)) return;
        ClientSession s = sessions.get(clientId);
        if (s == null) {
            JOptionPane.showMessageDialog(window, "No connected client selected!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File dateDir = new File(new File(new File("Recordings"), clientId + "_" + safeName(window.displayName(clientId))),
                new SimpleDateFormat("yyyyMMdd").format(new Date()));
        ViewerSession v = s.acquireViewer(RemoteSession.USER_RECORD);
        Recorder r = new Recorder(clientId, v, dateDir, false, ioExecutor, recScheduler, this::onRecordingFinished);
        recorders.put(clientId, r);
        r.start();
        window.setRecording(clientId, true);
        Toast.show("Recording started", clientId + "\n" + dateDir.getAbsolutePath(), new Color(0xC62828), 3000);
    }

    void stopRecording(String clientId) {
        Recorder r = recorders.remove(clientId);
        if (r == null) return;
        r.stop();
        ClientSession s = sessions.get(clientId);
        if (s != null) s.releaseViewer(RemoteSession.USER_RECORD);
        window.setRecording(clientId, false);
    }

    private void onRecordingFinished(Recorder r, File output) {
        if (output != null) Toast.show("Recording saved", output.getAbsolutePath(), new Color(0x2E7D32), 6000);
        else Toast.show("Recording not saved", r.key() + ": see logs/observer.log (frames are kept in " + r.dateDir() + ")",
                new Color(0xF57C00), 8000);
    }

    private static String safeName(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // ------------------------------------------------------------------ broadcasting this screen

    /**
     * Starts or stops "Broadcasting" exactly like a client does (peer port, viewers watch this PC's
     * screen), but viewers can neither control this PC, draw on it nor hear it.
     *
     * @return null on success, otherwise a short reason
     */
    public String setBroadcast(boolean on) {
        try {
            if (on) {
                if (peerHost == null) {
                    ClientConfig cc = ClientConfig.loadDefault();
                    cc.set("security.psk", cfg.psk);
                    cc.set("peer.port", String.valueOf(cfg.peerPort));
                    cc.set("peer.allowControl", "false");
                    cc.set("peer.allowDraw", "false");
                    AudioConfig.setMicEnabled(false);
                    AudioConfig.setSystemAudioEnabled(false);
                    shareCtx = new ClientContext(cc);
                    shareCtx.serverControlEnabled = false;
                    peerHost = new PeerHost(shareCtx, (who, connected) -> {
                        Log.info("Broadcast viewer " + (connected ? "connected: " : "left: ") + who);
                        SwingUtilities.invokeLater(() -> window.setViewers(PeerHost.viewerCount()));
                    });
                }
                peerHost.start();
            } else if (peerHost != null) {
                peerHost.stop();
                SwingUtilities.invokeLater(() -> window.setViewers(0));
            }
            return null;
        } catch (Exception | LinkageError e) {
            Log.warn("Broadcast " + (on ? "start" : "stop") + " failed: " + e);
            return String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        }
    }

    // ------------------------------------------------------------------ ClientListener.Handler

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
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window, "Cannot listen on port " + port + ": " + e.getMessage()
                + "\nIs another server or observer already using it?", "Observer error", JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void onOutdatedClient(String ip) {
        // pre-v3 clients cannot connect; nothing to show
    }

    @Override
    public void onConnected(MuxConnection conn) {
        ClientSession s = new ClientSession(conn, this, thumbPool);
        String id = s.clientId();
        ClientSession previous = sessions.put(id, s);
        if (previous != null && previous != s) previous.close("replaced by a newer connection");
        conn.start(s);
        s.sendControl("CLIENT_STATE:REGISTERED");
        s.sendControl("SET_INTERVAL:" + cfg.intervalSec); // applies to this connection only
        Log.info("[CONN] Client connected: " + id + " (" + s.helloName() + ")");
        SwingUtilities.invokeLater(() -> {
            if (sessions.get(id) != s) return;
            window.addOrUpdate(id, nameOf(id, s.helloName()), s.helloScreen());
            window.setOffline(id, false, null);
        });
    }

    // ------------------------------------------------------------------ SessionEvents

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
        if (full == null || Images.isBlank(full)) return; // keep the previous picture
        Dimension size = new Dimension(full.getWidth(), full.getHeight());
        BufferedImage tile = Images.boundWidth(full, window.tileTargetWidth());
        SwingUtilities.invokeLater(() -> {
            if (sessions.get(id) == s) window.setThumbnail(id, tile, size);
        });
    }

    @Override
    public void onStatus(ClientSession s, Map<String, String> status) {
        VpnGuardStatus st = VpnGuardStatus.fromMap(status);
        if (st == null) return;
        String id = s.clientId();
        SwingUtilities.invokeLater(() -> {
            if (sessions.get(id) == s) window.setVpn(id, st);
        });
    }

    @Override
    public void onClientClosed(ClientSession s, String reason) {
        String id = s.clientId();
        boolean owner = sessions.remove(id, s);
        Log.info((owner ? "Client disconnected: " : "Stale connection ended: ") + id + " (" + reason + ")");
        if (!owner) return;
        SwingUtilities.invokeLater(() -> {
            if (sessions.containsKey(id)) return; // reconnected meanwhile
            window.setOffline(id, true, "Disconnected");
            stopRecording(id);
            window.clearSelectionIf(id);
            RemoteViewerFrame f = live.get(id);
            if (f != null && f.session().connection() == s.connection()) {
                f.session().panel().setStatusMessage("Connection lost");
            }
        });
    }

    @Override
    public void onPeerClosed(PeerSession s, String reason) {
        // the observer has no peer port
    }

    // ------------------------------------------------------------------ live view (EDT)

    /** Double-click on a tile: a watch-only window (no tool bar, no control, no audio). */
    public void openLiveView(String clientId) {
        ClientSession s = sessions.get(clientId);
        RemoteViewerFrame existing = live.get(clientId);
        if (existing != null) {
            if (s != null && s.viewer() == existing.session()) {
                existing.setVisible(true);
                existing.toFront();
                return;
            }
            existing.dispose(); // belongs to a dead connection; its onClose releases it
            live.remove(clientId, existing);
        }
        if (s == null) {
            JOptionPane.showMessageDialog(window, window.displayName(clientId) + " (" + clientId + ") is not connected.",
                    "Not connected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ViewerSession v = s.acquireViewer(RemoteSession.USER_LIVE);
        RemoteViewerFrame f = new RemoteViewerFrame(v, window.displayName(clientId) + " - " + clientId, true);
        f.setOnClose(() -> {
            live.remove(clientId, f);
            s.releaseViewer(RemoteSession.USER_LIVE);
            Log.info("Closed live view of " + clientId);
        });
        live.put(clientId, f);
        f.setVisible(true);
        f.toFront();
        Log.info("Opened watch-only view of " + clientId);
    }
}
