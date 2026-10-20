package com.srd.common.video;

import com.srd.common.protocol.ScreenInfo;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Viewer-side decoder. Network threads hand VIDEO payloads to {@link #submit}; a dedicated thread
 * decodes them into the {@link FrameBuffer} (rects of one message in parallel across cores) and
 * reports damage plus completed frames. Decoding never runs on the connection's reader thread,
 * so audio and pen messages behind a big frame aren't delayed.
 */
public final class VideoDecoder {
    /** Callbacks run on the decoder thread. */
    public interface Sink {
        /** A new (or resized) framebuffer is in use. */
        void onFrameBuffer(FrameBuffer fb);

        /** Pixels inside {@code damage} changed. */
        void onDamage(Rectangle damage);

        /** Every message of {@code frameId} has been applied. */
        void onFrameComplete(int frameId, long decodeNanos);
    }

    private static final ExecutorService POOL =
            Threads.pool("tile-decode", Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)));
    private static final Object EOS = new Object();

    private final Sink sink;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile FrameBuffer fb;
    private volatile boolean running = true;
    private long frameDecodeNanos;

    public VideoDecoder(Sink sink) {
        this.sink = sink;
        Threads.start("video-decoder", Thread.NORM_PRIORITY + 1, this::loop);
    }

    public FrameBuffer frameBuffer() {
        return fb;
    }

    /** Sizes (or resizes) the framebuffer for a new stream geometry. Thread-safe (queued). */
    public void onScreenInfo(ScreenInfo info) {
        queue.add(info);
    }

    public void submit(byte[] videoPayload) {
        queue.add(videoPayload);
    }

    public void close() {
        running = false;
        queue.add(EOS);
    }

    private void loop() {
        try {
            while (running) {
                Object o = queue.take();
                if (o == EOS) return;
                if (o instanceof ScreenInfo) {
                    ScreenInfo si = (ScreenInfo) o;
                    FrameBuffer cur = fb;
                    if (cur == null || cur.width != si.width || cur.height != si.height) {
                        fb = new FrameBuffer(si.width, si.height);
                        sink.onFrameBuffer(fb);
                    }
                    continue;
                }
                FrameBuffer target = fb;
                if (target == null) continue; // video before geometry: drop
                try {
                    apply(target, (byte[]) o);
                } catch (Exception e) {
                    Log.warn("video decode failed: " + e);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void apply(FrameBuffer target, byte[] payload) throws Exception {
        long t0 = System.nanoTime();
        VideoMessage m = VideoMessage.decode(payload, target.width, target.height);
        Rectangle damage = null;
        if (m.rects.size() <= 2) {
            for (TileCodec.EncodedRect r : m.rects) TileCodec.decode(r, target.pixels, target.width);
        } else {
            List<Future<?>> fs = new ArrayList<>(m.rects.size());
            for (TileCodec.EncodedRect r : m.rects) {
                fs.add(POOL.submit(() -> {
                    TileCodec.decode(r, target.pixels, target.width);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        for (TileCodec.EncodedRect r : m.rects) {
            Rectangle rr = new Rectangle(r.x, r.y, r.w, r.h);
            damage = damage == null ? rr : damage.union(rr);
        }
        frameDecodeNanos += System.nanoTime() - t0;
        if (damage != null) sink.onDamage(damage);
        if (m.isEnd()) {
            sink.onFrameComplete(m.frameId, frameDecodeNanos);
            frameDecodeNanos = 0;
        }
    }
}
