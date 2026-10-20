package com.srd.common.protocol;

/** Viewer → host: start/stop/reconfigure the live tile stream for this connection. */
public final class StreamRequest {
    public static final int PRESET_SPEED = 0;
    public static final int PRESET_BALANCED = 1;
    public static final int PRESET_QUALITY = 2;

    public final boolean enabled;
    public final int preset;
    /** Monitor index from {@link ScreenInfo#monitors}, or {@link ScreenInfo#ALL_MONITORS}. */
    public final int monitor;
    /** Frames-per-second cap (1..60). */
    public final int maxFps;

    public StreamRequest(boolean enabled, int preset, int monitor, int maxFps) {
        this.enabled = enabled;
        this.preset = preset;
        this.monitor = monitor;
        this.maxFps = Math.max(1, Math.min(60, maxFps));
    }

    public static StreamRequest stop() {
        return new StreamRequest(false, PRESET_BALANCED, ScreenInfo.ALL_MONITORS, 30);
    }

    public byte[] encode() {
        return new WireWriter(8).u8(enabled ? 1 : 0).u8(preset).u8(monitor).u8(maxFps).toByteArray();
    }

    public static StreamRequest decode(byte[] data) {
        WireReader r = new WireReader(data);
        return new StreamRequest(r.u8() != 0, r.u8(), r.u8(), r.u8());
    }

    @Override
    public String toString() {
        return "StreamRequest[" + (enabled ? "on" : "off") + ", preset=" + preset + ", monitor=" + monitor + ", fps=" + maxFps + "]";
    }
}
