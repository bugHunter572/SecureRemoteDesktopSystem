package com.srd.client;

import com.srd.client.audio.AudioEngine;
import com.srd.client.input.InputInjector;
import com.srd.client.overlay.AnnotationHub;
import com.srd.client.stream.ScreenStreamer;
import com.srd.common.input.InputEvent;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.protocol.WireReader;
import com.srd.common.util.Log;
import java.awt.Rectangle;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Host half of a session: answers one viewer (the server console or a peer viewer) on one
 * connection. It handles stream requests and acks, remote input, audio subscription and pen
 * strokes. Every viewer has its own subscriptions, so one viewer stopping audio or closing its
 * window never affects another (the old global START/STOP_AUDIO did exactly that).
 */
public final class HostSession {
    private final MuxConnection conn;
    private final ScreenStreamer streamer;
    private final InputInjector injector;
    private final AnnotationHub hub;
    private final BooleanSupplier controlAllowed;
    private final BooleanSupplier drawAllowed;
    private volatile ScreenStreamer.Subscription stream;
    private volatile boolean audio;
    private volatile boolean closed;
    private boolean warnedControl;

    public HostSession(MuxConnection conn, ScreenStreamer streamer, InputInjector injector, AnnotationHub hub,
                       BooleanSupplier controlAllowed, BooleanSupplier drawAllowed) {
        this.conn = conn;
        this.streamer = streamer;
        this.injector = injector;
        this.hub = hub;
        this.controlAllowed = controlAllowed;
        this.drawAllowed = drawAllowed;
        hub.addViewer(conn, this::physicalRegion);
    }

    private Rectangle physicalRegion() {
        ScreenStreamer.Subscription s = stream;
        return s != null ? s.physicalRegion() : streamer.layout().physicalRegion(-1);
    }

    private Rectangle logicalRegion() {
        ScreenStreamer.Subscription s = stream;
        return s != null ? s.logicalRegion() : streamer.layout().logicalRegion(-1);
    }

    public boolean isStreaming() {
        return stream != null;
    }

    /** @return true if the message was a viewer→host message and has been handled */
    public boolean handle(int type, byte[] p) {
        if (closed) return false;
        switch (type) {
            case MessageType.STREAM_REQUEST: {
                StreamRequest req = StreamRequest.decode(p);
                Log.info(conn + " " + req);
                stream = streamer.apply(stream, conn, req);
                return true;
            }
            case MessageType.FRAME_ACK: {
                ScreenStreamer.Subscription s = stream;
                if (s != null) s.ack(new WireReader(p).i32());
                return true;
            }
            case MessageType.AUDIO_SUB: {
                boolean on = p.length > 0 && p[0] != 0;
                setAudio(on);
                return true;
            }
            case MessageType.INPUT: {
                if (!controlAllowed.getAsBoolean()) {
                    if (!warnedControl) {
                        warnedControl = true;
                        Log.info("Remote control from " + conn + " ignored: control is disabled");
                    }
                    return true;
                }
                warnedControl = false;
                List<InputEvent> events = InputEvent.decodeBatch(p);
                Rectangle phys = physicalRegion();
                Rectangle log = logicalRegion();
                for (InputEvent e : events) {
                    try {
                        injector.inject(this, e, phys, log);
                    } catch (Exception ex) {
                        Log.warn("input injection failed for " + e + ": " + ex);
                    }
                }
                return true;
            }
            case MessageType.PEN:
                if (drawAllowed.getAsBoolean()) hub.onPen(conn, p);
                return true;
            default:
                return false;
        }
    }

    private synchronized void setAudio(boolean on) {
        if (on == audio) return;
        audio = on;
        if (on) {
            AudioEngine.get().subscribe(this, payload -> conn.send(MessageType.AUDIO, payload));
        } else {
            AudioEngine.get().unsubscribe(this);
        }
        Log.info(conn + " audio " + (on ? "subscribed" : "unsubscribed"));
    }

    /** Releases everything this viewer holds (stream, audio, pressed keys, pen registration). */
    public void close() {
        if (closed) return;
        closed = true;
        ScreenStreamer.Subscription s = stream;
        stream = null;
        if (s != null) streamer.unsubscribe(s);
        setAudio(false);
        try {
            injector.releaseAll(this);
        } catch (Exception e) {
            Log.warn("releaseAll failed: " + e);
        }
        hub.removeViewer(conn);
    }
}
