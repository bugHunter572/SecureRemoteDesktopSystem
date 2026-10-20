package com.srd.client;

import com.srd.client.input.InputInjector;
import com.srd.client.overlay.AnnotationHub;
import com.srd.client.stream.ScreenStreamer;
import com.srd.common.crypto.SessionCrypto;
import com.srd.common.input.InputEvent;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.ui.ViewerSession;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * End-to-end benchmark on 127.0.0.1: real capture → ScreenStreamer → MuxConnection (encrypted)
 * → VideoDecoder, with acks driving the pacing, exactly like a real viewer. It never injects
 * input. With {@code --animate} it opens a small window that scrolls text and moves a gradient,
 * to produce screen changes.
 *
 * <pre>java -cp ... com.srd.client.LoopbackBench [--seconds 10] [--animate] [--preset 0|1|2]</pre>
 */
public final class LoopbackBench {
    public static void main(String[] args) throws Exception {
        int seconds = 10;
        boolean animate = false;
        int preset = StreamRequest.PRESET_BALANCED;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seconds")) seconds = Integer.parseInt(args[++i]);
            else if (args[i].equals("--animate")) animate = true;
            else if (args[i].equals("--preset")) preset = Integer.parseInt(args[++i]);
        }
        JFrame anim = animate ? openAnimation() : null;

        byte[] psk = SessionCrypto.pskKey("bench");
        ScreenStreamer streamer = new ScreenStreamer();
        System.out.println("Layout: " + streamer.layout());
        AnnotationHub hub = new AnnotationHub(streamer::layout);
        InputInjector noInput = new InputInjector() {
            @Override
            public void inject(Object c, InputEvent e, Rectangle p, Rectangle l) {
            }

            @Override
            public void releaseAll(Object c) {
            }

            @Override
            public String name() {
                return "none";
            }
        };

        MuxConnection host;
        MuxConnection viewer;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<MuxConnection> acc = CompletableFuture.supplyAsync(() -> {
                try {
                    Socket s = ss.accept();
                    return MuxConnection.accept(s, Hello.builder(Hello.ROLE_HOST).build(), psk);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            viewer = MuxConnection.connect("127.0.0.1", ss.getLocalPort(), 2000, Hello.builder(Hello.ROLE_VIEWER).build(), psk);
            host = acc.get(5, TimeUnit.SECONDS);
        }
        HostSession hs = new HostSession(host, streamer, noInput, hub, () -> false, () -> false);
        host.start((c, t, p) -> hs.handle(t, p));

        ViewerSession[] vs = new ViewerSession[1];
        SwingUtilities.invokeAndWait(() -> vs[0] = new ViewerSession(viewer, "bench"));
        AtomicInteger frames = new AtomicInteger();
        vs[0].addFrameListener(id -> frames.incrementAndGet());
        viewer.start((c, t, p) -> vs[0].handle(t, p));
        long t0 = System.nanoTime();
        vs[0].startStream(preset, ScreenInfo.ALL_MONITORS, 60);

        long firstFrameMs = -1;
        long lastBytes = 0;
        int lastFrames = 0;
        for (int s = 0; s < seconds * 10; s++) {
            Thread.sleep(100);
            if (firstFrameMs < 0 && frames.get() > 0) firstFrameMs = (System.nanoTime() - t0) / 1_000_000;
            if (s % 10 == 9) {
                long b = viewer.bytesIn();
                int f = frames.get();
                System.out.println(String.format(Locale.ROOT, "t=%2ds  %3d fps  %7.2f Mbit/s  rtt %.2f ms  | %s",
                        (s + 1) / 10, f - lastFrames, (b - lastBytes) * 8 / 1e6, viewer.rttMs(),
                        vs[0].statsText().replace('\n', ' ')));
                lastBytes = b;
                lastFrames = f;
            }
        }
        System.out.println("first frame after " + firstFrameMs + " ms; total frames " + frames.get()
                + "; total " + viewer.bytesIn() / 1024 + " KiB");
        vs[0].close();
        hs.close();
        viewer.close();
        host.close();
        if (anim != null) SwingUtilities.invokeLater(anim::dispose);
        Thread.sleep(Long.getLong("bench.exitDelay", 0));
        System.exit(0);
    }

    private static JFrame openAnimation() throws Exception {
        JFrame[] f = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame fr = new JFrame("LoopbackBench animation");
            final int[] tick = {0};
            JComponent c = new JComponent() {
                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0;
                    int t = tick[0];
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color((t * 3) & 0xFF, 120, 200));
                    g.fillRect((t * 7) % Math.max(1, getWidth() - 120), 20, 120, 80);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                    for (int line = 0; line < 30; line++) {
                        g.drawString("Line " + (line + t / 4) + ": the quick brown fox jumps over the lazy dog 0123456789",
                                10, 130 + line * 18 - (t % 4) * 4);
                    }
                }
            };
            fr.setContentPane(c);
            fr.setSize(800, 600);
            fr.setLocation(100, 100);
            fr.setVisible(true);
            new Timer(16, e -> {
                tick[0]++;
                c.repaint();
            }).start();
            f[0] = fr;
        });
        return f[0];
    }
}
