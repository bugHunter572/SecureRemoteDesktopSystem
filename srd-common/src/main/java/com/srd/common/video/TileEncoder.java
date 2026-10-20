package com.srd.common.video;

import com.srd.common.util.Threads;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Turns "these tiles changed" into encoded rects, using all cores. Pipeline:
 * classify each dirty tile (solid / palette / photo), merge neighbours of the same class, then
 * encode each merged rect in parallel.
 */
public final class TileEncoder {
    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    private static final ExecutorService POOL = Threads.pool("tile-encode", THREADS);

    private TileEncoder() {
    }

    /**
     * @param dirty tiles to encode; NOT modified
     * @param refine use the settled-region quality (higher JPEG quality, full palette range)
     */
    public static List<TileCodec.EncodedRect> encode(FrameBuffer fb, TileGrid grid, boolean[] dirty, Quality q, boolean refine)
            throws IOException {
        int maxColors = refine ? 256 : q.maxPaletteColors;
        float jpeg = refine ? q.refineJpeg : q.jpeg;
        int n = grid.count();
        long[] cls = new long[n];
        boolean[] work = dirty.clone();

        // 1. classify (parallel by tile rows)
        List<Future<?>> fs = new ArrayList<>();
        int rowsPerTask = Math.max(1, grid.rows / (THREADS * 2));
        for (int r0 = 0; r0 < grid.rows; r0 += rowsPerTask) {
            final int from = r0, to = Math.min(grid.rows, r0 + rowsPerTask);
            fs.add(POOL.submit(() -> {
                for (int ty = from; ty < to; ty++) {
                    for (int tx = 0; tx < grid.cols; tx++) {
                        int idx = ty * grid.cols + tx;
                        if (work[idx]) cls[idx] = TileCodec.classify(fb.pixels, fb.width, grid.tileRect(idx), maxColors);
                    }
                }
            }));
        }
        await(fs);

        // 2. merge same-class neighbours
        List<Rectangle> rects = grid.merge(work, cls);

        // 3. encode in parallel
        List<Future<TileCodec.EncodedRect>> enc = new ArrayList<>(rects.size());
        for (Rectangle r : rects) {
            enc.add(POOL.submit(() -> TileCodec.encode(fb.pixels, fb.width, r, maxColors, jpeg)));
        }
        List<TileCodec.EncodedRect> out = new ArrayList<>(rects.size());
        for (Future<TileCodec.EncodedRect> f : enc) {
            try {
                out.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (ExecutionException e) {
                throw new IOException("tile encode failed", e.getCause());
            }
        }
        return out;
    }

    private static void await(List<Future<?>> fs) throws IOException {
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (ExecutionException e) {
                throw new IOException("tile classify failed", e.getCause());
            }
        }
    }
}
