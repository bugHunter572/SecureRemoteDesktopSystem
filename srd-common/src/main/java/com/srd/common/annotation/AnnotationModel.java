package com.srd.common.annotation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * All strokes currently shown on one shared screen (thread-safe). Both the viewer, which renders
 * them over the remote image, and the host overlay, which renders them on the real desktop, hold
 * one, and both apply the same {@link PenMessage}s to it.
 */
public final class AnnotationModel {
    /** Laser trail points older than this disappear; the whole laser stroke goes this long after it ends. */
    public static final long LASER_TRAIL_MS = 700;

    private final Map<Long, Stroke> strokes = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    /** Applies a decoded message (from the network or generated locally). */
    public void apply(PenMessage m) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            switch (m.op) {
                case PenMessage.BEGIN: {
                    Stroke s = new Stroke(m.strokeId(), m.author, m.authorName, m.tool, m.argb, m.width, m.fadeSeconds, m.text, m.image);
                    for (int i = 0; i + 1 < m.points.length; i += 2) s.add(m.points[i], m.points[i + 1], now);
                    strokes.put(s.id, s);
                    break;
                }
                case PenMessage.POINTS: {
                    Stroke s = strokes.get(m.strokeId());
                    if (s == null) return;
                    for (int i = 0; i + 1 < m.points.length; i += 2) s.add(m.points[i], m.points[i + 1], now);
                    break;
                }
                case PenMessage.END: {
                    Stroke s = strokes.get(m.strokeId());
                    if (s != null) s.end(now);
                    break;
                }
                case PenMessage.REMOVE:
                    if (strokes.remove(m.strokeId()) == null) return;
                    break;
                case PenMessage.CLEAR:
                    if (m.author == 0) {
                        strokes.clear();
                    } else {
                        strokes.values().removeIf(s -> s.authorId == m.author);
                    }
                    break;
                default:
                    return;
            }
        }
        changed();
    }

    public void apply(byte[] payload) {
        apply(PenMessage.decode(payload));
    }

    /** Adds a locally created stroke (the caller also sends BEGIN). */
    public void addLocal(Stroke s) {
        synchronized (this) {
            strokes.put(s.id, s);
        }
        changed();
    }

    public void touch() {
        changed();
    }

    public synchronized Stroke get(long id) {
        return strokes.get(id);
    }

    public synchronized List<Stroke> snapshot() {
        return new ArrayList<>(strokes.values());
    }

    public synchronized boolean isEmpty() {
        return strokes.isEmpty();
    }

    /** Strokes hit by the eraser at (x,y). */
    public synchronized List<Stroke> hit(float x, float y, float tol, float aspect) {
        List<Stroke> out = new ArrayList<>();
        for (Stroke s : strokes.values()) {
            if (s.tool != PenTool.LASER && s.near(x, y, tol, aspect)) out.add(s);
        }
        return out;
    }

    /** Last stroke by {@code author}, for undo. */
    public synchronized Stroke lastBy(int author) {
        Stroke last = null;
        for (Stroke s : strokes.values()) if (s.authorId == author && s.tool != PenTool.LASER) last = s;
        return last;
    }

    /**
     * Expires faded and laser strokes.
     *
     * @return true while something is animating (lasers, fading strokes), so the renderer knows
     *         to keep repainting
     */
    public boolean tick(long now) {
        boolean animating = false;
        boolean removed = false;
        synchronized (this) {
            Iterator<Stroke> it = strokes.values().iterator();
            while (it.hasNext()) {
                Stroke s = it.next();
                long ended = s.endedAtMs();
                if (s.tool == PenTool.LASER) {
                    if (ended != 0 && now - ended > LASER_TRAIL_MS) {
                        it.remove();
                        removed = true;
                    } else {
                        animating = true;
                    }
                } else if (s.fadeSeconds > 0 && ended != 0) {
                    if (now - ended > s.fadeSeconds * 1000L) {
                        it.remove();
                        removed = true;
                    } else {
                        animating = true;
                    }
                }
            }
        }
        if (removed) changed();
        return animating;
    }

    /** Messages that recreate the current state, for a viewer that joins late. */
    public synchronized List<byte[]> snapshotMessages() {
        List<byte[]> out = new ArrayList<>();
        for (Stroke s : strokes.values()) {
            if (s.tool == PenTool.LASER) continue;
            int seq = (int) s.id;
            out.add(PenMessage.begin(s, seq, s.pointsXY()));
            if (s.isEnded()) out.add(PenMessage.end(s.authorId, seq));
        }
        return out;
    }
}
