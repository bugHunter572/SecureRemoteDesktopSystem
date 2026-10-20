package com.srd.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScreenshotsTest {
    @Test
    void stemIsDateIpName() {
        String today = LocalDate.now().toString();
        assertEquals(today + "_192.168.1.5_OFFICE-PC", Screenshots.stem("192.168.1.5", "OFFICE-PC"));
        // When the name equals the IP (unknown name), don't duplicate it.
        assertEquals(today + "_10.0.0.2", Screenshots.stem("10.0.0.2", "10.0.0.2"));
    }

    @Test
    void sanitizeRemovesIllegalChars() {
        assertEquals("a-b-c", Screenshots.sanitize("a/b:c"));
        assertEquals("My-PC", Screenshots.sanitize("My PC"));
        assertEquals("client", Screenshots.sanitize("  "));
        String s = Screenshots.sanitize("bad\\/:*?\"<>|name");
        for (char c : "\\/:*?\"<>|".toCharArray()) assertFalse(s.indexOf(c) >= 0, "contains " + c);
    }

    @Test
    void downloadsDirExists() {
        assertTrue(Screenshots.downloadsDir().isDirectory(), "downloads dir resolves");
    }
}
