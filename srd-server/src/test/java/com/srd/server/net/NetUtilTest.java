package com.srd.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.junit.jupiter.api.Test;

class NetUtilTest {
    @Test
    void privateAndLoopbackOnly() {
        assertTrue(NetUtil.isLocalIPAddress("10.1.2.3"));
        assertTrue(NetUtil.isLocalIPAddress("172.16.0.1"));
        assertTrue(NetUtil.isLocalIPAddress("172.31.255.1"));
        assertTrue(NetUtil.isLocalIPAddress("192.168.1.1"));
        assertTrue(NetUtil.isLocalIPAddress("169.254.1.1"));
        assertTrue(NetUtil.isLocalIPAddress("127.0.0.1"));
        assertFalse(NetUtil.isLocalIPAddress("172.32.0.1"));
        assertFalse(NetUtil.isLocalIPAddress("8.8.8.8"));
        assertFalse(NetUtil.isLocalIPAddress("::1"));
    }

    @Test
    void helloScreenParsing() {
        assertEquals(new Dimension(2560, 1440), ClientSession.parseScreen("2560x1440"));
        assertEquals(new Dimension(1920, 1080), ClientSession.parseScreen(null));
        assertEquals(new Dimension(1920, 1080), ClientSession.parseScreen("garbage"));
    }
}
