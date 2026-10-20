package com.srd.common.util;

import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Version and build date of this build. Both come from {@code build-info.properties}, which Maven
 * fills in from the pom version and the build time on every build, so they cannot go stale. Run
 * from an IDE (unfiltered file) they read "dev".
 */
public final class BuildInfo {
    /** Project version, e.g. {@code 3.0.2}. */
    public static final String VERSION;
    /** When this build was made, in the viewer's local time: {@code 2026-10-15 17:50}. */
    public static final String BUILT;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
        }
        VERSION = clean(p.getProperty("version"), "dev");
        BUILT = localTime(clean(p.getProperty("built"), ""));
    }

    private BuildInfo() {
    }

    private static String clean(String v, String def) {
        return v == null || v.trim().isEmpty() || v.contains("${") ? def : v.trim();
    }

    /** Converts the UTC stamp {@code 2026-10-15T08:50:12Z} (seconds optional) to local time; unknown stays "unknown". */
    static String localTime(String utcStamp) {
        if (utcStamp.isEmpty()) return "unknown";
        try {
            ZonedDateTime utc = ZonedDateTime.parse(utcStamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]'Z'").withZone(ZoneId.of("UTC")));
            return utc.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return utcStamp;
        }
    }
}
