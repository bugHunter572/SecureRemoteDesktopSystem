package com.srd.server.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.audio.AudioPacket;
import com.srd.common.crypto.SessionCrypto;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.ui.ViewerSession;
import com.srd.common.video.FrameBuffer;
import com.srd.common.video.Quality;
import com.srd.common.video.TileEncoder;
import com.srd.common.video.TileGrid;
import com.srd.common.video.VideoMessage;
import com.srd.server.net.ClientSession;
import com.srd.server.net.PeerSession;
import com.srd.server.net.RemoteSession;
import com.srd.server.net.SessionEvents;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end over loopback: a fake agent streams tile video + 48 kHz audio on request; the server
 * side records it headless through {@link RemoteSession#acquireViewer} + {@link Recorder} and
 * encodes with ffmpeg (if bin/ffmpeg.exe or ffmpeg on PATH is available). Opens no window.
 */
class RecorderIntegrationTest {
    private static final byte[] KEY = SessionCrypto.pskKey("MySecretKey12345");

    static {
        // Opt-in: -Dsrd.it.display=true switches this test JVM to non-headless before AWT starts.
        if (Boolean.getBoolean("srd.it.display")) System.setProperty("java.awt.headless", "false");
    }

    @TempDir
    File tmp;

    @Test
    void recordsStreamedVideoAndAudio() throws Exception {
        // ViewerSession builds a RemoteScreenPanel, whose static init needs a display (HeadlessException
        // under the build's -Djava.awt.headless=true). Run explicitly with:
        // mvn -o test -pl srd-server -Dtest=RecorderIntegrationTest -Dsrd.it.display=true
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "needs a display");
        ServerSocket ss = new ServerSocket(0);
        CompletableFuture<MuxConnection> serverSide = CompletableFuture.supplyAsync(() -> {
            try (ServerSocket s = ss) {
                Socket sock = s.accept();
                return MuxConnection.accept(sock, Hello.builder(Hello.ROLE_SERVER).put(Hello.K_NAME, "t").build(), KEY);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        MuxConnection agent = MuxConnection.connect("127.0.0.1", ss.getLocalPort(), 3000,
                Hello.builder(Hello.ROLE_AGENT).put(Hello.K_NAME, "rec-agent").build(), KEY);
        AtomicReference<StreamRequest> req = new AtomicReference<>(StreamRequest.stop());
        AtomicBoolean audioOn = new AtomicBoolean();
        agent.start((c, type, p) -> {
            if (type == MessageType.STREAM_REQUEST) req.set(StreamRequest.decode(p));
            if (type == MessageType.AUDIO_SUB) audioOn.set(p[0] != 0);
        });
        AtomicBoolean running = new AtomicBoolean(true);
        Thread streamer = new Thread(() -> stream(agent, req, audioOn, running), "fake-agent-stream");
        streamer.setDaemon(true);
        streamer.start();

        MuxConnection conn = serverSide.get(10, TimeUnit.SECONDS);
        ClientSession session = new ClientSession(conn, new NoEvents(), Runnable::run);
        conn.start(session);

        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        try {
            ViewerSession v = session.acquireViewer(RemoteSession.USER_RECORD);
            CompletableFuture<File> done = new CompletableFuture<>();
            File dateDir = new File(tmp, "127.0.0.1_rec-agent" + File.separator + "20260101");
            Recorder r = new Recorder("127.0.0.1", v, dateDir, false, io, sampler, (rec, out) -> done.complete(out));
            r.start();
            // speed preset / 10 fps requested by the headless recorder
            for (int i = 0; i < 50 && !req.get().enabled; i++) Thread.sleep(50);
            assertTrue(req.get().enabled);
            assertEquals(StreamRequest.PRESET_SPEED, req.get().preset);
            assertEquals(10, req.get().maxFps);
            assertTrue(audioOn.get() || waitFor(audioOn), "recorder subscribes to audio");

            Thread.sleep(3000);
            r.stop();
            session.releaseViewer(RemoteSession.USER_RECORD);
            File out = done.get(60, TimeUnit.SECONDS);

            for (int i = 0; i < 40 && req.get().enabled; i++) Thread.sleep(50);
            assertTrue(!req.get().enabled, "stream stopped when the last viewer user left");
            boolean haveFfmpeg = !"ffmpeg".equals(FfmpegEncoder.locate()) || onPath();
            if (haveFfmpeg) {
                assertNotNull(out, "video produced");
                assertTrue(out.getName().endsWith(".mp4"), out.getName()); // audio track present
                assertTrue(out.length() > 1000, "size " + out.length());
                File[] left = dateDir.listFiles(f -> f.getName().startsWith("frames_"));
                assertEquals(0, left == null ? 0 : left.length, "work dir removed: " + Arrays.toString(left));
            } else {
                assertNull(out);
            }
        } finally {
            running.set(false);
            agent.close("test done");
            conn.close("test done");
            io.shutdownNow();
            sampler.shutdownNow();
        }
    }

    private static boolean waitFor(AtomicBoolean b) throws InterruptedException {
        for (int i = 0; i < 40 && !b.get(); i++) Thread.sleep(50);
        return b.get();
    }

    private static boolean onPath() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void stream(MuxConnection c, AtomicReference<StreamRequest> req, AtomicBoolean audio, AtomicBoolean running) {
        try {
            int w = 320, h = 180;
            FrameBuffer fb = new FrameBuffer(w, h);
            TileGrid grid = new TileGrid(w, h);
            boolean[] all = new boolean[grid.count()];
            Arrays.fill(all, true);
            boolean infoSent = false;
            int frameId = 0, seq = 0;
            long next = 0, audioStart = 0;
            double phase = 0;
            while (running.get() && c.isOpen()) {
                long now = System.currentTimeMillis();
                StreamRequest r = req.get();
                if (r.enabled && now >= next) {
                    if (!infoSent) {
                        c.send(MessageType.SCREEN_INFO, new ScreenInfo(w, h, Collections.singletonList(new Rectangle(0, 0, w, h)),
                                ScreenInfo.ALL_MONITORS).encode());
                        infoSent = true;
                    }
                    Graphics2D g = fb.image().createGraphics();
                    g.setColor(Color.DARK_GRAY);
                    g.fillRect(0, 0, w, h);
                    g.setColor(Color.ORANGE);
                    g.fillOval((frameId * 5) % (w - 40), 70, 40, 40);
                    g.dispose();
                    for (byte[] m : VideoMessage.pack(++frameId, frameId == 1,
                            TileEncoder.encode(fb, grid, all, Quality.forPreset(r.preset), false))) {
                        c.send(MessageType.VIDEO, m);
                    }
                    next = now + 1000 / r.maxFps;
                }
                if (audio.get()) {
                    if (audioStart == 0) audioStart = now;
                    while (seq < (now - audioStart) / AudioPacket.FRAME_MS) {
                        byte[] pcm = new byte[AudioPacket.FRAME_BYTES];
                        for (int i = 0; i < AudioPacket.SAMPLES_PER_FRAME; i++) {
                            short s = (short) (Math.sin(phase) * 5000);
                            phase += 2 * Math.PI * 440 / AudioPacket.SAMPLE_RATE;
                            for (int ch = 0; ch < 2; ch++) {
                                pcm[(i * 2 + ch) * 2] = (byte) s;
                                pcm[(i * 2 + ch) * 2 + 1] = (byte) (s >> 8);
                            }
                        }
                        c.send(MessageType.AUDIO, new AudioPacket(seq++, AudioPacket.SAMPLE_RATE, 2, pcm).encode());
                    }
                }
                Thread.sleep(5);
            }
        } catch (Exception e) {
            if (running.get()) e.printStackTrace();
        }
    }

    private static final class NoEvents implements SessionEvents {
        @Override
        public void onThumbnail(ClientSession s, byte[] jpeg) {
        }

        @Override
        public void onStatus(ClientSession s, Map<String, String> status) {
        }

        @Override
        public void onClientClosed(ClientSession s, String reason) {
        }

        @Override
        public void onPeerClosed(PeerSession s, String reason) {
        }
    }
}
