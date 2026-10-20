package com.srd.client;

import com.srd.common.annotation.PenMessage;
import com.srd.common.annotation.PenTool;
import com.srd.common.annotation.Stroke;
import com.srd.common.crypto.SessionCrypto;
import com.srd.common.input.InputEvent;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.protocol.ScreenInfo;
import com.srd.common.protocol.StreamRequest;
import com.srd.common.ui.ViewerSession;
import com.srd.common.video.FrameBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/**
 * Integration probe against a RUNNING client's peer port: streams video + audio for a few seconds,
 * then draws a thick red stroke across the middle of the host screen and checks that the stroke is
 * NOT in the captured video (overlay excluded from capture), then clears it. Never sends input.
 *
 * <pre>PeerProbe host port [psk]</pre>
 */
public final class PeerProbe {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        byte[] psk = SessionCrypto.pskKey(args.length > 2 ? args[2] : ClientConfig.DEFAULT_PSK);
        MuxConnection c = MuxConnection.connect(host, port, 3000,
                Hello.builder(Hello.ROLE_VIEWER).put(Hello.K_NAME, "PeerProbe").build(), psk);
        System.out.println("connected to " + c.remoteHello());
        ViewerSession[] vs = new ViewerSession[1];
        SwingUtilities.invokeAndWait(() -> vs[0] = new ViewerSession(c, "PeerProbe"));
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger audio = new AtomicInteger();
        vs[0].addFrameListener(f -> frames.incrementAndGet());
        vs[0].addAudioListener(p -> audio.incrementAndGet());
        c.start((m, t, p) -> vs[0].handle(t, p));
        vs[0].startStream(StreamRequest.PRESET_BALANCED, ScreenInfo.ALL_MONITORS, 60);
        Thread.sleep(4000);
        System.out.println("4 s: frames=" + frames.get() + " audioPackets=" + audio.get() + " (expect ~200) rtt=" + c.rttMs()
                + " screen=" + vs[0].screenInfo());

        // Remote input through the real injector (SendInput on Windows): move the pointer 40 px and
        // back, checking it lands on the exact pixel. No clicks, no keys.
        ScreenInfo si = vs[0].screenInfo();
        java.awt.Point p0 = java.awt.MouseInfo.getPointerInfo().getLocation();
        int tx = Math.min(si.width - 1, p0.x + 40), ty = Math.min(si.height - 1, p0.y + 30);
        int nx = (int) Math.round((tx + 0.5) / si.width * 65535), ny = (int) Math.round((ty + 0.5) / si.height * 65535);
        c.send(MessageType.INPUT, InputEvent.encode(InputEvent.move(nx, ny)));
        Thread.sleep(300);
        java.awt.Point p1 = java.awt.MouseInfo.getPointerInfo().getLocation();
        int bx = (int) Math.round((p0.x + 0.5) / si.width * 65535), by = (int) Math.round((p0.y + 0.5) / si.height * 65535);
        c.send(MessageType.INPUT, InputEvent.encode(InputEvent.move(bx, by)));
        Thread.sleep(300);
        java.awt.Point p2 = java.awt.MouseInfo.getPointerInfo().getLocation();
        System.out.println("input: target " + tx + "," + ty + " -> pointer " + p1.x + "," + p1.y
                + (p1.x == tx && p1.y == ty ? " (exact)" : " (MISMATCH)") + "; back to " + p0.x + "," + p0.y + " -> " + p2.x + "," + p2.y
                + (p2.equals(p0) ? " (exact)" : " (MISMATCH)"));

        // Draw a 40 px wide pure-red horizontal band at y = 50%.
        Stroke s = new Stroke(Stroke.id(4242, 1), 4242, "PeerProbe", PenTool.PEN, 0xFFFF0000, 40f / 1920, 0);
        c.send(MessageType.PEN, PenMessage.begin(s, 1, new float[] {0.2f, 0.5f}));
        c.send(MessageType.PEN, PenMessage.points(4242, 1, new float[] {0.5f, 0.5f, 0.8f, 0.5f}));
        c.send(MessageType.PEN, PenMessage.end(4242, 1));
        Thread.sleep(2500);
        FrameBuffer fb = vs[0].frameBuffer();
        int red = 0;
        int y = fb.height / 2;
        for (int x = fb.width / 4; x < fb.width * 3 / 4; x++) {
            int p = fb.pixels[y * fb.width + x];
            if (((p >> 16) & 0xFF) > 230 && ((p >> 8) & 0xFF) < 30 && (p & 0xFF) < 30) red++;
        }
        System.out.println("pure-red pixels on the stroke line in the captured video: " + red + " of " + fb.width / 2
                + (red < fb.width / 20 ? "  => overlay EXCLUDED from capture (good)" : "  => overlay visible in capture!"));
        c.send(MessageType.PEN, PenMessage.clear(0));
        Thread.sleep(500);
        System.out.println("total frames=" + frames.get() + " audio=" + audio.get());
        vs[0].close();
        c.closeGracefully("probe done");
        Thread.sleep(300);
        System.exit(0);
    }
}
