package com.srd.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable big-endian byte builder for message payloads. Not thread-safe. */
public final class WireWriter {
    private byte[] buf;
    private int pos;

    public WireWriter() {
        this(256);
    }

    public WireWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
        }
    }

    public WireWriter u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public WireWriter u16(int v) {
        ensure(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public WireWriter i32(int v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public WireWriter i64(long v) {
        i32((int) (v >>> 32));
        return i32((int) v);
    }

    public WireWriter f32(float v) {
        return i32(Float.floatToIntBits(v));
    }

    public WireWriter bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public WireWriter bytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** u32 length + bytes. */
    public WireWriter blob(byte[] b) {
        i32(b.length);
        return bytes(b);
    }

    /** u16 length + UTF-8. */
    public WireWriter str(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > 0xFFFF) b = Arrays.copyOf(b, 0xFFFF);
        u16(b.length);
        return bytes(b);
    }

    public int size() {
        return pos;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }
}
