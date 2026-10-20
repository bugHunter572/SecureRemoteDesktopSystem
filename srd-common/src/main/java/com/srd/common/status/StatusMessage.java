package com.srd.common.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** STATUS payload codec: UTF-8 "key=value" lines (newlines in values are escaped). */
public final class StatusMessage {
    private StatusMessage() {
    }

    public static byte[] encode(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null) continue;
            b.append(e.getKey()).append('=')
                    .append(e.getValue().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", ""))
                    .append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, String> decode(byte[] payload) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : new String(payload, StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String v = line.substring(eq + 1);
            StringBuilder out = new StringBuilder(v.length());
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) {
                    char n = v.charAt(++i);
                    out.append(n == 'n' ? '\n' : n);
                } else {
                    out.append(c);
                }
            }
            m.put(line.substring(0, eq), out.toString());
        }
        return m;
    }
}
