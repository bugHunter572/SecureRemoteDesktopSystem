package com.srd.common.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identity exchanged right after key agreement. Properties are free-form so new fields don't
 * need a protocol bump. Well-known keys are the {@code K_*} constants.
 */
public final class Hello {
    /** A monitored client agent connecting to the server console (the agent is the host). */
    public static final String ROLE_AGENT = "agent";
    /** The server console (acceptor of {@link #ROLE_AGENT}, or initiator to a peer port). */
    public static final String ROLE_SERVER = "server";
    /** A peer viewer window connecting to a broadcasting client. */
    public static final String ROLE_VIEWER = "viewer";
    /** A broadcasting client accepting viewers on its peer port. */
    public static final String ROLE_HOST = "host";

    public static final String K_ROLE = "role";
    public static final String K_ID = "id";
    public static final String K_NAME = "name";
    public static final String K_OS = "os";
    public static final String K_VERSION = "version";
    public static final String K_USER = "user";
    /** Agent's thumbnail/legacy screen size "WxH". */
    public static final String K_SCREEN = "screen";

    private final Map<String, String> props;

    public Hello(Map<String, String> props) {
        this.props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    public static Builder builder(String role) {
        return new Builder().put(K_ROLE, role);
    }

    public String get(String key) {
        return props.get(key);
    }

    public String get(String key, String def) {
        String v = props.get(key);
        return v == null ? def : v;
    }

    public String role() {
        return get(K_ROLE, "");
    }

    public String name() {
        return get(K_NAME, get(K_ID, "?"));
    }

    public Map<String, String> asMap() {
        return props;
    }

    public byte[] encode() {
        WireWriter w = new WireWriter();
        w.u16(props.size());
        for (Map.Entry<String, String> e : props.entrySet()) {
            w.str(e.getKey()).str(e.getValue());
        }
        return w.toByteArray();
    }

    public static Hello decode(byte[] data) {
        WireReader r = new WireReader(data);
        int n = r.u16();
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(r.str(), r.str());
        return new Hello(m);
    }

    @Override
    public String toString() {
        return "Hello" + props;
    }

    public static final class Builder {
        private final Map<String, String> m = new LinkedHashMap<>();

        public Builder put(String k, String v) {
            if (v != null) m.put(k, v);
            return this;
        }

        public Hello build() {
            return new Hello(m);
        }
    }
}
