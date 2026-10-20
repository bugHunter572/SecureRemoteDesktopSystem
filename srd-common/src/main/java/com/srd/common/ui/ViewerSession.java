package com.srd.common.ui;

import com.srd.common.annotation.AnnotationModel;
import com.srd.common.annotation.PenController;
import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.AudioPlayer;
import com.srd.common.input.KeyboardGrabber;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.protocol.WireReader;
import com.srd.common.protocol.WireWriter;
import com.srd.common.util.Log;
import com.srd.common.video.FrameBuffer;
import com.srd.common.video.VideoDecoder;
import java.awt.Rectangle;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Viewer half of a live session over one {@link MuxConnection}: video decode, frame acks, cursor,
 * audio playback, input and pen. The connection's owner forwards messages to {@link #handle};
 * anything not consumed (thumbnails, status, control…) stays the owner's business. Usable
 * headless (e.g. the server recording a client without a window open).
 */
public final class ViewerSession {
    private final MuxConnection conn;
    private final RemoteScreenPanel panel;
    private final VideoDecoder decoder;
    private final AnnotationModel annotations = new AnnotationModel();
    private final PenController pen;
    private final CopyOnWriteArrayList<IntConsumer> frameListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ScreenInfo>> screenListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<AudioPacket>> audioListeners = new CopyOnWriteArrayList<>();
    private boolean audioSubscribed;
    private final String name;

    private volatile ScreenInfo screenInfo;
    private volatile StreamRequest stream = StreamRequest.stop();
    private volatile AudioPlayer player;
    private volatile boolean control;
    private volatile boolean penMode;
    private volatile boolean closed;

    // stats
    private volatile long statWindowStart = System.currentTimeMillis();
    private volatile int framesInWindow;
    private volatile double fps;
    private volatile long bytesAtWindowStart;
    private volatile double kbps;
    private volatile double decodeMs;

    public ViewerSession(MuxConnection conn, String viewerName) {
        this.conn = conn;
        this.name = viewerName;
        this.panel = new RemoteScreenPanel();
        this.pen = new PenController(annotations, viewerName, p -> conn.send(MessageType.PEN, p));
        panel.setAnnotations(annotations, pen);
        panel.setInputSender(p -> conn.send(MessageType.INPUT, p));
        panel.setStatsSupplier(this::statsText);
        this.decoder = new VideoDecoder(new VideoDecoder.Sink() {
            @Override
            public void onFrameBuffer(FrameBuffer fb) {
                panel.onFrameBuffer(fb);
            }

            @Override
            public void onDamage(Rectangle damage) {
                panel.onDamage(damage);
            }

            @Override
            public void onFrameComplete(int frameId, long decodeNanos) {
                conn.send(MessageType.FRAME_ACK, new WireWriter(4).i32(frameId).toByteArray());
                countFrame(decodeNanos);
                for (IntConsumer l : frameListeners) l.accept(frameId);
                panel.onFrameComplete(frameId, decodeNanos);
            }
        });
    }

    public MuxConnection connection() {
        return conn;
    }

    public RemoteScreenPanel panel() {
        return panel;
    }

    public AnnotationModel annotations() {
        return annotations;
    }

    public PenController pen() {
        return pen;
    }

    public ScreenInfo screenInfo() {
        return screenInfo;
    }

    public FrameBuffer frameBuffer() {
        return decoder.frameBuffer();
    }

    public StreamRequest currentStream() {
        return stream;
    }

    public void addFrameListener(IntConsumer l) {
        frameListeners.add(l);
    }

    public void removeFrameListener(IntConsumer l) {
        frameListeners.remove(l);
    }

    public void addScreenInfoListener(Consumer<ScreenInfo> l) {
        screenListeners.add(l);
        ScreenInfo si = screenInfo;
        if (si != null) l.accept(si);
    }

    public void removeScreenInfoListener(Consumer<ScreenInfo> l) {
        screenListeners.remove(l);
    }

    public void setKeyboardGrabber(KeyboardGrabber g) {
        panel.setKeyboardGrabber(g);
    }

    /**
     * Dispatches a message from the host.
     *
     * @return true if consumed
     */
    public boolean handle(int type, byte[] p) {
        if (closed) return false;
        switch (type) {
            case MessageType.SCREEN_INFO: {
                ScreenInfo si = ScreenInfo.decode(p);
                screenInfo = si;
                decoder.onScreenInfo(si);
                for (Consumer<ScreenInfo> l : screenListeners) l.accept(si);
                return true;
            }
            case MessageType.VIDEO:
                decoder.submit(p);
                return true;
            case MessageType.CURSOR_POS: {
                WireReader r = new WireReader(p);
                int nx = r.u16(), ny = r.u16();
                boolean vis = r.u8() != 0;
                panel.setRemoteCursor(nx, ny, vis);
                return true;
            }
            case MessageType.CURSOR_SHAPE:
                panel.setRemoteCursorKind(CursorKind.of(new WireReader(p).u8()));
                return true;
            case MessageType.AUDIO: {
                AudioPlayer pl = player;
                if (pl == null && audioListeners.isEmpty()) return true;
                AudioPacket pkt = AudioPacket.decode(p);
                if (pl != null) pl.offer(pkt);
                for (Consumer<AudioPacket> l : audioListeners) l.accept(pkt);
                return true;
            }
            case MessageType.PEN:
                annotations.apply(p);
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ controls

    public void startStream(int preset, int monitor, int maxFps) {
        stream = new StreamRequest(true, preset, monitor, maxFps);
        conn.send(MessageType.STREAM_REQUEST, stream.encode());
        panel.setStatusMessage(screenInfo == null ? "Waiting for video…" : null);
    }

    public void stopStream() {
        stream = StreamRequest.stop();
        if (conn.isOpen()) conn.send(MessageType.STREAM_REQUEST, stream.encode());
    }

    public void setPreset(int preset) {
        StreamRequest s = stream;
        if (s.enabled) startStream(preset, s.monitor, s.maxFps);
    }

    public void setMonitor(int monitor) {
        StreamRequest s = stream;
        if (s.enabled) startStream(s.preset, monitor, s.maxFps);
    }

    public boolean isAudioEnabled() {
        return player != null;
    }

    /** Turns local playback of the host's audio on/off (subscribing to it as needed). */
    public synchronized void setAudioEnabled(boolean on) {
        if (on == (player != null)) return;
        if (on) {
            player = new AudioPlayer(name);
        } else {
            AudioPlayer p = player;
            player = null;
            if (p != null) p.close();
        }
        updateAudioSubscription();
    }

    /** Receives every audio packet (e.g. a recorder), independent of playback. Subscribes as needed. */
    public synchronized void addAudioListener(Consumer<AudioPacket> l) {
        audioListeners.add(l);
        updateAudioSubscription();
    }

    public synchronized void removeAudioListener(Consumer<AudioPacket> l) {
        audioListeners.remove(l);
        updateAudioSubscription();
    }

    /** Host audio is requested while playback is on or any listener exists. */
    private void updateAudioSubscription() {
        boolean want = !closed && (player != null || !audioListeners.isEmpty());
        if (want == audioSubscribed) return;
        audioSubscribed = want;
        if (conn.isOpen()) conn.send(MessageType.AUDIO_SUB, new byte[] {(byte) (want ? 1 : 0)});
    }

    public boolean isControlEnabled() {
        return control;
    }

    public void setControlEnabled(boolean on) {
        control = on;
        applyMode();
    }

    public boolean isPenEnabled() {
        return penMode;
    }

    public void setPenEnabled(boolean on) {
        penMode = on;
        applyMode();
    }

    private void applyMode() {
        panel.setMode(penMode ? RemoteScreenPanel.Mode.PEN : control ? RemoteScreenPanel.Mode.CONTROL : RemoteScreenPanel.Mode.VIEW);
    }

    /** Sends a CONTROL text command to the host. */
    public void sendControl(String cmd) {
        conn.sendText(MessageType.CONTROL, cmd);
    }

    // ------------------------------------------------------------------ stats

    private void countFrame(long decodeNanos) {
        framesInWindow++;
        decodeMs = decodeMs * 0.8 + (decodeNanos / 1e6) * 0.2;
        long now = System.currentTimeMillis();
        long dt = now - statWindowStart;
        if (dt >= 1000) {
            fps = framesInWindow * 1000.0 / dt;
            long b = conn.bytesIn();
            kbps = (b - bytesAtWindowStart) * 8.0 / dt;
            bytesAtWindowStart = b;
            framesInWindow = 0;
            statWindowStart = now;
        }
    }

    public double fps() {
        return System.currentTimeMillis() - statWindowStart > 2500 ? 0 : fps;
    }

    public String statsText() {
        ScreenInfo si = screenInfo;
        AudioPlayer p = player;
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%s  %.0f fps  %.1f Mbit/s", si == null ? "-" : si.width + "x" + si.height, fps(), kbps / 1000));
        b.append(String.format(Locale.ROOT, "\nRTT %.1f ms   decode %.1f ms", conn.rttMs(), decodeMs));
        if (p != null) b.append(String.format(Locale.ROOT, "\naudio buffer %d ms  underruns %d", p.bufferedMs(), p.underruns()));
        return b.toString();
    }

    public Supplier<String> statsSupplier() {
        return this::statsText;
    }

    /** Stops stream/audio subscriptions and frees resources; the connection itself stays open. */
    public void close() {
        if (closed) return;
        closed = true;
        try {
            panel.releaseAllInput();
            stopStream();
            audioListeners.clear();
            setAudioEnabled(false);
            synchronized (this) {
                if (audioSubscribed && conn.isOpen()) conn.send(MessageType.AUDIO_SUB, new byte[] {0});
                audioSubscribed = false;
            }
        } catch (Exception e) {
            Log.warn("viewer close: " + e);
        }
        decoder.close();
        panel.dispose();
    }
}
