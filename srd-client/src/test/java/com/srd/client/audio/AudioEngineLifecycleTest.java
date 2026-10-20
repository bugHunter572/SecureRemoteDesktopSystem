package com.srd.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Engine lifecycle and fan-out with a fake (device-free) source. */
class AudioEngineLifecycleTest {

    /** Pushes a constant level in 10 ms chunks on its own thread. */
    static final class FakeSource implements AudioSource {
        static final AtomicInteger started = new AtomicInteger();
        static final AtomicInteger stopped = new AtomicInteger();
        private volatile boolean running;
        private Thread t;

        @Override
        public void start(PcmFifo out) {
            started.incrementAndGet();
            running = true;
            t = new Thread(() -> {
                short[] chunk = new short[480 * 2];
                java.util.Arrays.fill(chunk, (short) 1000);
                long next = System.nanoTime();
                while (running) {
                    out.write(chunk, 0, 480);
                    next += 10_000_000L;
                    long w = next - System.nanoTime();
                    if (w > 0) java.util.concurrent.locks.LockSupport.parkNanos(w);
                }
            }, "fake-audio");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void stop() {
            running = false;
            stopped.incrementAndGet();
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    private static AudioEngine newEngine() {
        AudioEngine e = new AudioEngine();
        e.setSourceFactory(() -> Collections.singletonList(new FakeSource()));
        return e;
    }

    private static void waitUntil(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    @Test
    void deliversFiftyPacketsPerSecondOfMixedAudio() throws Exception {
        AudioEngine e = newEngine();
        List<byte[]> got = Collections.synchronizedList(new ArrayList<>());
        Object me = new Object();
        e.subscribe(me, got::add);
        assertTrue(e.isRunning());
        Thread.sleep(300);                     // warm-up
        got.clear();
        Thread.sleep(1000);
        int n = got.size();
        e.unsubscribe(me);
        assertTrue(n >= 45 && n <= 55, "packets in 1 s: " + n);
        AudioPacket p = AudioPacket.decode(got.get(got.size() - 1));
        assertEquals(48000, p.sampleRate);
        assertEquals(2, p.channels);
        assertEquals(AudioPacket.FRAME_BYTES, p.pcm.length);
        assertEquals(1000, Pcm.sampleLE(p.pcm, 0));
        // seq increments by one
        AudioPacket a = AudioPacket.decode(got.get(1));
        AudioPacket b = AudioPacket.decode(got.get(2));
        assertEquals(a.seq + 1, b.seq);
    }

    @Test
    void referenceCountedStartStop() throws Exception {
        AudioEngine e = newEngine();
        Object a = new Object(), b = new Object();
        AtomicInteger countA = new AtomicInteger(), countB = new AtomicInteger();
        e.subscribe(a, x -> countA.incrementAndGet());
        e.subscribe(b, x -> countB.incrementAndGet());
        Thread.sleep(200);
        e.unsubscribe(a);                      // b still subscribed: must keep running
        assertTrue(e.isRunning());
        int before = countB.get();
        Thread.sleep(200);
        assertTrue(countB.get() > before + 5, "b keeps receiving after a's unsubscribe");
        int aAfter = countA.get();
        Thread.sleep(100);
        assertEquals(aAfter, countA.get(), "a receives nothing after unsubscribing");
        e.unsubscribe(b);
        assertFalse(e.isRunning());
        int bAfter = countB.get();
        Thread.sleep(150);
        assertTrue(countB.get() <= bAfter + 1);
        e.unsubscribe(b);                      // idempotent
        e.unsubscribe(new Object());           // unknown: ignored
        assertFalse(e.isRunning());
    }

    @Test
    void stopImmediatelyFollowedByStartEndsRunning() throws Exception {
        AudioEngine e = newEngine();
        Object a = new Object();
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            e.subscribe(a, x -> count.incrementAndGet());
            e.unsubscribe(a);
        }
        long t0 = System.nanoTime();
        e.subscribe(a, x -> count.incrementAndGet());
        long subscribeMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(subscribeMs < 100, "subscribe must not block: " + subscribeMs + " ms");
        assertTrue(e.isRunning());
        int c0 = count.get();
        waitUntil(() -> count.get() > c0 + 10, 3000);
        assertTrue(count.get() > c0 + 10, "audio flows after rapid stop/start");
        t0 = System.nanoTime();
        e.unsubscribe(a);
        long unsubMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(unsubMs < 100, "unsubscribe must not block: " + unsubMs + " ms");
        waitUntil(() -> FakeSource.started.get() == FakeSource.stopped.get(), 3000);
        assertEquals(FakeSource.started.get(), FakeSource.stopped.get(), "every started source was stopped");
    }

    @Test
    void resubscribeReplacesSinkAndThrowingSinkIsIsolated() throws Exception {
        AudioEngine e = newEngine();
        Object a = new Object(), bad = new Object();
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        e.subscribe(bad, x -> {
            throw new RuntimeException("boom");
        });
        e.subscribe(a, x -> first.incrementAndGet());
        e.subscribe(a, x -> second.incrementAndGet());
        try {
            assertEquals(2, e.subscriberCount());
            // Wait for flow instead of a fixed sleep: the first session in a JVM also loads native
            // timer support (JNA), which can take a few hundred ms on a busy machine.
            waitUntil(() -> second.get() > 5, 3000);
            int f = first.get();
            assertTrue(second.get() > 5, "replacement sink receives despite a throwing sink");
            Thread.sleep(100);
            assertEquals(f, first.get(), "old sink no longer called");
        } finally {
            e.unsubscribe(a);   // always release, so a failure can't leave a session running
            e.unsubscribe(bad); // and skew the shared source counters of later tests
        }
        assertFalse(e.isRunning());
    }

    @Test
    void restartSourcesKeepsSubscribersFed() throws Exception {
        AudioEngine e = newEngine();
        AtomicReference<String> desc = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        Object a = new Object();
        e.subscribe(a, x -> count.incrementAndGet());
        Thread.sleep(150);
        e.restartSources();
        assertTrue(e.isRunning());
        int c0 = count.get();
        waitUntil(() -> count.get() > c0 + 5, 2000);
        assertTrue(count.get() > c0 + 5);
        desc.set(e.describeSources());
        assertTrue(desc.get().contains("fake"), desc.get());
        e.unsubscribe(a);
        assertEquals("audio stopped", e.describeSources());
    }
}
