package com.srd.common.video;

import com.srd.common.protocol.ProtocolException;
import com.srd.common.protocol.WireReader;
import com.srd.common.protocol.WireWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * VIDEO payload: {@code u32 frameId, u8 flags, u16 count, count × [u16 x,y,w,h, u8 enc, u32 len, data]}.
 * One frame may span several messages; the last carries {@link #FLAG_END}. Messages are capped at
 * {@link #TARGET_BYTES} (except a single oversized rect) so the mux can interleave input/audio.
 */
public final class VideoMessage {
    public static final int FLAG_END = 1;
    public static final int FLAG_KEYFRAME = 2;
    public static final int TARGET_BYTES = 60 * 1024;

    public final int frameId;
    public final int flags;
    public final List<TileCodec.EncodedRect> rects;

    public VideoMessage(int frameId, int flags, List<TileCodec.EncodedRect> rects) {
        this.frameId = frameId;
        this.flags = flags;
        this.rects = rects;
    }

    public boolean isEnd() {
        return (flags & FLAG_END) != 0;
    }

    /** Packs rects into as few ≤TARGET_BYTES messages as possible; the last one gets FLAG_END. */
    public static List<byte[]> pack(int frameId, boolean keyframe, List<TileCodec.EncodedRect> rects) {
        List<byte[]> out = new ArrayList<>();
        List<TileCodec.EncodedRect> batch = new ArrayList<>();
        int batchBytes = 0;
        for (TileCodec.EncodedRect r : rects) {
            int sz = 13 + r.data.length;
            if (!batch.isEmpty() && batchBytes + sz > TARGET_BYTES) {
                out.add(encode(frameId, keyframe ? FLAG_KEYFRAME : 0, batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(r);
            batchBytes += sz;
        }
        out.add(encode(frameId, FLAG_END | (keyframe ? FLAG_KEYFRAME : 0), batch));
        return out;
    }

    public static byte[] encode(int frameId, int flags, List<TileCodec.EncodedRect> rects) {
        int size = 7;
        for (TileCodec.EncodedRect r : rects) size += 13 + r.data.length;
        WireWriter w = new WireWriter(size);
        w.i32(frameId).u8(flags).u16(rects.size());
        for (TileCodec.EncodedRect r : rects) {
            w.u16(r.x).u16(r.y).u16(r.w).u16(r.h).u8(r.encoding).blob(r.data);
        }
        return w.toByteArray();
    }

    /**
     * @param fbWidth  framebuffer width, for bounds validation
     * @param fbHeight framebuffer height
     */
    public static VideoMessage decode(byte[] payload, int fbWidth, int fbHeight) {
        WireReader r = new WireReader(payload);
        int frameId = r.i32();
        int flags = r.u8();
        int n = r.u16();
        List<TileCodec.EncodedRect> rects = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = r.u16(), y = r.u16(), w = r.u16(), h = r.u16(), enc = r.u8();
            byte[] data = r.blob();
            if (w <= 0 || h <= 0 || x + w > fbWidth || y + h > fbHeight) {
                throw new ProtocolException("video rect out of bounds: " + x + "," + y + " " + w + "x" + h
                        + " in " + fbWidth + "x" + fbHeight);
            }
            rects.add(new TileCodec.EncodedRect(x, y, w, h, enc, data));
        }
        return new VideoMessage(frameId, flags, rects);
    }
}
