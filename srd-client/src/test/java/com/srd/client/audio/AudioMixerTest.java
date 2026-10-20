package com.srd.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srd.common.audio.AudioPacket;
import com.srd.common.audio.Pcm;
import org.junit.jupiter.api.Test;

class AudioMixerTest {
    static final int N = AudioEngine.TICK_FRAMES;

    static PcmFifo constant(int frames, int l, int r) {
        PcmFifo f = new PcmFifo(48000);
        short[] s = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            s[2 * i] = (short) l;
            s[2 * i + 1] = (short) r;
        }
        f.write(s, 0, frames);
        return f;
    }

    static int left(byte[] pcm, int frame) {
        return Pcm.sampleLE(pcm, frame * 4);
    }

    static int right(byte[] pcm, int frame) {
        return Pcm.sampleLE(pcm, frame * 4 + 2);
    }

    @Test
    void outputIsExactlyOneFrame() {
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[0]);
        assertEquals(AudioPacket.FRAME_BYTES, pcm.length);
        for (byte b : pcm) assertEquals(0, b);
    }

    @Test
    void singleSourcePassesThroughUnchanged() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(N, 1234, -567), 1f, 0);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
        for (int i = 0; i < N; i++) {
            assertEquals(1234, left(pcm, i));
            assertEquals(-567, right(pcm, i));
        }
    }

    @Test
    void twoSourcesAreSummedNotAveraged() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(N, 1000, 2000), 1f, 0);
        AudioEngine.MixInput b = new AudioEngine.MixInput(constant(N, 3000, -500), 1f, 0);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a, b});
        assertEquals(4000, left(pcm, 0));
        assertEquals(1500, right(pcm, 0));
        assertEquals(4000, left(pcm, N - 1));
        // Adding a silent source must not change the level (the old mixer averaged: volume pumping).
        AudioEngine.MixInput c = new AudioEngine.MixInput(constant(N, 1000, 2000), 1f, 0);
        AudioEngine.MixInput silent = new AudioEngine.MixInput(constant(N, 0, 0), 1f, 0);
        byte[] pcm2 = AudioEngine.mixTick(new AudioEngine.MixInput[]{c, silent});
        assertEquals(1000, left(pcm2, 10));
        assertEquals(2000, right(pcm2, 10));
    }

    @Test
    void perSourceGainApplies() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(N, 1000, 1000), 0.5f, 0);
        AudioEngine.MixInput b = new AudioEngine.MixInput(constant(N, 1000, 1000), 2f, 0);
        AudioEngine.MixInput muted = new AudioEngine.MixInput(constant(N, 9000, 9000), 0f, 0);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a, b, muted});
        assertEquals(2500, left(pcm, 5));
        assertEquals(0, muted.fifo.available(), "muted source is still consumed (keeps its clock)");
    }

    @Test
    void loudSumIsSoftClippedNotWrapped() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(N, 30000, -30000), 1f, 0);
        AudioEngine.MixInput b = new AudioEngine.MixInput(constant(N, 30000, -30000), 1f, 0);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a, b});
        int l = left(pcm, 0), r = right(pcm, 0);
        assertTrue(l > 30000 && l <= 32767, "positive clip " + l);
        assertTrue(r < -30000 && r >= -32768, "negative clip " + r);
        assertEquals(Pcm.softClip(60000f), l);
        // Below the knee the limiter is transparent.
        AudioEngine.MixInput q = new AudioEngine.MixInput(constant(N, 10000, 10000), 1f, 0);
        AudioEngine.MixInput w = new AudioEngine.MixInput(constant(N, 10000, 10000), 1f, 0);
        assertEquals(20000, left(AudioEngine.mixTick(new AudioEngine.MixInput[]{q, w}), 0));
    }

    @Test
    void shortSourceIsPaddedWithSilence() {
        AudioEngine.MixInput full = new AudioEngine.MixInput(constant(N, 100, 100), 1f, 0);
        AudioEngine.MixInput shorty = new AudioEngine.MixInput(constant(500, 1000, 1000), 1f, 0);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{full, shorty});
        assertEquals(1100, left(pcm, 0));
        assertEquals(1100, left(pcm, 499));
        assertEquals(100, left(pcm, 500));
        assertEquals(100, left(pcm, N - 1));
        assertEquals(0, shorty.fifo.available());
        assertEquals(1, shorty.underruns);
        assertFalse(shorty.primed, "an underrun re-enters priming");
    }

    @Test
    void primingWaitsForEnoughDataThenReads() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(1000, 700, 700), 1f, 1920);
        byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
        assertEquals(0, left(pcm, 0));
        assertEquals(1000, a.fifo.available(), "nothing consumed while priming");
        short[] more = new short[1000 * 2];
        java.util.Arrays.fill(more, (short) 700);
        a.fifo.write(more, 0, 1000);                    // 2000 >= 1920: primed
        pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
        assertEquals(700, left(pcm, 0));
        assertEquals(700, left(pcm, N - 1));
        assertEquals(2000 - N, a.fifo.available());
        assertTrue(a.primed);
    }

    @Test
    void driftTrimmingDropsOldestBackToPrimeLevel() {
        // 200 ms queued (older half = 1, newer half = 2) with a 40 ms prime: high water is 120 ms.
        PcmFifo f = new PcmFifo(48000);
        short[] s = new short[9600 * 2];
        for (int i = 0; i < 9600; i++) {
            short v = (short) (i < 4800 ? 1 : 2);
            s[2 * i] = v;
            s[2 * i + 1] = v;
        }
        f.write(s, 0, 9600);
        AudioEngine.MixInput a = new AudioEngine.MixInput(f, 1f, 1920);
        assertEquals(5760, a.highWaterFrames);
        AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
        assertEquals(1920, f.available(), "trimmed back to ~40 ms");
        assertEquals(9600 - N - 1920, a.trimmedFrames);
        short[] out = new short[1920 * 2];
        f.read(1920, out);
        assertEquals(2, out[0], "the newest audio is kept");
    }

    @Test
    void noTrimBelowHighWater() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(constant(5000, 1, 1), 1f, 1920);
        AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
        assertEquals(5000 - N, a.fifo.available());
        assertEquals(0, a.trimmedFrames);
    }

    @Test
    void steadyProducerAtMixerRateNeverUnderruns() {
        AudioEngine.MixInput a = new AudioEngine.MixInput(new PcmFifo(48000), 1f, 1920);
        short[] chunk = new short[480 * 2];
        java.util.Arrays.fill(chunk, (short) 5);
        int silentTicks = 0;
        for (int tick = 0; tick < 500; tick++) {
            a.fifo.write(chunk, 0, 480);                // producer: 2 x 10 ms per 20 ms tick
            a.fifo.write(chunk, 0, 480);
            byte[] pcm = AudioEngine.mixTick(new AudioEngine.MixInput[]{a});
            if (left(pcm, 0) == 0) silentTicks++;
        }
        assertEquals(0, a.underruns);
        assertEquals(1, silentTicks, "only the first priming tick is silent");
    }
}
