package com.srd.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerConfigTest {
    @Test
    void defaultsMatchTheClients() {
        ServerConfig c = new ServerConfig();
        assertEquals("MySecretKey12345", c.getPsk());
        assertEquals("SERVER_CONFIG:AUTO_ADD=1;SCREEN_CONTROL=1;SCREENSHOT_SAVE=1;IMAGE_QUALITY=0.85;ALTERNATIVE_IPS=;CLIENT_NOTIFY=1;NOTIFY_DELAY=20",
                c.toServerConfigCommand());
        assertEquals("SET_INTERVAL:60", c.toIntervalCommand());
    }

    @Test
    void commandFormatIsUnchanged() {
        ServerConfig c = new ServerConfig();
        c.setAutoAddClients(false);
        c.setScreenshotSaveEnabled(false);
        c.setImageQuality(0.8f);
        c.setAlternativeIPs(Arrays.asList("10.0.0.1", "10.0.0.2"));
        c.setScreenshotInterval(15);
        assertEquals("SERVER_CONFIG:AUTO_ADD=0;SCREEN_CONTROL=1;SCREENSHOT_SAVE=0;IMAGE_QUALITY=0.8;ALTERNATIVE_IPS=10.0.0.1,10.0.0.2;CLIENT_NOTIFY=1;NOTIFY_DELAY=20",
                c.toServerConfigCommand());
        assertEquals("SET_INTERVAL:15", c.toIntervalCommand());
    }

    @Test
    void databaseRoundTripIncludesPsk() {
        ServerConfig c = new ServerConfig();
        c.setPsk("other-key");
        c.setScreenControlEnabled(false);
        Map<String, String> m = c.toDatabaseMap();
        assertEquals("other-key", m.get(ServerConfig.KEY_PSK));
        ServerConfig back = ServerConfig.fromDatabase(m);
        assertEquals("other-key", back.getPsk());
        assertFalse(back.isScreenControlEnabled());
        // a v2 table has no psk row -> default key
        Map<String, String> v2 = new HashMap<>(m);
        v2.remove(ServerConfig.KEY_PSK);
        assertEquals(ServerConfig.DEFAULT_PSK, ServerConfig.fromDatabase(v2).getPsk());
    }

    @Test
    void ipValidation() {
        assertTrue(ServerConfig.isValidIP("192.168.0.1"));
        assertFalse(ServerConfig.isValidIP("192.168.0"));
        assertFalse(ServerConfig.isValidIP("300.1.1.1"));
    }
}
