package com.srd.client.net;

import com.srd.client.ClientContext;
import com.srd.client.HostSession;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * "Broadcasting": accepts peer viewers on the peer port (2583). Each viewer gets its own
 * {@link HostSession}, so stream, audio and control are independent per viewer.
 */
public final class PeerHost {
    private static final Map<MuxConnection, HostSession> VIEWERS = new ConcurrentHashMap<>();
    private static final ExecutorService HANDSHAKES = Threads.pool("peer-handshake", 4);

    private final ClientContext ctx;
    private final BiConsumer<String, Boolean> viewerListener;
    private volatile ServerSocket server;

    /**
     * @param viewerListener (viewer name, connected?) — e.g. to show a tray notice
     */
    public PeerHost(ClientContext ctx, BiConsumer<String, Boolean> viewerListener) {
        this.ctx = ctx;
        this.viewerListener = viewerListener;
    }

    public static int viewerCount() {
        return VIEWERS.size();
    }

    public boolean isRunning() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    public synchronized void start() throws IOException {
        if (isRunning()) return;
        ServerSocket ss = new ServerSocket(ctx.config.peerPort());
        server = ss;
        Log.info("Broadcasting: accepting peer viewers on port " + ss.getLocalPort());
        Threads.start("peer-accept", () -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    HANDSHAKES.execute(() -> accept(s));
                } catch (IOException e) {
                    if (!ss.isClosed()) Log.warn("peer accept failed: " + e);
                }
            }
        });
    }

    private void accept(Socket s) {
        MuxConnection c;
        try {
            c = MuxConnection.accept(s, ctx.hostHello(), ctx.pskKey);
        } catch (IOException e) {
            Log.warn("Peer handshake from " + s.getInetAddress().getHostAddress() + " failed: " + e.getMessage());
            return;
        }
        String who = c.remoteHello().name() + " (" + c.remoteAddress() + ")";
        HostSession hs = new HostSession(c, ctx.streamer, ctx.injector, ctx.annotations,
                ctx::peerControlAllowed, ctx::peerDrawAllowed);
        VIEWERS.put(c, hs);
        Log.info("Peer viewer connected: " + who);
        viewerListener.accept(who, true);
        c.start(new MuxConnection.Listener() {
            @Override
            public void onMessage(MuxConnection m, int type, byte[] p) {
                hs.handle(type, p);
            }

            @Override
            public void onClosed(MuxConnection m, String reason) {
                hs.close();
                VIEWERS.remove(m);
                Log.info("Peer viewer " + who + " left: " + reason);
                viewerListener.accept(who, false);
            }
        });
    }

    public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        for (MuxConnection c : VIEWERS.keySet()) c.closeGracefully("broadcasting stopped");
        Log.info("Broadcasting stopped");
    }
}
