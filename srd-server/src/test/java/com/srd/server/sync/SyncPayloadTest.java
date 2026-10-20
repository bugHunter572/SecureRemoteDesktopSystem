package com.srd.server.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncPayloadTest {
    @Test
    void roundTripKeepsNamesAndTags() throws Exception {
        Map<String, String> clients = new LinkedHashMap<>();
        clients.put("192.168.110.100", "Kim = Front=Desk");
        clients.put("192.168.110.101", "Ünïcode 한글");
        clients.put("192.168.110.102", "");
        SyncPayload back = SyncPayload.parse(new SyncPayload(clients, "@acs 192.168.110.100 ~ 104\n@cdr 192.168.110.105").toText());
        assertEquals(clients, back.clients);
        assertEquals("@acs 192.168.110.100 ~ 104\n@cdr 192.168.110.105", back.tags);
    }

    @Test
    void rejectsUnknownVersion() {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> SyncPayload.parse("version=9\n"));
    }
}
