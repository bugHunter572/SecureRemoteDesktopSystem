package com.srd.common.protocol;

import java.nio.charset.StandardCharsets;

/** Big-endian reader over a payload; throws {@link ProtocolException} on truncation. */
public final class WireReader {
    private final byte[] buf;
    private int pos;
    private final int end;

    public WireReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public WireReader(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    private void need(int n) {
        if (pos + n > end) throw new ProtocolException("truncated message (need " + n + ", have " + (end - pos) + ")");
    }

    public int u8() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public int u16() {
        need(2);
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public short i16() {
        return (short) u16();
    }

    public int i32() {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long i64() {
        long hi = i32() & 0xFFFFFFFFL;
        long lo = i32() & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public byte[] bytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** u32 length + bytes. */
    public byte[] blob() {
        int n = i32();
        if (n < 0) throw new ProtocolException("negative blob length");
        return bytes(n);
    }

    /** Offset of the next byte in the backing array (for zero-copy slices). */
    public int position() {
        return pos;
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    public byte[] array() {
        return buf;
    }

    public String str() {
        int n = u16();
        need(n);
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public int remaining() {
        return end - pos;
    }
}
