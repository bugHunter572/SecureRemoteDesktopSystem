package com.srd.common.protocol;

/**
 * Wire message types (protocol v3). Every frame on the wire is {@code [u8 type][u32 len][sealed payload]}.
 *
 * <p>"Host" = the machine whose screen is shared (the client agent). "Viewer" = whoever watches or
 * controls it (the server console, or another client's peer viewer). Either side may be the one
 * that opened the TCP connection.
 */
public final class MessageType {
    private MessageType() {
    }

    // ---- session ----
    /** Initiator → acceptor, first sealed message: {@link Hello}. */
    public static final int HELLO = 1;
    /** Acceptor → initiator reply: {@link Hello}. */
    public static final int HELLO_ACK = 2;
    /** Keepalive / RTT probe: u64 sender nanoTime. Handled inside MuxConnection. */
    public static final int PING = 3;
    public static final int PONG = 4;
    /** UTF-8 reason, then the connection closes. */
    public static final int BYE = 5;

    // ---- host → viewer ----
    /** Stream geometry: see {@link ScreenInfo}. */
    public static final int SCREEN_INFO = 10;
    /** A batch of encoded rects of one frame: see {@code com.srd.common.video.VideoMessage}. */
    public static final int VIDEO = 11;
    /** u16 nx, u16 ny (normalized 0..65535), u8 visible. */
    public static final int CURSOR_POS = 12;
    /** u8 cursor kind ({@code CursorKind}). */
    public static final int CURSOR_SHAPE = 13;
    /** Audio frame: see {@code com.srd.common.audio.AudioPacket}. */
    public static final int AUDIO = 14;
    /** Low-rate small JPEG for the server's thumbnail wall. */
    public static final int THUMBNAIL = 15;
    /** UTF-8 "key=value\n" lines (VPN Guard status, host capabilities…). */
    public static final int STATUS = 16;

    // ---- viewer → host ----
    /** Start/stop the live tile stream: see {@link StreamRequest}. */
    public static final int STREAM_REQUEST = 20;
    /** u32 frameId: viewer has decoded+applied the frame (drives host pacing). */
    public static final int FRAME_ACK = 21;
    /** u8 1=subscribe, 0=unsubscribe to the host's audio. */
    public static final int AUDIO_SUB = 22;
    /** Batch of input events: see {@code com.srd.common.input.InputEvent}. */
    public static final int INPUT = 23;

    // ---- both directions ----
    /** Pen / annotation operation: see {@code com.srd.common.annotation.PenMessage}. */
    public static final int PEN = 30;
    /** UTF-8 text command (SET_INTERVAL:n, SERVER_CONFIG:…, START_RECORD, …). */
    public static final int CONTROL = 31;

    /** Sender lane: 0 = interactive (input/pen/cursor/control), 1 = audio, 2 = bulk video. */
    public static int lane(int type) {
        switch (type) {
            case AUDIO:
                return 1;
            case VIDEO:
            case THUMBNAIL:
                return 2;
            default:
                return 0;
        }
    }

    public static boolean isKnown(int type) {
        switch (type) {
            case HELLO: case HELLO_ACK: case PING: case PONG: case BYE:
            case SCREEN_INFO: case VIDEO: case CURSOR_POS: case CURSOR_SHAPE: case AUDIO:
            case THUMBNAIL: case STATUS: case STREAM_REQUEST: case FRAME_ACK: case AUDIO_SUB:
            case INPUT: case PEN: case CONTROL:
                return true;
            default:
                return false;
        }
    }
}
