package com.srd.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class BuildInfoTest {
    @Test
    void mavenFillsInVersionAndBuildDate() {
        assertFalse(BuildInfo.VERSION.contains("${"));
        assertTrue(BuildInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), "version comes from the pom: " + BuildInfo.VERSION);
        assertTrue(BuildInfo.BUILT.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "built: " + BuildInfo.BUILT);
    }

    @Test
    void stampIsShownInLocalTime() {
        String expected = ZonedDateTime.of(2026, 10, 15, 8, 50, 0, 0, ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertEquals(expected, BuildInfo.localTime("2026-10-15T08:50Z"));
        assertEquals("unknown", BuildInfo.localTime(""));
        assertEquals("garbage", BuildInfo.localTime("garbage"));
    }
}
