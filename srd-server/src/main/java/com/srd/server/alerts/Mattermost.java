package com.srd.server.alerts;

import com.srd.common.util.Log;
import com.srd.common.util.Threads;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Mattermost incoming-webhook client and message layout. Uses {@link HttpURLConnection} (already in
 * the trimmed runtime, no extra module), one short-lived background thread, short timeouts and a
 * couple of quick retries. A post that still fails is kept (bounded, at most an hour) and retried
 * by {@link #retryPending}, so a chat-server outage never touches the UI or the clients and
 * nothing is silently lost.
 */
public final class Mattermost {
    /** One table row of a post: the same five columns for problems and for "connected". */
    public static final class Row {
        public final String tag;
        public final String displayName;
        public final String ip;
        public final String status;
        public final String when;

        public Row(String tag, String displayName, String ip, String status, String when) {
            this.tag = tag;
            this.displayName = displayName;
            this.ip = ip;
            this.status = status;
            this.when = when;
        }
    }

    /** Called on the worker thread when delivery starts failing and when it works again. */
    public interface Health {
        void unreachable(String reason);

        void reachableAgain();
    }

    /** Mattermost refuses posts over 16383 characters; a row is about 120, so long lists are split. */
    static final int ROWS_PER_POST = 100;
    private static final int[] RETRY_DELAYS_SEC = {5, 20};
    private static final int MAX_PENDING = 20;
    private static final long PENDING_MAX_AGE_MS = 60 * 60 * 1000L;

    /** Core size 0: the thread exists only while a post is in flight and exits after 30 s idle. */
    private final ExecutorService worker = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Threads.factory("mattermost", Thread.NORM_PRIORITY));

    private static final class Pending {
        final String url;
        final String text;
        final long created = System.currentTimeMillis();

        Pending(String url, String text) {
            this.url = url;
            this.text = text;
        }
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean failing;
    private volatile Health health;

    public void setHealth(Health h) {
        health = h;
    }

    // ------------------------------------------------------------------ layout

    /** The post text(s): just the table, oldest first (the When column sorts as text). */
    public static List<String> formatTables(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((Row r) -> r.when).thenComparing(r -> r.ip));
        List<String> posts = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i += ROWS_PER_POST) {
            posts.add(table(sorted.subList(i, Math.min(sorted.size(), i + ROWS_PER_POST))));
        }
        return posts;
    }

    public static String table(List<Row> rows) {
        StringBuilder b = new StringBuilder("| Tag | Display Name | IP | Status | When |\n|:--|:--|:--|:--|:--|\n");
        for (Row r : rows) {
            b.append("| ").append(cell(r.tag)).append(" | ").append(cell(r.displayName)).append(" | ").append(cell(r.ip))
                    .append(" | ").append(cell(r.status)).append(" | ").append(cell(r.when)).append(" |\n");
        }
        return b.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** "1m 04s", "45s", "2h 03m". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + String.format("%02d", s % 60) + "s";
        return (m / 60) + "h " + String.format("%02d", m % 60) + "m";
    }

    // ------------------------------------------------------------------ transport

    /** Queues a post; a few quick retries, then it is kept and retried by {@link #retryPending}. */
    public void post(String webhookUrl, String markdown) {
        worker.execute(() -> {
            for (int attempt = 0; ; attempt++) {
                try {
                    postBlocking(webhookUrl, markdown);
                    Log.info("[MATTERMOST] notification sent");
                    delivered();
                    return;
                } catch (IOException e) {
                    if (attempt >= RETRY_DELAYS_SEC.length) {
                        Log.warn("[MATTERMOST] not delivered (" + e.getMessage() + "); kept for retry");
                        keep(new Pending(webhookUrl, markdown), e.getMessage());
                        return;
                    }
                    Log.warn("[MATTERMOST] send failed (" + e.getMessage() + "); retrying in " + RETRY_DELAYS_SEC[attempt] + " s");
                    Threads.sleepQuietly(RETRY_DELAYS_SEC[attempt] * 1000L);
                }
            }
        });
    }

    private synchronized void keep(Pending p, String reason) {
        while (pending.size() >= MAX_PENDING) pending.pollFirst();
        pending.addLast(p);
        if (!failing) {
            failing = true;
            Health h = health;
            if (h != null) h.unreachable(reason);
        }
    }

    private synchronized void delivered() {
        if (!failing) return;
        failing = false;
        Health h = health;
        if (h != null) h.reachableAgain();
    }

    /** Tries each kept post once, oldest first; stops at the first failure. Cheap no-op when nothing is kept. */
    public void retryPending() {
        synchronized (this) {
            if (pending.isEmpty()) return;
        }
        worker.execute(() -> {
            while (true) {
                Pending p;
                synchronized (this) {
                    p = pending.peekFirst();
                    if (p == null) return;
                    if (System.currentTimeMillis() - p.created > PENDING_MAX_AGE_MS) {
                        pending.pollFirst();
                        Log.warn("[MATTERMOST] dropping a notification older than 1 h");
                        continue;
                    }
                }
                try {
                    postBlocking(p.url, p.text);
                } catch (IOException e) {
                    Log.debug("[MATTERMOST] retry failed: " + e.getMessage());
                    return;
                }
                synchronized (this) {
                    pending.pollFirst();
                }
                Log.info("[MATTERMOST] kept notification delivered");
                delivered();
            }
        });
    }

    /** Posts and throws on any failure; used directly by the "send test" button (off the EDT). */
    public static void postBlocking(String webhookUrl, String markdown) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(webhookUrl).openConnection();
        if (c instanceof HttpsURLConnection) {
            SSLSocketFactory f = socketFactory();
            if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
        }
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = ("{\"text\":" + jsonString(markdown) + "}").getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[512];
                while (in.read(buf) > 0) {
                    // drain so the connection can be reused
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static volatile SSLSocketFactory sslFactory;

    /**
     * TLS for the webhook: Java's normal trust store plus the bundled root of the Mattermost
     * server's internal CA ("Caddy Local Authority"). Browsers and Windows trust that root because
     * it was installed there; Java never does, so every post failed with "PKIX path building
     * failed". Only this connection gets the extra root; host names are still verified.
     */
    private static SSLSocketFactory socketFactory() {
        SSLSocketFactory f = sslFactory;
        if (f != null) return f;
        try (InputStream in = Mattermost.class.getResourceAsStream("/mattermost-ca.pem")) {
            if (in == null) return null;
            X509Certificate root = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            extra.setCertificateEntry("mattermost-ca", root);
            X509TrustManager normal = trustManager(null);
            X509TrustManager internal = trustManager(extra);
            X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    normal.checkClientTrusted(chain, type);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    try {
                        normal.checkServerTrusted(chain, type);
                    } catch (CertificateException e) {
                        internal.checkServerTrusted(chain, type);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return normal.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {both}, null);
            sslFactory = ctx.getSocketFactory();
            return sslFactory;
        } catch (Exception e) {
            Log.warn("[MATTERMOST] could not load the bundled CA: " + e);
            return null;
        }
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException("no X509TrustManager");
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }
}
