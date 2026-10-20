package com.srd.common.annotation;

import com.srd.common.protocol.ProtocolException;
import com.srd.common.protocol.WireReader;
import com.srd.common.protocol.WireWriter;

/**
 * PEN payload codec. Ops:
 * <pre>
 *  BEGIN  i32 author, i32 seq, u8 tool, i32 argb, f32 width, u8 fadeSec, str authorName, points
 *  POINTS i32 author, i32 seq, points
 *  END    i32 author, i32 seq
 *  REMOVE i32 author, i32 seq
 *  CLEAR  i32 author (0 = everyone)
 *  points = u16 n, n × (u16 x, u16 y)  normalized 0..65535
 *  BEGIN of a TEXT / IMAGE stroke continues with: str text, blob image (older peers ignore these bytes)
 * </pre>
 */
public final class PenMessage {
    public static final int BEGIN = 1;
    public static final int POINTS = 2;
    public static final int END = 3;
    public static final int REMOVE = 4;
    public static final int CLEAR = 5;

    public final int op;
    public final int author;
    public final int seq;
    public final PenTool tool;
    public final int argb;
    public final float width;
    public final int fadeSeconds;
    public final String authorName;
    /** Interleaved normalized x,y (0..1). */
    public final float[] points;
    public final String text;
    public final byte[] image;

    private PenMessage(int op, int author, int seq, PenTool tool, int argb, float width, int fade, String name, float[] pts) {
        this(op, author, seq, tool, argb, width, fade, name, pts, "", null);
    }

    private PenMessage(int op, int author, int seq, PenTool tool, int argb, float width, int fade, String name, float[] pts,
                       String text, byte[] image) {
        this.text = text == null ? "" : text;
        this.image = image;
        this.op = op;
        this.author = author;
        this.seq = seq;
        this.tool = tool;
        this.argb = argb;
        this.width = width;
        this.fadeSeconds = fade;
        this.authorName = name;
        this.points = pts == null ? new float[0] : pts;
    }

    public long strokeId() {
        return Stroke.id(author, seq);
    }

    public static byte[] begin(Stroke s, int seq, float[] pts) {
        byte[] extra = s.imageData();
        WireWriter w = new WireWriter(64 + pts.length * 2 + (extra == null ? 0 : extra.length) + s.text.length() * 3);
        w.u8(BEGIN).i32(s.authorId).i32(seq).u8(s.tool.ordinal()).i32(s.argb).f32(s.width)
                .u8(Math.max(0, Math.min(255, s.fadeSeconds))).str(s.authorName);
        writePoints(w, pts);
        if (s.tool == PenTool.TEXT || s.tool == PenTool.IMAGE) {
            byte[] img = s.imageData();
            w.str(s.text).blob(img == null ? new byte[0] : img);
        }
        return w.toByteArray();
    }

    public static byte[] points(int author, int seq, float[] pts) {
        WireWriter w = new WireWriter(16 + pts.length * 2);
        w.u8(POINTS).i32(author).i32(seq);
        writePoints(w, pts);
        return w.toByteArray();
    }

    public static byte[] end(int author, int seq) {
        return new WireWriter(12).u8(END).i32(author).i32(seq).toByteArray();
    }

    public static byte[] remove(int author, int seq) {
        return new WireWriter(12).u8(REMOVE).i32(author).i32(seq).toByteArray();
    }

    public static byte[] clear(int author) {
        return new WireWriter(8).u8(CLEAR).i32(author).toByteArray();
    }

    private static void writePoints(WireWriter w, float[] pts) {
        int n = Math.min(pts.length / 2, 0xFFFF);
        w.u16(n);
        for (int i = 0; i < n; i++) {
            w.u16(Math.round(Math.max(0, Math.min(1, pts[2 * i])) * 65535));
            w.u16(Math.round(Math.max(0, Math.min(1, pts[2 * i + 1])) * 65535));
        }
    }

    private static float[] readPoints(WireReader r) {
        int n = r.u16();
        float[] p = new float[n * 2];
        for (int i = 0; i < n * 2; i++) p[i] = r.u16() / 65535f;
        return p;
    }

    public static PenMessage decode(byte[] payload) {
        WireReader r = new WireReader(payload);
        int op = r.u8();
        switch (op) {
            case BEGIN: {
                int a = r.i32(), s = r.i32();
                PenTool t = PenTool.of(r.u8());
                int argb = r.i32();
                float wd = r.f32();
                int fade = r.u8();
                String name = r.str();
                float[] pts = readPoints(r);
                String text = "";
                byte[] img = null;
                if ((t == PenTool.TEXT || t == PenTool.IMAGE) && r.remaining() > 0) {
                    text = r.str();
                    img = r.blob();
                }
                return new PenMessage(op, a, s, t, argb, wd, fade, name, pts, text, img);
            }
            case POINTS: {
                int a = r.i32(), s = r.i32();
                return new PenMessage(op, a, s, null, 0, 0, 0, null, readPoints(r));
            }
            case END:
            case REMOVE: {
                int a = r.i32(), s = r.i32();
                return new PenMessage(op, a, s, null, 0, 0, 0, null, null);
            }
            case CLEAR:
                return new PenMessage(op, r.i32(), 0, null, 0, 0, 0, null, null);
            default:
                throw new ProtocolException("unknown pen op " + op);
        }
    }
}
