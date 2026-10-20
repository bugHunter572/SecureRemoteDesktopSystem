package com.srd.client.overlay;

import com.srd.client.capture.ScreenLayout;
import com.srd.common.annotation.AnnotationModel;
import com.srd.common.annotation.PenMessage;
import com.srd.common.annotation.Stroke;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.util.Log;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Host-side hub for viewer annotations. Strokes are stored in "desktop space": coordinates
 * normalized over the whole multi-monitor desktop. Each viewer may be watching only one monitor,
 * so its pen messages are converted from that viewer's stream region on the way in and out.
 * The hub renders the strokes on the real screen through {@link AnnotationOverlay} and relays
 * them to every other viewer.
 */
public final class AnnotationHub {
    /** Where one viewer's normalized coordinates live on the desktop (physical px). */
    public interface RegionSource {
        Rectangle physicalRegion();
    }

    private final AnnotationModel model = new AnnotationModel();
    private final Map<MuxConnection, RegionSource> viewers = new ConcurrentHashMap<>();
    private final Supplier<ScreenLayout> layout;
    private final AnnotationOverlay overlay;

    public AnnotationHub(Supplier<ScreenLayout> layout) {
        this.layout = layout;
        this.overlay = new AnnotationOverlay(model, layout);
    }

    /** Notified once (true=drawings hidden from capture, false=not) when the overlay first appears. */
    public void onStealthStatus(java.util.function.Consumer<Boolean> cb) {
        overlay.onStatus(cb);
    }

    public AnnotationModel model() {
        return model;
    }

    public void addViewer(MuxConnection c, RegionSource region) {
        viewers.put(c, region);
        // Late joiner: replay what is on screen right now.
        for (byte[] m : model.snapshotMessages()) {
            c.send(MessageType.PEN, convert(m, desktop(), region.physicalRegion()));
        }
    }

    public void removeViewer(MuxConnection c) {
        viewers.remove(c);
    }

    /** A PEN message from viewer {@code from}. */
    public void onPen(MuxConnection from, byte[] payload) {
        RegionSource src = viewers.get(from);
        Rectangle desk = desktop();
        byte[] global = src == null ? payload : convert(payload, src.physicalRegion(), desk);
        try {
            model.apply(PenMessage.decode(global));
        } catch (RuntimeException e) {
            Log.warn("bad pen message: " + e);
            return;
        }
        overlay.ensureShown();
        for (Map.Entry<MuxConnection, RegionSource> e : viewers.entrySet()) {
            if (e.getKey() == from || !e.getKey().isOpen()) continue;
            e.getKey().send(MessageType.PEN, convert(global, desk, e.getValue().physicalRegion()));
        }
    }

    /** Clears everything locally (tray menu) and tells all viewers. */
    public void clearAll() {
        byte[] msg = PenMessage.clear(0);
        model.apply(PenMessage.decode(msg));
        for (MuxConnection c : viewers.keySet()) c.send(MessageType.PEN, msg);
    }

    public boolean isCaptureExcluded() {
        return overlay.isCaptureExcluded();
    }

    private Rectangle desktop() {
        return layout.get().physicalRegion(-1);
    }

    /** Re-expresses a pen message's coordinates from region {@code from} into region {@code to}. */
    static byte[] convert(byte[] payload, Rectangle from, Rectangle to) {
        if (from == null || to == null || from.equals(to)) return payload;
        PenMessage m = PenMessage.decode(payload);
        if (m.op != PenMessage.BEGIN && m.op != PenMessage.POINTS) return payload;
        float[] p = m.points.clone();
        for (int i = 0; i + 1 < p.length; i += 2) {
            double x = from.x + p[i] * from.width;
            double y = from.y + p[i + 1] * from.height;
            p[i] = (float) ((x - to.x) / to.width);
            p[i + 1] = (float) ((y - to.y) / to.height);
        }
        if (m.op == PenMessage.POINTS) return PenMessage.points(m.author, m.seq, p);
        float width = m.width * from.width / Math.max(1, to.width);
        Stroke s = new Stroke(m.strokeId(), m.author, m.authorName, m.tool, m.argb, width, m.fadeSeconds, m.text, m.image);
        return PenMessage.begin(s, m.seq, p);
    }

    public void dispose() {
        overlay.dispose();
    }

    /** For diagnostics. */
    public List<Stroke> strokes() {
        return model.snapshot();
    }
}
