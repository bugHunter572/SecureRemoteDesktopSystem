package com.srd.common.update;

/** Dotted numeric versions ("3.0.6"); anything after the numbers ("-SNAPSHOT") is ignored. */
public final class Versions {
    private Versions() {
    }

    /** Parsed numeric parts, or null when the text is not a version (for example "dev"). */
    public static int[] parse(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        s = s.substring(0, end);
        if (s.isEmpty() || s.startsWith(".") || s.endsWith(".") || s.contains("..")) return null;
        String[] parts = s.split("[.]");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Negative, zero or positive like {@link Comparable}; both must parse. */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) throw new IllegalArgumentException("not a version: " + (x == null ? a : b));
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
}
