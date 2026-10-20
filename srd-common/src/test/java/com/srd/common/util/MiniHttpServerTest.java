package com.srd.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MiniHttpServerTest {
    @Test
    void routesMethodHeadersAndBody() throws Exception {
        MiniHttpServer s = new MiniHttpServer(0, 100, (method, path, headers, body) ->
                new MiniHttpServer.Reply("secret".equals(headers.get("x-srd-key")) ? 200 : 401,
                        method + " " + path + " " + new String(body, StandardCharsets.UTF_8)));
        try {
            assertEquals("POST /a hello", call(s.port(), "/a?x=1", "secret", "hello", 200));
            call(s.port(), "/a", "wrong", "hello", 401);
        } finally {
            s.stop();
        }
    }

    private static String call(int port, String path, String key, String body, int expect) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("X-SRD-Key", key);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(b);
        }
        assertEquals(expect, c.getResponseCode());
        if (expect != 200) return ""; // HttpURLConnection hides the body of a 401 in streaming mode
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
