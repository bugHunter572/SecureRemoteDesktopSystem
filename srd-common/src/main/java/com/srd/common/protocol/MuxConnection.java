package com.srd.common.protocol;

import com.srd.common.crypto.SessionCrypto;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.AEADBadTagException;

/**
 * One encrypted, multiplexed TCP connection (protocol v3).
 *
 * <p>Why this exists: the old code wrote whole JPEG frames, audio chunks and commands on one
 * stream under one lock, often unbuffered. A 300 KB frame then blocked the audio chunk behind it
 * (audible gaps), every mouse move cost 3 TCP segments, and one stalled peer froze command
 * delivery to everyone. Here every connection has:
 * <ul>
 *   <li>its own sender thread, which owns the socket output and the TX cipher;</li>
 *   <li>three priority lanes, always drained in this order: interactive (input, pen, cursor,
 *       control), audio (bounded, drop-oldest so latency can't build up), and video (bounded;
 *       producers pace themselves with frame acks). Video payloads are kept ≤ ~64 KB by the
 *       encoder, so an input event or audio frame waits at most one chunk;</li>
 *   <li>buffered I/O, flushed after each interactive/audio frame and whenever the queues
 *       drain (so a burst of video goes out in a few large writes);</li>
 *   <li>PING/PONG every 2 s for RTT and liveness. The connection closes if nothing has been
 *       received for 10 s or a write has been stuck for 10 s.</li>
 * </ul>
 */
public final class MuxConnection implements Closeable {
    public static final int VERSION = 3;
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final byte[] MAGIC = "SRD3".getBytes(StandardCharsets.US_ASCII);
    private static final int AUDIO_QUEUE = 24;      // ~480 ms of 20 ms frames
    private static final int VIDEO_QUEUE = 1024;
    private static final long PING_MS = 2000;
    private static final long RX_TIMEOUT_MS = 10000; // 5 missed pings
    private static final long TX_STALL_MS = 10000;
    private static final AtomicInteger IDS = new AtomicInteger();

    /** Receives messages on this connection's reader thread; keep handlers fast. */
    public interface Listener {
        void onMessage(MuxConnection c, int type, byte[] payload) throws Exception;

        default void onClosed(MuxConnection c, String reason) {
        }
    }

    private static final class Out {
        final int type;
        final byte[] payload;
        final boolean closeAfter;

        Out(int type, byte[] payload, boolean closeAfter) {
            this.type = type;
            this.payload = payload;
            this.closeAfter = closeAfter;
        }
    }

    private final int id = IDS.incrementAndGet();
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SessionCrypto crypto;
    private final Hello localHello;
    private final Hello remoteHello;
    private final boolean initiator;

    private final ConcurrentLinkedQueue<Out> lane0 = new ConcurrentLinkedQueue<>();
    private final ArrayBlockingQueue<Out> lane1 = new ArrayBlockingQueue<>(AUDIO_QUEUE);
    private final ArrayBlockingQueue<Out> lane2 = new ArrayBlockingQueue<>(VIDEO_QUEUE);
    private final Semaphore pending = new Semaphore(0);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private volatile long lastReceivedMs = System.currentTimeMillis();
    private volatile long writingSinceMs;
    private volatile double rttMs = -1;
    private volatile Listener listener;
    private volatile ScheduledFuture<?> pinger;
    private volatile String closeReason;
    private volatile Object attachment;

    private MuxConnection(Socket socket, DataInputStream in, DataOutputStream out, SessionCrypto crypto,
                          Hello local, Hello remote, boolean initiator) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.crypto = crypto;
        this.localHello = local;
        this.remoteHello = remote;
        this.initiator = initiator;
    }

    // ------------------------------------------------------------------ handshake

    private static void tune(Socket s) throws SocketException {
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        // Moderate buffers: big enough for LAN throughput, small enough not to hide seconds of
        // stale video in the kernel (bufferbloat = latency).
        s.setSendBufferSize(256 * 1024);
        s.setReceiveBufferSize(512 * 1024);
    }

    /** Opens a connection and performs the handshake as initiator. */
    public static MuxConnection connect(String host, int port, int timeoutMs, Hello local, byte[] pskKey) throws IOException {
        return connect(host, port, timeoutMs, local, pskKey, null);
    }

    /**
     * Same, from a chosen local address (null = any). Lets load tests and multi-homed hosts present
     * many distinct source IPs; the server identifies agents by their source IP.
     */
    public static MuxConnection connect(String host, int port, int timeoutMs, Hello local, byte[] pskKey,
                                        java.net.InetAddress localAddress) throws IOException {
        Socket s = new Socket();
        try {
            tune(s);
            if (localAddress != null) s.bind(new InetSocketAddress(localAddress, 0));
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(10_000); // handshake only
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

            byte[] myNonce = SessionCrypto.randomNonce();
            out.write(MAGIC);
            out.writeShort(VERSION);
            out.write(myNonce);
            out.flush();

            readMagic(in);
            int peerVersion = in.readUnsignedShort();
            int status = in.readUnsignedByte();
            byte[] peerNonce = new byte[SessionCrypto.NONCE_BYTES];
            in.readFully(peerNonce);
            if (status != 0) {
                throw new IOException("peer rejected protocol version " + VERSION + " (peer speaks " + peerVersion + ")");
            }
            SessionCrypto crypto = SessionCrypto.derive(pskKey, myNonce, peerNonce, true);
            writeFrameDirect(out, crypto, MessageType.HELLO, local.encode());
            out.flush();
            byte[] ack = readFrameDirect(in, crypto, MessageType.HELLO_ACK);
            Hello remote = Hello.decode(ack);
            s.setSoTimeout(0);
            return new MuxConnection(s, in, out, crypto, local, remote, true);
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** Performs the handshake as acceptor on an already-accepted socket. */
    public static MuxConnection accept(Socket s, Hello local, byte[] pskKey) throws IOException {
        try {
            tune(s);
            s.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
            readMagic(in);
            int peerVersion = in.readUnsignedShort();
            byte[] peerNonce = new byte[SessionCrypto.NONCE_BYTES];
            in.readFully(peerNonce);
            byte[] myNonce = SessionCrypto.randomNonce();
            out.write(MAGIC);
            out.writeShort(VERSION);
            out.writeByte(peerVersion == VERSION ? 0 : 1);
            out.write(myNonce);
            out.flush();
            if (peerVersion != VERSION) {
                throw new IOException("peer speaks protocol " + peerVersion + ", need " + VERSION);
            }
            SessionCrypto crypto = SessionCrypto.derive(pskKey, peerNonce, myNonce, false);
            Hello remote = Hello.decode(readFrameDirect(in, crypto, MessageType.HELLO));
            writeFrameDirect(out, crypto, MessageType.HELLO_ACK, local.encode());
            out.flush();
            s.setSoTimeout(0);
            return new MuxConnection(s, in, out, crypto, local, remote, false);
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void readMagic(DataInputStream in) throws IOException {
        byte[] m = new byte[MAGIC.length];
        in.readFully(m);
        if (!Arrays.equals(m, MAGIC)) {
            throw new IOException("not an SRD v3 peer (outdated client/server? both sides must be upgraded together)");
        }
    }

    private static void writeFrameDirect(DataOutputStream out, SessionCrypto c, int type, byte[] payload) throws IOException {
        try {
            byte[] sealed = c.seal(type, payload, 0, payload.length);
            out.writeByte(type);
            out.writeInt(sealed.length);
            out.write(sealed);
        } catch (GeneralSecurityException e) {
            throw new IOException("encrypt failed", e);
        }
    }

    private static byte[] readFrameDirect(DataInputStream in, SessionCrypto c, int expectedType) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        if (type != expectedType || len < 0 || len > 64 * 1024) {
            throw new IOException("bad handshake frame type=" + type + " len=" + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        try {
            return c.open(type, buf, 0, len);
        } catch (AEADBadTagException e) {
            throw new IOException("authentication failed: pre-shared key mismatch");
        } catch (GeneralSecurityException e) {
            throw new IOException("decrypt failed", e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the reader, sender and keepalive. Call exactly once. */
    public void start(Listener l) {
        this.listener = l;
        String tag = "mux" + id + "-" + remoteAddress();
        Threads.start(tag + "-rx", Thread.MAX_PRIORITY - 1, this::readLoop);
        Threads.start(tag + "-tx", Thread.MAX_PRIORITY - 1, this::sendLoop);
        pinger = Threads.scheduler().scheduleAtFixedRate(this::keepalive, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
    }

    public Hello localHello() {
        return localHello;
    }

    public Hello remoteHello() {
        return remoteHello;
    }

    public boolean isInitiator() {
        return initiator;
    }

    public String remoteAddress() {
        return socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "?";
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public String closeReason() {
        return closeReason;
    }

    /** Round-trip time in ms from the last PING, or -1 before the first PONG. */
    public double rttMs() {
        return rttMs;
    }

    public long bytesIn() {
        return bytesIn.get();
    }

    public long bytesOut() {
        return bytesOut.get();
    }

    /** Video messages queued but not yet written. */
    public int videoBacklog() {
        return lane2.size();
    }

    public Object attachment() {
        return attachment;
    }

    public void attach(Object o) {
        attachment = o;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Queues a message. Audio drops its oldest frame when full; video returns false when full
     * (the producer should back off). Never blocks.
     */
    public boolean send(int type, byte[] payload) {
        if (closed.get()) return false;
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too large: " + payload.length);
        Out o = new Out(type, payload, false);
        switch (MessageType.lane(type)) {
            case 1:
                while (!lane1.offer(o)) lane1.poll();
                break;
            case 2:
                if (!lane2.offer(o)) return false;
                break;
            default:
                lane0.add(o);
        }
        pending.release();
        return true;
    }

    public boolean sendText(int type, String text) {
        return send(type, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Drops every queued (not yet written) video message, e.g. when a stream is stopped. */
    public void clearVideo() {
        lane2.clear();
    }

    /** Sends BYE with a reason, then closes once it has been flushed. */
    public void closeGracefully(String reason) {
        if (closed.get()) return;
        lane0.add(new Out(MessageType.BYE, reason.getBytes(StandardCharsets.UTF_8), true));
        pending.release();
        Threads.scheduler().schedule(() -> close(reason), 2, TimeUnit.SECONDS);
    }

    private void sendLoop() {
        try {
            while (!closed.get()) {
                if (!pending.tryAcquire(1, TimeUnit.SECONDS)) continue;
                Out o = lane0.poll();
                if (o == null) o = lane1.poll();
                if (o == null) o = lane2.poll();
                if (o == null) continue; // permit left over from a dropped audio frame
                writingSinceMs = System.currentTimeMillis();
                byte[] sealed = crypto.seal(o.type, o.payload, 0, o.payload.length);
                out.writeByte(o.type);
                out.writeInt(sealed.length);
                out.write(sealed);
                boolean drained = lane0.isEmpty() && lane1.isEmpty() && lane2.isEmpty();
                if (MessageType.lane(o.type) < 2 || drained || o.closeAfter) out.flush();
                writingSinceMs = 0;
                bytesOut.addAndGet(sealed.length + 5L);
                if (o.closeAfter) {
                    close(new String(o.payload, StandardCharsets.UTF_8));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            close("send failed: " + e.getMessage());
        } catch (Exception e) {
            Log.error("mux" + id + " sender crashed", e);
            close("sender error: " + e);
        }
    }

    // ------------------------------------------------------------------ receiving

    private void readLoop() {
        try {
            while (!closed.get()) {
                int type = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > MAX_PAYLOAD + 64) throw new ProtocolException("bad frame length " + len);
                if (!MessageType.isKnown(type)) throw new ProtocolException("unknown message type " + type);
                byte[] buf = new byte[len];
                in.readFully(buf);
                lastReceivedMs = System.currentTimeMillis();
                bytesIn.addAndGet(len + 5L);
                byte[] plain = crypto.open(type, buf, 0, len);
                switch (type) {
                    case MessageType.PING:
                        send(MessageType.PONG, plain);
                        break;
                    case MessageType.PONG:
                        if (plain.length >= 8) {
                            long sent = new WireReader(plain).i64();
                            rttMs = (System.nanoTime() - sent) / 1_000_000.0;
                        }
                        break;
                    case MessageType.BYE:
                        close("peer closed: " + new String(plain, StandardCharsets.UTF_8));
                        return;
                    default:
                        Listener l = listener;
                        if (l != null) {
                            try {
                                l.onMessage(this, type, plain);
                            } catch (ProtocolException e) {
                                throw e;
                            } catch (Exception e) {
                                Log.error("mux" + id + " handler failed for type " + type, e);
                            }
                        }
                }
            }
        } catch (EOFException e) {
            close("connection closed by peer");
        } catch (AEADBadTagException e) {
            close("message authentication failed");
        } catch (IOException e) {
            close(closed.get() ? closeReason : "read failed: " + e.getMessage());
        } catch (ProtocolException e) {
            close("protocol error: " + e.getMessage());
        } catch (Exception e) {
            Log.error("mux" + id + " reader crashed", e);
            close("reader error: " + e);
        }
    }

    private void keepalive() {
        if (closed.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastReceivedMs > RX_TIMEOUT_MS) {
            close("timeout: nothing received for " + (now - lastReceivedMs) / 1000 + " s");
            return;
        }
        long ws = writingSinceMs;
        if (ws != 0 && now - ws > TX_STALL_MS) {
            close("send stalled for " + (now - ws) / 1000 + " s");
            return;
        }
        send(MessageType.PING, new WireWriter(8).i64(System.nanoTime()).toByteArray());
    }

    @Override
    public void close() {
        close("closed locally");
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        closeReason = reason;
        ScheduledFuture<?> p = pinger;
        if (p != null) p.cancel(false);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        pending.release();
        lane1.clear();
        lane2.clear();
        Listener l = listener;
        if (l != null) {
            try {
                l.onClosed(this, reason);
            } catch (Exception e) {
                Log.error("mux" + id + " onClosed handler failed", e);
            }
        }
    }

    @Override
    public String toString() {
        return "Mux#" + id + "[" + remoteAddress() + " " + remoteHello.role() + "/" + remoteHello.name() + "]";
    }
}
