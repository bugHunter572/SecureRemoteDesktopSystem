package com.srd.server.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.crypto.SessionCrypto;
import com.srd.common.protocol.Hello;
import com.srd.common.protocol.MessageType;
import com.srd.common.protocol.MuxConnection;
import com.srd.common.status.StatusMessage;
import com.srd.common.status.VpnGuardStatus;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loopback test of the accept path: v3 handshake, session routing, and outdated-client detection. */
class ClientListenerTest {
    private static final byte[] KEY = SessionCrypto.pskKey("MySecretKey12345");

    private final BlockingQueue<MuxConnection> connected = new ArrayBlockingQueue<>(4);
    private final BlockingQueue<String> outdated = new ArrayBlockingQueue<>(4);
    private ClientListener listener;
    private int port;

    @BeforeEach
    void start() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        listener = new ClientListener(port, new ClientListener.Handler() {
            @Override
            public Hello localHello() {
                return Hello.builder(Hello.ROLE_SERVER).put(Hello.K_NAME, "test-server").put(Hello.K_VERSION, "3.0.0").build();
            }

            @Override
            public byte[] pskKey() {
                return KEY;
            }

            @Override
            public void onConnected(MuxConnection conn) {
                connected.add(conn);
            }

            @Override
            public void onOutdatedClient(String ip) {
                outdated.add(ip);
            }

            @Override
            public void onBindFailed(int p, Exception e) {
                throw new AssertionError("bind failed", e);
            }
        });
        listener.start();
        // wait until listening
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                break;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
    }

    @AfterEach
    void stop() {
        listener.stop();
    }

    @Test
    void v2ClientIsReportedOutdated() throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            // v2 handshake: int length + AES/ECB-encrypted id
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(16);
            out.write(new byte[16]);
            out.flush();
            assertEquals("127.0.0.1", outdated.poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void agentHandshakeAndMessageRouting() throws Exception {
        Hello agentHello = Hello.builder(Hello.ROLE_AGENT).put(Hello.K_NAME, "Desk-7").put(Hello.K_SCREEN, "1280x720").build();
        MuxConnection agent = MuxConnection.connect("127.0.0.1", port, 3000, agentHello, KEY);
        BlockingQueue<String> agentControl = new ArrayBlockingQueue<>(8);
        agent.start((c, type, payload) -> {
            if (type == MessageType.CONTROL) agentControl.add(new String(payload, StandardCharsets.UTF_8));
        });
        MuxConnection serverSide = connected.poll(10, TimeUnit.SECONDS);
        assertNotNull(serverSide);
        assertEquals("test-server", agent.remoteHello().name());

        AtomicReference<byte[]> thumb = new AtomicReference<>();
        AtomicReference<Map<String, String>> status = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(2);
        CountDownLatch closed = new CountDownLatch(1);
        ClientSession session = new ClientSession(serverSide, new SessionEvents() {
            @Override
            public void onThumbnail(ClientSession s, byte[] jpeg) {
                thumb.set(jpeg);
                got.countDown();
            }

            @Override
            public void onStatus(ClientSession s, Map<String, String> m) {
                status.set(m);
                got.countDown();
            }

            @Override
            public void onClientClosed(ClientSession s, String reason) {
                closed.countDown();
            }

            @Override
            public void onPeerClosed(PeerSession s, String reason) {
            }
        }, Executors.newSingleThreadExecutor());
        assertEquals("Desk-7", session.helloName());
        assertEquals("127.0.0.1", session.clientId());
        assertEquals(1280, session.helloScreen().width);
        serverSide.start(session);
        session.sendControl("SET_INTERVAL:60");
        assertEquals("SET_INTERVAL:60", agentControl.poll(5, TimeUnit.SECONDS));

        agent.send(MessageType.THUMBNAIL, new byte[] {1, 2, 3});
        agent.send(MessageType.STATUS, StatusMessage.encode(VpnGuardStatus.of(VpnGuardStatus.State.OFF).toMap()));
        assertTrue(got.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[] {1, 2, 3}, thumb.get());
        assertEquals(VpnGuardStatus.State.OFF, VpnGuardStatus.fromMap(status.get()).state);

        agent.close("bye");
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }
}
