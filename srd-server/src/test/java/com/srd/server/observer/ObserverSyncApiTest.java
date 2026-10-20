package com.srd.server.observer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.server.sync.SyncPayload;
import com.srd.server.sync.SyncSender;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ObserverSyncApiTest {
    @Test
    void senderDeliversPayloadAndRejectsWrongKey() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        AtomicReference<SyncPayload> got = new AtomicReference<>();
        ObserverSyncApi api = new ObserverSyncApi(port, "secret", got::set);
        try {
            Map<String, String> clients = new LinkedHashMap<>();
            clients.put("10.0.0.5", "Front desk");
            SyncPayload payload = new SyncPayload(clients, "@acs 10.0.0.1 ~ 9");

            assertEquals(Collections.singletonList("127.0.0.1: wrong key (PSK differs)"),
                    SyncSender.send(Collections.singletonList("127.0.0.1"), port, "nope", payload));
            assertEquals(null, got.get());

            assertEquals(Collections.singletonList("127.0.0.1: ok"),
                    SyncSender.send(Collections.singletonList("127.0.0.1"), port, "secret", payload));
            assertEquals(clients, got.get().clients);
            assertEquals("@acs 10.0.0.1 ~ 9", got.get().tags);

            assertTrue(SyncSender.send(Collections.singletonList("127.0.0.1"), port + 1, "secret", payload).get(0).contains("not reachable"));
        } finally {
            api.stop();
        }
    }
}
