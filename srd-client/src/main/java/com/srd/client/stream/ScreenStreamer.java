package com.srd.client.stream;

import com.srd.client.capture.Capturers;
import com.srd.client.capture.CursorProbe;
import com.srd.client.capture.CursorState;
import com.srd.client.capture.Displays;
import com.srd.client.capture.ScreenCapturer;
import com.srd.client.capture.ScreenLayout;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.protocol.WireWriter;
import com.srd.common.ui.CursorKind;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.srd.common.video.FrameBuffer;
import com.srd.common.video.Quality;
import com.srd.common.video.TileCodec;
import com.srd.common.video.TileEncoder;
import com.srd.common.video.TileGrid;
import com.srd.common.video.VideoMessage;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live screen streaming to any number of viewers (the server console and peer viewers).
 *
 * <p>This is the main speed fix. The old client captured and JPEG-encoded the whole screen for
 * every frame, painted the cursor into it, and pushed frames into the socket as fast as it could
 * capture. Here:
 * <ul>
 *   <li><b>one capture loop per monitor selection</b> serves every viewer of it. On Windows it
 *       uses DXGI desktop duplication, which reports dirty rectangles and blocks until something
 *       actually changes;</li>
 *   <li><b>only changed 64×64 tiles</b> are encoded: lossless palette for text/UI, JPEG for
 *       photos and video, merged and encoded in parallel;</li>
 *   <li><b>ack pacing:</b> each viewer has at most {@link #MAX_IN_FLIGHT} frames in flight. While
 *       a viewer is busy, changes accumulate in its dirty-tile set and go out together in the
 *       next frame. A slow viewer sees fewer, always-current frames instead of a growing backlog;
 *       this is what kept latency low;</li>
 *   <li><b>refinement:</b> once a region has stayed static it is re-sent once at high quality,
 *       so text sharpens a moment after scrolling stops;</li>
 *   <li><b>the cursor is a separate tiny message</b>, so pointer motion never costs a video
 *       frame.</li>
 * </ul>
 */
public final class ScreenStreamer {
    public static final int MAX_IN_FLIGHT = 2;
    private static final long ACK_STALL_MS = 3000;

    private final Map<Integer, Group> groups = new ConcurrentHashMap<>();
    private volatile ScreenLayout layout = Displays.layout();

    /** One viewer's stream state. */
    public final class Subscription {
        final MuxConnection conn;
        volatile StreamRequest req;
        volatile Group group;
        boolean[] dirty;
        boolean[] refinePending;
        long[] changedAt;
        volatile boolean keyframe = true;
        int nextFrameId = 1;
        volatile int lastSent;
        volatile int lastAcked;
        long lastSendMs;
        long inFlightSinceMs;
        int lastCursorX = -1, lastCursorY = -1;
        boolean lastCursorVisible;
        CursorKind lastKind;

        Subscription(MuxConnection conn, StreamRequest req) {
            this.conn = conn;
            this.req = req;
        }

        public void ack(int frameId) {
            if (frameId > lastAcked) lastAcked = frameId;
            Group g = group;
            if (g != null) g.wake();
        }

        int inFlight() {
            return lastSent - lastAcked;
        }

        /** Region of the host desktop this viewer currently sees (for input mapping). */
        public Rectangle physicalRegion() {
            Group g = group;
            return g != null ? g.physical : layout.physicalRegion(-1);
        }

        public Rectangle logicalRegion() {
            Group g = group;
            return g != null ? g.logical : layout.logicalRegion(-1);
        }

        public int monitor() {
            StreamRequest r = req;
            return r == null ? ScreenInfo.ALL_MONITORS : r.monitor;
        }
    }

    public ScreenLayout layout() {
        return layout;
    }

    /** Starts, reconfigures or stops a viewer's stream according to {@code req}. */
    public Subscription apply(Subscription existing, MuxConnection conn, StreamRequest req) {
        if (!req.enabled) {
            if (existing != null) unsubscribe(existing);
            return null;
        }
        Subscription s = existing != null ? existing : new Subscription(conn, req);
        int key = groupKey(req.monitor);
        Group current = s.group;
        s.req = req;
        if (current != null && current.key == key) {
            synchronized (current) {
                s.keyframe = true; // preset change: resend everything at the new quality
            }
            current.wake();
            return s;
        }
        if (current != null) current.remove(s);
        Group g = groups.computeIfAbsent(key, Group::new);
        g.add(s);
        return s;
    }

    public void unsubscribe(Subscription s) {
        Group g = s.group;
        if (g != null) g.remove(s);
        s.group = null;
        s.conn.clearVideo();
    }

    private int groupKey(int monitor) {
        return monitor >= 0 && monitor < layout.monitors.size() ? monitor : ScreenInfo.ALL_MONITORS;
    }

    /**
     * A full-resolution picture of the whole desktop (for the server's periodic snapshot). Reuses
     * the live framebuffer when a whole-desktop stream is running, otherwise does a one-shot capture.
     */
    public BufferedImage snapshot() {
        Group g = groups.get(ScreenInfo.ALL_MONITORS);
        if (g != null) {
            BufferedImage img = g.snapshot();
            if (img != null && !isBlank(img)) return img;
        }
        ScreenLayout l = Displays.layout();
        layout = l;
        // DXGI first, then GDI and Robot: some GPUs/drivers hand DXGI a black desktop that the other
        // capturers still see. Nothing usable (no picture yet, or black everywhere) = null, so the
        // server keeps showing the previous screenshot instead of a black tile.
        for (String type : new String[] {null, "GDI", "Robot"}) {
            if (type != null && !Os.WINDOWS) break;
            try (ScreenCapturer c = type == null ? Capturers.open(l, -1) : Capturers.openType(type, l, -1)) {
                FrameBuffer fb = new FrameBuffer(c.width(), c.height());
                boolean got = false;
                for (int i = 0; i < 3 && !got; i++) got = c.grab(fb, 500).changed;
                if (!got) continue;
                BufferedImage img = fb.image();
                if (!isBlank(img)) return img;
            } catch (Exception e) {
                Log.warn("snapshot capture (" + (type == null ? "auto" : type) + ") failed: " + e);
            }
        }
        return null;
    }

    /** True if (a sample of) the picture is pure black: a failed capture, locked secure desktop or a display that is off. */
    static boolean isBlank(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int stepX = Math.max(1, w / 64), stepY = Math.max(1, h / 64);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ capture group

    private final class Group {
        final int key;
        final List<Subscription> subs = new CopyOnWriteArrayList<>();
        private final Object wakeLock = new Object();
        private boolean wakeFlag;
        volatile Rectangle physical;
        volatile Rectangle logical;
        private ScreenCapturer capturer;
        private FrameBuffer fb;
        private FrameBuffer ref;
        private TileGrid grid;
        private volatile Thread thread;
        private byte[] screenInfo;
        /** ref holds a real captured picture (it starts as an "impossible" fill that would show as black). */
        private volatile boolean hasPicture;
        private long openedAtMs;
        private boolean primeTried;

        Group(int key) {
            this.key = key;
        }

        synchronized void add(Subscription s) {
            s.group = this;
            int n = grid != null ? grid.count() : 0;
            s.dirty = new boolean[n];
            s.refinePending = new boolean[n];
            s.changedAt = new long[n];
            s.keyframe = true;
            s.lastCursorX = -1;
            subs.add(s);
            if (screenInfo != null) s.conn.send(MessageType.SCREEN_INFO, screenInfo);
            if (thread == null || !thread.isAlive()) {
                thread = Threads.start("screen-stream-" + (key == ScreenInfo.ALL_MONITORS ? "all" : "m" + key),
                        Thread.MAX_PRIORITY - 1, this::run);
            }
            wake();
        }

        synchronized void remove(Subscription s) {
            subs.remove(s);
            if (s.group == this) s.group = null;
            wake();
        }

        void wake() {
            synchronized (wakeLock) {
                wakeFlag = true;
                wakeLock.notifyAll();
            }
        }

        private void waitWake(long ms) throws InterruptedException {
            synchronized (wakeLock) {
                if (!wakeFlag && ms > 0) wakeLock.wait(ms);
                wakeFlag = false;
            }
        }

        BufferedImage snapshot() {
            synchronized (this) {
                return ref == null || !hasPicture ? null : ref.snapshot();
            }
        }

        private void open() throws Exception {
            closeCapturer();
            ScreenLayout l = Displays.layout();
            layout = l;
            int mon = key == ScreenInfo.ALL_MONITORS ? -1 : key;
            physical = l.physicalRegion(mon);
            logical = l.logicalRegion(mon);
            ScreenCapturer c = Capturers.open(l, mon);
            synchronized (this) {
                capturer = c;
                if (fb == null || fb.width != c.width() || fb.height != c.height()) {
                    fb = new FrameBuffer(c.width(), c.height());
                    ref = new FrameBuffer(c.width(), c.height());
                    grid = new TileGrid(c.width(), c.height());
                    Arrays.fill(ref.pixels, 0xFF000000); // impossible pixel value: first diff marks everything
                    hasPicture = false;
                }
                openedAtMs = System.currentTimeMillis();
                primeTried = false;
                for (Subscription s : subs) {
                    s.dirty = new boolean[grid.count()];
                    s.refinePending = new boolean[grid.count()];
                    s.changedAt = new long[grid.count()];
                    s.keyframe = true;
                }
                screenInfo = buildScreenInfo(l, mon, c).encode();
                for (Subscription s : subs) s.conn.send(MessageType.SCREEN_INFO, screenInfo);
            }
            Log.info("Streaming " + (mon < 0 ? "all monitors" : "monitor " + mon) + " via " + c.name()
                    + " at " + c.width() + "x" + c.height() + " (desktop region " + physical + ")");
        }

        private ScreenInfo buildScreenInfo(ScreenLayout l, int mon, ScreenCapturer c) {
            Rectangle region = physical;
            double sx = (double) c.width() / Math.max(1, region.width);
            double sy = (double) c.height() / Math.max(1, region.height);
            List<Rectangle> mons = new ArrayList<>();
            for (ScreenLayout.Monitor m : l.monitors) {
                Rectangle r = m.physical;
                mons.add(new Rectangle((int) Math.round((r.x - region.x) * sx), (int) Math.round((r.y - region.y) * sy),
                        (int) Math.round(r.width * sx), (int) Math.round(r.height * sy)));
            }
            return new ScreenInfo(c.width(), c.height(), mons, mon < 0 ? ScreenInfo.ALL_MONITORS : mon);
        }

        private void closeCapturer() {
            ScreenCapturer c = capturer;
            capturer = null;
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void run() {
            long backoff = 200;
            try {
                while (!subs.isEmpty()) {
                    try {
                        if (capturer == null) open();
                        loopOnce();
                        backoff = 200;
                    } catch (ScreenCapturer.CaptureLostException e) {
                        Log.info("capture lost (" + e.getMessage() + "), reopening");
                        closeCapturer();
                        Threads.sleepQuietly(150);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        Log.error("stream loop error", e);
                        closeCapturer();
                        Threads.sleepQuietly(backoff);
                        backoff = Math.min(3000, backoff * 2);
                    }
                }
            } finally {
                closeCapturer();
                synchronized (this) {
                    thread = null;
                    fb = null;
                    ref = null;
                    grid = null;
                    screenInfo = null;
                }
                groups.remove(key, this);
                // A subscriber may have raced in while we were exiting.
                if (!subs.isEmpty()) {
                    groups.putIfAbsent(key, this);
                    synchronized (this) {
                        if (thread == null) thread = Threads.start("screen-stream-restart", Thread.MAX_PRIORITY - 1, this::run);
                    }
                }
            }
        }

        private void loopOnce() throws Exception {
            int fps = 1;
            for (Subscription s : subs) fps = Math.max(fps, s.req.maxFps);
            long frameMs = Math.max(1, 1000 / fps);

            // Wait for change (DXGI blocks until the screen updates; others return immediately).
            // If a viewer has queued changes and room in flight, don't sit in a long wait.
            boolean pending = false;
            for (Subscription s : subs) {
                if (s.inFlight() < MAX_IN_FLIGHT && (s.keyframe || any(s.dirty))) {
                    pending = true;
                    break;
                }
            }
            ScreenCapturer.Grab grab = capturer.grab(fb, pending ? 2 : (int) Math.min(frameMs, 50));
            long now = System.currentTimeMillis();
            boolean[] changed = new boolean[grid.count()];
            int nChanged = 0;
            if (grab.changed) {
                boolean[] candidates = null;
                if (grab.dirty != null) {
                    candidates = new boolean[grid.count()];
                    for (Rectangle r : grab.dirty) grid.markRect(candidates, r);
                }
                synchronized (this) {
                    nChanged = grid.diffAndUpdate(fb.pixels, ref.pixels, candidates, changed);
                }
                hasPicture = true;
            } else if (!hasPicture && !primeTried && now - openedAtMs > PRIME_AFTER_MS) {
                primeFromFallback();
            }

            sendCursor();

            for (Subscription s : subs) {
                if (nChanged > 0) {
                    for (int i = 0; i < changed.length; i++) {
                        if (changed[i]) {
                            s.dirty[i] = true;
                            s.refinePending[i] = true;
                            s.changedAt[i] = now;
                        }
                    }
                }
            }
            sendFrames(now);

            if (!grab.changed || "Robot".equals(capturer.name()) || "GDI".equals(capturer.name())) {
                // Polling capturers: pace to the fps cap; ack/wake shortens the wait.
                long sleep = frameMs - (System.currentTimeMillis() - now);
                if (sleep > 0) waitWake(sleep);
            }
        }

        /**
         * DXGI delivers nothing while the screen is perfectly still, and until its first frame there
         * is no picture to send (the old code sent the "black" placeholder). Take one picture with GDI
         * or Robot so viewers see the screen at once.
         */
        private void primeFromFallback() {
            primeTried = true;
            ScreenLayout l = layout;
            int mon = key == ScreenInfo.ALL_MONITORS ? -1 : key;
            for (String type : new String[] {"GDI", "Robot"}) {
                try (ScreenCapturer c = Capturers.openType(type, l, mon)) {
                    if (c.width() != fb.width || c.height() != fb.height) continue;
                    if (!c.grab(fb, 500).changed) continue;
                    synchronized (this) {
                        grid.diffAndUpdate(fb.pixels, ref.pixels, null, new boolean[grid.count()]);
                    }
                    hasPicture = true;
                    Log.info("First picture taken with " + type + " (the main capturer had not delivered one yet)");
                    return;
                } catch (Exception e) {
                    Log.debug("prime with " + type + " failed: " + e);
                }
            }
        }

        private void sendFrames(long now) throws Exception {
            if (!hasPicture) return; // nothing real to show yet: wait instead of sending black
            // Viewers with identical work this tick share one encode.
            List<Subscription> ready = new ArrayList<>();
            for (Subscription s : subs) {
                if (!s.conn.isOpen()) continue;
                if (s.inFlight() >= MAX_IN_FLIGHT) {
                    if (now - s.inFlightSinceMs > ACK_STALL_MS) {
                        s.lastAcked = s.lastSent; // viewer lost an ack (e.g. reconfigured); resync
                        s.keyframe = true;
                    } else {
                        continue;
                    }
                }
                long minGap = 1000L / Math.max(1, s.req.maxFps);
                if (now - s.lastSendMs < minGap) continue;
                ready.add(s);
            }
            boolean[] all = null;
            while (!ready.isEmpty()) {
                Subscription lead = ready.remove(0);
                boolean refine = false;
                boolean[] work;
                if (lead.keyframe) {
                    if (all == null) {
                        all = new boolean[grid.count()];
                        Arrays.fill(all, true);
                    }
                    work = all;
                } else if (any(lead.dirty)) {
                    work = lead.dirty.clone();
                } else {
                    work = refineSet(lead, now);
                    if (work == null) continue;
                    refine = true;
                }
                List<Subscription> group = new ArrayList<>();
                group.add(lead);
                for (int i = 0; i < ready.size(); i++) {
                    Subscription o = ready.get(i);
                    boolean same = o.req.preset == lead.req.preset && o.keyframe == lead.keyframe
                            && (lead.keyframe || (!refine && Arrays.equals(o.dirty, lead.dirty)));
                    if (same) {
                        group.add(o);
                        ready.remove(i--);
                    }
                }
                Quality q = Quality.forPreset(lead.req.preset);
                List<TileCodec.EncodedRect> rects;
                synchronized (this) {
                    rects = TileEncoder.encode(ref, grid, work, q, refine);
                }
                boolean key = lead.keyframe;
                for (Subscription s : group) {
                    int id = s.nextFrameId++;
                    for (byte[] m : VideoMessage.pack(id, key, rects)) {
                        if (!s.conn.send(MessageType.VIDEO, m)) {
                            s.keyframe = true; // video lane overflowed: resend everything next time
                            break;
                        }
                    }
                    if (s.inFlight() == 0) s.inFlightSinceMs = now;
                    s.lastSent = id;
                    s.lastSendMs = now;
                    if (key) {
                        s.keyframe = false;
                        Arrays.fill(s.dirty, false);
                        if (!refine) {
                            // keyframes are encoded at "moving" quality: refine everything later
                            Arrays.fill(s.refinePending, true);
                            Arrays.fill(s.changedAt, now);
                        }
                    } else {
                        for (int i = 0; i < work.length; i++) {
                            if (work[i]) {
                                s.dirty[i] = false;
                                if (refine) s.refinePending[i] = false;
                            }
                        }
                    }
                }
            }
        }

        private boolean[] refineSet(Subscription s, long now) {
            Quality q = Quality.forPreset(s.req.preset);
            if (q.refineAfterMs <= 0) return null;
            boolean[] out = null;
            for (int i = 0; i < s.refinePending.length; i++) {
                if (s.refinePending[i] && now - s.changedAt[i] >= q.refineAfterMs) {
                    if (out == null) out = new boolean[s.refinePending.length];
                    out[i] = true;
                }
            }
            return out;
        }

        private void sendCursor() {
            CursorState c;
            try {
                c = CursorProbe.read(layout);
            } catch (Throwable t) {
                return;
            }
            if (c == null) return;
            Rectangle r = physical;
            boolean inside = r.contains(c.x, c.y);
            int nx = clampNorm((c.x - r.x + 0.5) / r.width);
            int ny = clampNorm((c.y - r.y + 0.5) / r.height);
            boolean vis = c.visible && inside;
            for (Subscription s : subs) {
                if (nx != s.lastCursorX || ny != s.lastCursorY || vis != s.lastCursorVisible) {
                    s.lastCursorX = nx;
                    s.lastCursorY = ny;
                    s.lastCursorVisible = vis;
                    s.conn.send(MessageType.CURSOR_POS, new WireWriter(5).u16(nx).u16(ny).u8(vis ? 1 : 0).toByteArray());
                }
                if (c.kind != s.lastKind) {
                    s.lastKind = c.kind;
                    s.conn.send(MessageType.CURSOR_SHAPE, new byte[] {(byte) c.kind.ordinal()});
                }
            }
        }
    }

    /** How long the main capturer may stay silent before one picture is taken with another one. */
    private static final long PRIME_AFTER_MS = 1500;

    private static int clampNorm(double f) {
        return (int) Math.max(0, Math.min(65535, Math.round(f * 65535)));
    }

    private static boolean any(boolean[] a) {
        for (boolean b : a) if (b) return true;
        return false;
    }
}
