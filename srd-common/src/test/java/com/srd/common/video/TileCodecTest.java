package com.srd.common.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.protocol.StreamRequest;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TileCodecTest {

    /** A "desktop": flat background, a window with text-like few-color content, and a photo area. */
    private static FrameBuffer desktop(int w, int h, long seed) {
        FrameBuffer fb = new FrameBuffer(w, h);
        Random r = new Random(seed);
        Arrays.fill(fb.pixels, 0x2B579A);
        for (int y = 40; y < 300; y++) {
            for (int x = 50; x < 700; x++) {
                fb.pixels[y * w + x] = ((x / 3 + y / 7) % 5 == 0) ? 0x000000 : 0xFFFFFF; // "text"
            }
        }
        for (int y = 320; y < h - 10; y++) {
            for (int x = 300; x < w - 20; x++) {
                fb.pixels[y * w + x] = (r.nextInt(256) << 16) | ((x * 255 / w) << 8) | ((y * 255 / h)); // photo/noise
            }
        }
        return fb;
    }

    @Test
    void solidAndPaletteAreLossless() throws Exception {
        FrameBuffer fb = desktop(1000, 700, 1);
        int[] out = new int[fb.pixels.length];
        Rectangle solid = new Rectangle(0, 0, 40, 30);
        TileCodec.EncodedRect e = TileCodec.encode(fb.pixels, fb.width, solid, 32, 0.5f);
        assertEquals(TileCodec.SOLID, e.encoding);
        TileCodec.decode(e, out, fb.width);
        Rectangle text = new Rectangle(64, 64, 256, 128);
        TileCodec.EncodedRect t = TileCodec.encode(fb.pixels, fb.width, text, 32, 0.5f);
        assertEquals(TileCodec.PALETTE, t.encoding);
        assertTrue(t.data.length < text.width * text.height / 4, "palette should compress well: " + t.data.length);
        TileCodec.decode(t, out, fb.width);
        for (Rectangle r : new Rectangle[] {solid, text}) {
            for (int y = r.y; y < r.y + r.height; y++) {
                for (int x = r.x; x < r.x + r.width; x++) {
                    assertEquals(fb.pixels[y * fb.width + x], out[y * fb.width + x], "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void jpegIsCloseToSourceAndKeepsChannelOrder() throws Exception {
        int w = 256, h = 192;
        FrameBuffer fb = new FrameBuffer(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x, g = y * 255 / h, b = 255 - x; // smooth, distinct per channel (>256 colors)
                fb.pixels[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        Rectangle all = new Rectangle(0, 0, w, h);
        TileCodec.EncodedRect e = TileCodec.encode(fb.pixels, w, all, 32, 0.9f);
        assertEquals(TileCodec.JPEG, e.encoding);
        int[] out = new int[w * h];
        TileCodec.decode(e, out, w);
        double[] err = new double[3];
        for (int i = 0; i < out.length; i++) {
            for (int c = 0; c < 3; c++) {
                int sh = 16 - 8 * c;
                err[c] += Math.abs(((fb.pixels[i] >> sh) & 0xFF) - ((out[i] >> sh) & 0xFF));
            }
        }
        for (int c = 0; c < 3; c++) assertTrue(err[c] / out.length < 4, "channel " + c + " mean error " + err[c] / out.length);
    }

    @Test
    void fullPipelineReconstructsLosslessContent() throws Exception {
        int w = 1366, h = 768; // not a multiple of the tile size
        FrameBuffer src = new FrameBuffer(w, h);
        Arrays.fill(src.pixels, 0x101010);
        for (int y = 100; y < 600; y++) for (int x = 77; x < 1300; x++) src.pixels[y * w + x] = ((x ^ y) & 8) != 0 ? 0xEEEEEE : 0x1E88E5;
        TileGrid grid = new TileGrid(w, h);
        boolean[] dirty = new boolean[grid.count()];
        Arrays.fill(dirty, true);
        List<TileCodec.EncodedRect> rects = TileEncoder.encode(src, grid, dirty, Quality.forPreset(StreamRequest.PRESET_BALANCED), false);
        List<byte[]> msgs = VideoMessage.pack(1, true, rects);
        assertTrue(msgs.size() >= 1);
        FrameBuffer dst = new FrameBuffer(w, h);
        int ends = 0;
        for (byte[] m : msgs) {
            assertTrue(m.length <= VideoMessage.TARGET_BYTES + 64 * 64 * 4 * 8 * 4, "message size " + m.length);
            VideoMessage vm = VideoMessage.decode(m, w, h);
            if (vm.isEnd()) ends++;
            for (TileCodec.EncodedRect r : vm.rects) TileCodec.decode(r, dst.pixels, w);
        }
        assertEquals(1, ends);
        assertArrayEquals(src.pixels, dst.pixels);
    }

    @Test
    void diffFindsOnlyChangedTilesAndMergeCoversThem() {
        int w = 640, h = 480;
        FrameBuffer cur = new FrameBuffer(w, h);
        FrameBuffer ref = new FrameBuffer(w, h);
        TileGrid g = new TileGrid(w, h);
        boolean[] changed = new boolean[g.count()];
        assertEquals(0, g.diffAndUpdate(cur.pixels, ref.pixels, null, changed));
        cur.pixels[100 * w + 100] = 0xFF;       // tile (1,1)
        cur.pixels[479 * w + 639] = 0xFF00;     // last tile
        cur.pixels[10 * w + 130] = 0xF;         // tile (2,0)
        assertEquals(3, g.diffAndUpdate(cur.pixels, ref.pixels, null, changed));
        assertArrayEquals(cur.pixels, ref.pixels);
        List<Rectangle> rects = g.merge(changed.clone(), null);
        List<Rectangle> expectCovered = new ArrayList<>();
        expectCovered.add(new Rectangle(100, 100, 1, 1));
        expectCovered.add(new Rectangle(639, 479, 1, 1));
        expectCovered.add(new Rectangle(130, 10, 1, 1));
        for (Rectangle px : expectCovered) {
            assertTrue(rects.stream().anyMatch(r -> r.contains(px)), "pixel " + px + " not covered by " + rects);
        }
        // candidates restrict the scan
        cur.pixels[300 * w + 300] = 0x123456;
        boolean[] cand = new boolean[g.count()];
        boolean[] ch2 = new boolean[g.count()];
        assertEquals(0, g.diffAndUpdate(cur.pixels, ref.pixels, cand, ch2));
        g.markRect(cand, new Rectangle(290, 290, 20, 20));
        assertEquals(1, g.diffAndUpdate(cur.pixels, ref.pixels, cand, ch2));
    }
}
