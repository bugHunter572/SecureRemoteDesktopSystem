package com.srd.common.video;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Encodes/decodes one rectangle of a 0x00RRGGBB frame. The encoding is picked per region:
 * <ul>
 *   <li>{@link #SOLID}: a single color (3 bytes). Typical of backgrounds and window fills.</li>
 *   <li>{@link #PALETTE}: ≤256 distinct colors. Indices are bit-packed and then deflated, so
 *       it's lossless; text and UI stay crisp and compress very well.</li>
 *   <li>{@link #JPEG}: everything else (photos, video, gradients).</li>
 * </ul>
 * All scratch state (ImageWriter/Reader, deflater, BGR staging images) is per thread and reused,
 * unlike the old code that built a new ImageWriter for every frame.
 */
public final class TileCodec {
    public static final int SOLID = 0;
    public static final int PALETTE = 1;
    public static final int JPEG = 2;

    static {
        // Without this ImageIO backs every in-memory stream with a temp FILE.
        ImageIO.setUseCache(false);
    }

    private TileCodec() {
    }

    /** One encoded region. */
    public static final class EncodedRect {
        public final int x, y, w, h, encoding;
        public final byte[] data;

        public EncodedRect(int x, int y, int w, int h, int encoding, byte[] data) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.encoding = encoding;
            this.data = data;
        }
    }

    // ------------------------------------------------------------------ classification

    public static final long CLASS_PALETTE = 2L << 32;
    public static final long CLASS_JPEG = 3L << 32;

    /**
     * Class key used to merge neighbouring tiles: solid tiles of the same color, palette tiles,
     * or JPEG tiles.
     */
    public static long classify(int[] px, int stride, Rectangle r, int maxPaletteColors) {
        Scratch s = SCRATCH.get();
        int n = s.countColors(px, stride, r, Math.max(1, maxPaletteColors));
        if (n == 1) return (1L << 32) | (px[r.y * stride + r.x] & 0xFFFFFFL);
        return n <= maxPaletteColors ? CLASS_PALETTE : CLASS_JPEG;
    }

    // ------------------------------------------------------------------ encode

    public static EncodedRect encode(int[] px, int stride, Rectangle r, int maxPaletteColors, float jpegQuality) throws IOException {
        Scratch s = SCRATCH.get();
        int n = s.countColors(px, stride, r, 256);
        if (n == 1) {
            int c = px[r.y * stride + r.x];
            return new EncodedRect(r.x, r.y, r.width, r.height, SOLID,
                    new byte[] {(byte) (c >> 16), (byte) (c >> 8), (byte) c});
        }
        if (n <= Math.max(maxPaletteColors, 2) && n <= 256) {
            return new EncodedRect(r.x, r.y, r.width, r.height, PALETTE, s.encodePalette(px, stride, r, n));
        }
        return new EncodedRect(r.x, r.y, r.width, r.height, JPEG, s.encodeJpeg(px, stride, r, jpegQuality));
    }

    // ------------------------------------------------------------------ decode

    /** Decodes {@code e} into {@code dst} (a {@code stride}-wide 0x00RRGGBB buffer). */
    public static void decode(EncodedRect e, int[] dst, int stride) throws IOException {
        switch (e.encoding) {
            case SOLID: {
                int c = ((e.data[0] & 0xFF) << 16) | ((e.data[1] & 0xFF) << 8) | (e.data[2] & 0xFF);
                for (int y = e.y; y < e.y + e.h; y++) {
                    int off = y * stride + e.x;
                    Arrays.fill(dst, off, off + e.w, c);
                }
                return;
            }
            case PALETTE:
                SCRATCH.get().decodePalette(e, dst, stride);
                return;
            case JPEG:
                SCRATCH.get().decodeJpeg(e, dst, stride);
                return;
            default:
                throw new IOException("unknown rect encoding " + e.encoding);
        }
    }

    // ------------------------------------------------------------------ per-thread scratch

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        // Open-addressing color set (capacity 1024 ≫ 257 entries) storing color → index.
        private final int[] keys = new int[1024];
        private final int[] vals = new int[1024];
        private final boolean[] used = new boolean[1024];
        private final int[] usedSlots = new int[1024];
        private int usedCount;
        private final int[] palette = new int[256];

        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        private final Inflater inflater = new Inflater();
        private byte[] packBuf = new byte[64 * 1024];
        private byte[] zbuf = new byte[64 * 1024];

        private ImageWriter writer;
        private ImageWriteParam writeParam;
        private ImageReader reader;
        private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(64 * 1024);
        private final Map<Integer, BufferedImage> bgrCache = new LinkedHashMap<Integer, BufferedImage>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest) {
                return size() > 24;
            }
        };

        private void clearSet() {
            for (int i = 0; i < usedCount; i++) used[usedSlots[i]] = false;
            usedCount = 0;
        }

        /** Returns index of color, inserting if absent; -1 if the set is full ({@code limit} reached). */
        private int indexOf(int color, int limit, int[] sizeHolder) {
            int h = (color * 0x9E3779B1) >>> 22; // 10 bits
            while (used[h]) {
                if (keys[h] == color) return vals[h];
                h = (h + 1) & 1023;
            }
            if (sizeHolder[0] >= limit) return -1;
            used[h] = true;
            keys[h] = color;
            vals[h] = sizeHolder[0];
            usedSlots[usedCount++] = h;
            palette[sizeHolder[0]] = color;
            return sizeHolder[0]++;
        }

        /** Distinct colors in r, stopping at limit+1 (returns limit+1 meaning "more than limit"). */
        int countColors(int[] px, int stride, Rectangle r, int limit) {
            clearSet();
            int[] size = {0};
            int last = px[r.y * stride + r.x] ^ 1;
            for (int y = r.y; y < r.y + r.height; y++) {
                int off = y * stride + r.x;
                for (int x = 0; x < r.width; x++) {
                    int c = px[off + x] & 0xFFFFFF;
                    if (c == last) continue;
                    last = c;
                    if (indexOf(c, limit, size) < 0) return limit + 1;
                }
            }
            return size[0];
        }

        byte[] encodePalette(int[] px, int stride, Rectangle r, int n) {
            // countColors() just filled the set/palette for this exact rect.
            int bits = n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : 8;
            int rowBytes = (r.width * bits + 7) / 8;
            int packedLen = rowBytes * r.height;
            if (packBuf.length < packedLen) packBuf = new byte[packedLen];
            Arrays.fill(packBuf, 0, packedLen, (byte) 0);
            int[] size = {n};
            int ppb = 8 / bits;
            for (int y = 0; y < r.height; y++) {
                int src = (r.y + y) * stride + r.x;
                int rowOff = y * rowBytes;
                for (int x = 0; x < r.width; x++) {
                    int idx = indexOf(px[src + x] & 0xFFFFFF, 256, size);
                    int bytePos = rowOff + x / ppb;
                    int shift = (ppb - 1 - (x % ppb)) * bits;
                    packBuf[bytePos] |= (byte) (idx << shift);
                }
            }
            deflater.reset();
            deflater.setInput(packBuf, 0, packedLen);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + n * 3 + packedLen / 4);
            bos.write(n - 1);
            for (int i = 0; i < n; i++) {
                int c = palette[i];
                bos.write(c >> 16);
                bos.write(c >> 8);
                bos.write(c);
            }
            bos.write(bits);
            while (!deflater.finished()) {
                int k = deflater.deflate(zbuf);
                bos.write(zbuf, 0, k);
            }
            return bos.toByteArray();
        }

        void decodePalette(EncodedRect e, int[] dst, int stride) throws IOException {
            byte[] d = e.data;
            int n = (d[0] & 0xFF) + 1;
            int p = 1;
            for (int i = 0; i < n; i++, p += 3) {
                palette[i] = ((d[p] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF);
            }
            int bits = d[p++] & 0xFF;
            if (bits != 1 && bits != 2 && bits != 4 && bits != 8) throw new IOException("bad palette bits " + bits);
            int rowBytes = (e.w * bits + 7) / 8;
            int packedLen = rowBytes * e.h;
            if (packBuf.length < packedLen) packBuf = new byte[packedLen];
            inflater.reset();
            inflater.setInput(d, p, d.length - p);
            try {
                int got = 0;
                while (got < packedLen) {
                    int k = inflater.inflate(packBuf, got, packedLen - got);
                    if (k == 0 && (inflater.finished() || inflater.needsInput())) break;
                    got += k;
                }
                if (got != packedLen) throw new IOException("palette rect truncated");
            } catch (DataFormatException ex) {
                throw new IOException("corrupt palette rect", ex);
            }
            int ppb = 8 / bits;
            int mask = (1 << bits) - 1;
            for (int y = 0; y < e.h; y++) {
                int out = (e.y + y) * stride + e.x;
                int rowOff = y * rowBytes;
                for (int x = 0; x < e.w; x++) {
                    int shift = (ppb - 1 - (x % ppb)) * bits;
                    int idx = (packBuf[rowOff + x / ppb] >> shift) & mask;
                    dst[out + x] = palette[idx < n ? idx : 0];
                }
            }
        }

        private BufferedImage bgr(int w, int h) {
            Integer key = (w << 16) | h;
            BufferedImage img = bgrCache.get(key);
            if (img == null) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                bgrCache.put(key, img);
            }
            return img;
        }

        byte[] encodeJpeg(int[] px, int stride, Rectangle r, float quality) throws IOException {
            BufferedImage img = bgr(r.width, r.height);
            byte[] b = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            int o = 0;
            for (int y = 0; y < r.height; y++) {
                int src = (r.y + y) * stride + r.x;
                for (int x = 0; x < r.width; x++) {
                    int c = px[src + x];
                    b[o++] = (byte) c;
                    b[o++] = (byte) (c >> 8);
                    b[o++] = (byte) (c >> 16);
                }
            }
            if (writer == null) {
                Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
                if (!it.hasNext()) throw new IOException("no JPEG writer");
                writer = it.next();
                writeParam = writer.getDefaultWriteParam();
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            }
            writeParam.setCompressionQuality(Math.max(0.05f, Math.min(1f, quality)));
            jpegOut.reset();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(jpegOut)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), writeParam);
            } finally {
                writer.setOutput(null);
            }
            return jpegOut.toByteArray();
        }

        void decodeJpeg(EncodedRect e, int[] dst, int stride) throws IOException {
            if (reader == null) {
                Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("jpeg");
                if (!it.hasNext()) throw new IOException("no JPEG reader");
                reader = it.next();
            }
            BufferedImage img;
            try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(e.data))) {
                reader.setInput(iis, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage target = bgr(e.w, e.h);
                param.setDestination(target);
                try {
                    img = reader.read(0, param);
                } catch (IllegalArgumentException | javax.imageio.IIOException mismatch) {
                    // Unusual JPEG color type: decode into whatever the reader picks.
                    iis.seek(0);
                    reader.setInput(iis, true, true);
                    img = reader.read(0);
                }
            } finally {
                reader.setInput(null);
            }
            int w = Math.min(e.w, img.getWidth());
            int h = Math.min(e.h, img.getHeight());
            if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
                byte[] b = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                int iw = img.getWidth();
                for (int y = 0; y < h; y++) {
                    int o = y * iw * 3;
                    int out = (e.y + y) * stride + e.x;
                    for (int x = 0; x < w; x++, o += 3) {
                        dst[out + x] = ((b[o + 2] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o] & 0xFF);
                    }
                }
            } else {
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, dst, (e.y + y) * stride + e.x, w);
                }
                for (int y = 0; y < h; y++) {
                    int out = (e.y + y) * stride + e.x;
                    for (int x = 0; x < w; x++) dst[out + x] &= 0xFFFFFF;
                }
            }
        }
    }
}
