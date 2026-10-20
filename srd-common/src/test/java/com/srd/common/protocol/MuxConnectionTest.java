package com.srd.common.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.crypto.SessionCrypto;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MuxConnectionTest {
    private static final byte[] PSK = SessionCrypto.pskKey("test-psk");

    private static MuxConnection[] pair(byte[] serverPsk, byte[] clientPsk) throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<MuxConnection> server = CompletableFuture.supplyAsync(() -> {
                try {
                    Socket s = ss.accept();
                    return MuxConnection.accept(s, Hello.builder(Hello.ROLE_SERVER).put(Hello.K_NAME, "srv").build(), serverPsk);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            MuxConnection client = MuxConnection.connect("127.0.0.1", ss.getLocalPort(), 2000,
                    Hello.builder(Hello.ROLE_AGENT).put(Hello.K_NAME, "cli").build(), clientPsk);
            return new MuxConnection[] {server.get(5, TimeUnit.SECONDS), client};
        }
    }

    @Test
    void handshakeExchangesHellos() throws Exception {
        MuxConnection[] p = pair(PSK, PSK);
        assertEquals("cli", p[0].remoteHello().name());
        assertEquals(Hello.ROLE_AGENT, p[0].remoteHello().role());
        assertEquals("srv", p[1].remoteHello().name());
        p[0].close();
        p[1].close();
    }

    @Test
    void wrongPskFailsHandshake() throws Exception {
        assertThrows(Exception.class, () -> pair(PSK, SessionCrypto.pskKey("other")));
    }

    @Test
    void messagesArriveIntactAndInLaneOrder() throws Exception {
        MuxConnection[] p = pair(PSK, PSK);
        List<int[]> got = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        p[0].start(new MuxConnection.Listener() {
            @Override
            public void onMessage(MuxConnection c, int type, byte[] payload) {
                got.add(new int[] {type, payload.length, payload.length > 0 ? payload[0] : -1});
                if (type == MessageType.CONTROL) done.countDown();
            }
        });
        p[1].start((c, t, pl) -> { });
        byte[] big = new byte[60_000];
        big[0] = 42;
        for (int i = 0; i < 20; i++) p[1].send(MessageType.VIDEO, big);
        p[1].send(MessageType.INPUT, new byte[] {7});
        p[1].sendText(MessageType.CONTROL, "END");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        Thread.sleep(300);
        int videos = 0;
        int inputIndex = -1;
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i)[0] == MessageType.VIDEO) {
                videos++;
                assertEquals(60_000, got.get(i)[1]);
                assertEquals(42, got.get(i)[2]);
            }
            if (got.get(i)[0] == MessageType.INPUT) inputIndex = i;
        }
        assertEquals(20, videos);
        // The interactive lane overtakes queued bulk video (it can't be stuck behind all 20 frames).
        assertTrue(inputIndex >= 0 && inputIndex < 20, "input at index " + inputIndex);
        p[0].close();
        p[1].close();
    }

    @Test
    void closeNotifiesPeer() throws Exception {
        MuxConnection[] p = pair(PSK, PSK);
        CountDownLatch closed = new CountDownLatch(1);
        p[0].start(new MuxConnection.Listener() {
            @Override
            public void onMessage(MuxConnection c, int type, byte[] payload) {
            }

            @Override
            public void onClosed(MuxConnection c, String reason) {
                closed.countDown();
            }
        });
        p[1].start((c, t, pl) -> { });
        p[1].closeGracefully("bye");
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void helloRoundTrip() {
        Hello h = Hello.builder("x").put("a", "1").put("ü", "ö").build();
        assertEquals(h.asMap(), Hello.decode(h.encode()).asMap());
    }

    @Test
    void streamRequestAndScreenInfoRoundTrip() {
        StreamRequest r = new StreamRequest(true, 2, 1, 45);
        StreamRequest r2 = StreamRequest.decode(r.encode());
        assertEquals(r.toString(), r2.toString());
        ScreenInfo si = new ScreenInfo(3840, 1080, List.of(new java.awt.Rectangle(0, 0, 1920, 1080),
                new java.awt.Rectangle(1920, 0, 1920, 1080)), 255);
        ScreenInfo si2 = ScreenInfo.decode(si.encode());
        assertEquals(si.width, si2.width);
        assertEquals(si.monitors, si2.monitors);
        assertArrayEquals(si.encode(), si2.encode());
    }
}
