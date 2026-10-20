package com.srd.common.video;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixed 64×64 tile grid over a frame: change detection against a reference frame and merging
 * dirty tiles into a few larger rectangles.
 */
public final class TileGrid {
    public static final int TILE = 64;
    /** Merged rects are at most 8×4 tiles (512×256) so one encoded rect stays well under 64 KB. */
    public static final int MAX_RUN_X = 8;
    public static final int MAX_RUN_Y = 4;

    public final int width;
    public final int height;
    public final int cols;
    public final int rows;

    public TileGrid(int width, int height) {
        this.width = width;
        this.height = height;
        this.cols = (width + TILE - 1) / TILE;
        this.rows = (height + TILE - 1) / TILE;
    }

    public int count() {
        return cols * rows;
    }

    public Rectangle tileRect(int index) {
        int tx = index % cols;
        int ty = index / cols;
        int x = tx * TILE;
        int y = ty * TILE;
        return new Rectangle(x, y, Math.min(TILE, width - x), Math.min(TILE, height - y));
    }

    /** Marks every tile intersecting {@code r} in {@code dirty}. */
    public void markRect(boolean[] dirty, Rectangle r) {
        int x0 = Math.max(0, r.x) / TILE;
        int y0 = Math.max(0, r.y) / TILE;
        int x1 = Math.min(cols - 1, (Math.min(width, r.x + r.width) - 1) / TILE);
        int y1 = Math.min(rows - 1, (Math.min(height, r.y + r.height) - 1) / TILE);
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) dirty[ty * cols + tx] = true;
        }
    }

    /**
     * Compares {@code current} with {@code reference} tile by tile (only tiles flagged in
     * {@code candidates}, or all tiles if it is null), sets {@code changedOut} for tiles that
     * differ, and copies those tiles into {@code reference}. Row compares use
     * {@link Arrays#equals(int[], int, int, int[], int, int)}, which the JIT vectorizes.
     *
     * @return number of changed tiles
     */
    public int diffAndUpdate(int[] current, int[] reference, boolean[] candidates, boolean[] changedOut) {
        int changed = 0;
        for (int ty = 0; ty < rows; ty++) {
            int y0 = ty * TILE;
            int h = Math.min(TILE, height - y0);
            for (int tx = 0; tx < cols; tx++) {
                int idx = ty * cols + tx;
                if (candidates != null && !candidates[idx]) continue;
                int x0 = tx * TILE;
                int w = Math.min(TILE, width - x0);
                boolean diff = false;
                for (int y = y0; y < y0 + h; y++) {
                    int off = y * width + x0;
                    if (!Arrays.equals(current, off, off + w, reference, off, off + w)) {
                        diff = true;
                        break;
                    }
                }
                if (diff) {
                    for (int y = y0; y < y0 + h; y++) {
                        int off = y * width + x0;
                        System.arraycopy(current, off, reference, off, w);
                    }
                    changedOut[idx] = true;
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Groups dirty tiles into rectangles: horizontal runs of tiles with the same class key
     * (≤ {@link #MAX_RUN_X}), extended downward while the row below has the identical run
     * (≤ {@link #MAX_RUN_Y}). Consumed tiles are cleared in {@code dirty}.
     *
     * @param classKey per-tile key; only tiles with equal keys merge (e.g. encoding class, or
     *                 the color of solid tiles). May be null to merge purely on dirtiness.
     */
    public List<Rectangle> merge(boolean[] dirty, long[] classKey) {
        boolean[] d = dirty;
        List<Rectangle> out = new ArrayList<>();
        for (int ty = 0; ty < rows; ty++) {
            int tx = 0;
            while (tx < cols) {
                int idx = ty * cols + tx;
                if (!d[idx]) {
                    tx++;
                    continue;
                }
                long key = classKey == null ? 0 : classKey[idx];
                int run = 1;
                while (tx + run < cols && run < MAX_RUN_X && d[idx + run]
                        && (classKey == null || classKey[idx + run] == key)) {
                    run++;
                }
                int height = 1;
                while (ty + height < rows && height < MAX_RUN_Y) {
                    int below = (ty + height) * cols + tx;
                    boolean same = true;
                    for (int i = 0; i < run; i++) {
                        if (!d[below + i] || (classKey != null && classKey[below + i] != key)) {
                            same = false;
                            break;
                        }
                    }
                    if (!same) break;
                    height++;
                }
                for (int yy = 0; yy < height; yy++) {
                    for (int i = 0; i < run; i++) d[(ty + yy) * cols + tx + i] = false;
                }
                int x = tx * TILE;
                int y = ty * TILE;
                out.add(new Rectangle(x, y, Math.min(run * TILE, width - x), Math.min(height * TILE, this.height - y)));
                tx += run;
            }
        }
        return out;
    }
}
