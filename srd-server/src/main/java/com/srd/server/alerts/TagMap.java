package com.srd.server.alerts;

import java.util.ArrayList;
import java.util.List;

/**
 * Which Mattermost tag owns which client IPs. One line per tag, e.g. {@code @acs 192.168.110.100 ~ 104}
 * (the end may be a last octet or a full address; {@code ~}, {@code -} and {@code –} all work, a colon
 * after the tag is fine, and a single address has no range). Lines that don't parse are skipped.
 */
public final class TagMap {
    private static final class Range {
        final String tag;
        final long from;
        final long to;

        Range(String tag, long from, long to) {
            this.tag = tag;
            this.from = from;
            this.to = to;
        }
    }

    private final List<Range> ranges = new ArrayList<>();

    public static TagMap parse(String text) {
        TagMap m = new TagMap();
        if (text == null) return m;
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] part = line.split("\\s*[~\u2013\u2014]\\s*|\\s+-\\s*|(?<=\\d)-(?=\\d)", 2);
            String head = part[0].trim();
            int sp = head.indexOf(' ');
            if (sp < 0) continue;
            String tag = head.substring(0, sp).trim();
            if (tag.endsWith(":")) tag = tag.substring(0, tag.length() - 1);
            long from = ip(head.substring(sp + 1).trim());
            if (tag.isEmpty() || from < 0) continue;
            long to = from;
            if (part.length > 1) {
                String end = part[1].trim();
                to = end.indexOf('.') >= 0 ? ip(end) : lastOctet(from, end);
                if (to < 0) continue;
            }
            m.ranges.add(new Range(tag, Math.min(from, to), Math.max(from, to)));
        }
        return m;
    }

    /** The tag for this client IP, or "" if no range covers it. */
    public String tagFor(String ip) {
        long v = ip(ip == null ? "" : ip.trim());
        if (v < 0) return "";
        for (Range r : ranges) if (v >= r.from && v <= r.to) return r.tag;
        return "";
    }

    private static long lastOctet(long base, String s) {
        try {
            int n = Integer.parseInt(s);
            return n < 0 || n > 255 ? -1 : (base & ~0xFFL) | n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long ip(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        try {
            for (String o : p) {
                int n = Integer.parseInt(o.trim());
                if (n < 0 || n > 255) return -1;
                v = (v << 8) | n;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return v;
    }
}
