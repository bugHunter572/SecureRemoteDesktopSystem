package com.srd.client.audio;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manual probe against the real devices (it only captures, unless --tone is given). Run with:
 * <pre>java -cp ... com.srd.client.audio.AudioProbe [seconds] [--tone]</pre>
 * {@code --tone} plays a nearly inaudible (-58 dBFS) 1 kHz tone on the default output while
 * measuring, to check that loopback audio really arrives (expect RMS of about 30).
 * For each second it prints packets received (expect 50 ±1), the RMS/peak level and the
 * sources. Then it unsubscribes, checks that the engine stopped, and subscribes again to test
 * a restart. Exit code 0 means all checks passed.
 */
public final class AudioProbe {
    private AudioProbe() {
    }

    public static void main(String[] args) throws Exception {
        int seconds = 5;
        boolean tone = false;
        for (String a : args) {
            if (a.equals("--tone")) tone = true;
            else seconds = Integer.parseInt(a);
        }
        AudioEngine engine = AudioEngine.get();
        boolean ok = true;
        if (tone) startTone(seconds + 3);

        ok &= run(engine, "run 1", seconds);

        long t0 = System.nanoTime();
        boolean stopped = !engine.isRunning();
        System.out.println("after unsubscribe: isRunning=" + engine.isRunning() + " (unsubscribe took "
                + (System.nanoTime() - t0) / 1000 + " us) -> " + (stopped ? "OK" : "FAIL"));
        ok &= stopped;
        Thread.sleep(500);
        System.out.println("describeSources while stopped: " + engine.describeSources());

        ok &= run(engine, "run 2 (restart)", Math.max(2, seconds / 2));
        ok &= !engine.isRunning();

        Thread.sleep(1500);   // let the sources' threads finish closing devices
        System.out.println(ok ? "PROBE OK" : "PROBE FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static void startTone(int seconds) {
        Thread t = new Thread(() -> {
            try {
                javax.sound.sampled.SourceDataLine l = javax.sound.sampled.AudioSystem.getSourceDataLine(AudioPacket.FORMAT);
                l.open(AudioPacket.FORMAT, AudioPacket.FRAME_BYTES * 4);
                l.start();
                byte[] b = new byte[AudioPacket.FRAME_BYTES];
                long n = 0;
                for (int k = 0; k < seconds * 50; k++) {
                    for (int i = 0; i < AudioPacket.SAMPLES_PER_FRAME; i++, n++) {
                        int v = (int) Math.round(40 * Math.sin(2 * Math.PI * 1000 * n / 48000.0));
                        Pcm.putLE(b, 4 * i, v);
                        Pcm.putLE(b, 4 * i + 2, v);
                    }
                    l.write(b, 0, b.length);
                }
                l.drain();
                l.close();
            } catch (Exception e) {
                System.out.println("tone failed: " + e);
            }
        }, "probe-tone");
        t.setDaemon(true);
        t.start();
        System.out.println("playing -58 dBFS 1 kHz test tone for " + seconds + " s");
    }

    private static boolean run(AudioEngine engine, String label, int seconds) throws InterruptedException {
        ConcurrentLinkedQueue<byte[]> q = new ConcurrentLinkedQueue<>();
        Object me = new Object();
        System.out.println("=== " + label + ": subscribing for " + seconds + " s");
        long t0 = System.nanoTime();
        engine.subscribe(me, q::offer);
        System.out.println("subscribe took " + (System.nanoTime() - t0) / 1000 + " us, isRunning=" + engine.isRunning());
        boolean ok = true;
        int lastSeq = -1;
        long gaps = 0;
        // Allow ~0.6 s for devices to open before measuring.
        Thread.sleep(600);
        q.clear();
        long start = System.nanoTime();
        for (int s = 1; s <= seconds; s++) {
            long deadline = start + s * 1_000_000_000L;
            long w;
            while ((w = deadline - System.nanoTime()) > 0) Thread.sleep(Math.max(1, w / 1_000_000));
            int packets = 0;
            double sumSq = 0;
            long samples = 0;
            int peak = 0;
            byte[] p;
            while ((p = q.poll()) != null) {
                AudioPacket pk = AudioPacket.decode(p);
                if (pk.sampleRate != 48000 || pk.channels != 2 || pk.pcm.length != AudioPacket.FRAME_BYTES) {
                    System.out.println("  BAD PACKET rate=" + pk.sampleRate + " ch=" + pk.channels + " len=" + pk.pcm.length);
                    ok = false;
                }
                if (lastSeq >= 0 && pk.seq != lastSeq + 1) gaps++;
                lastSeq = pk.seq;
                packets++;
                for (int i = 0; i + 1 < pk.pcm.length; i += 2) {
                    int v = Pcm.sampleLE(pk.pcm, i);
                    sumSq += (double) v * v;
                    peak = Math.max(peak, Math.abs(v));
                    samples++;
                }
            }
            double rms = samples > 0 ? Math.sqrt(sumSq / samples) : 0;
            double dbfs = rms > 0 ? 20 * Math.log10(rms / 32768.0) : Double.NEGATIVE_INFINITY;
            boolean rateOk = packets >= 49 && packets <= 51;
            if (!rateOk) ok = false;
            System.out.printf("  t=%ds packets=%d %s rms=%.1f (%.1f dBFS) peak=%d | %s%n", s, packets,
                    rateOk ? "OK" : "OUT-OF-RANGE", rms, dbfs, peak, engine.describeSources());
        }
        System.out.println("  seq gaps: " + gaps);
        if (gaps > 0) ok = false;
        engine.unsubscribe(me);
        return ok;
    }
}
