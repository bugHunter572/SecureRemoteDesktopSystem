package com.srd.common.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JitterBufferTest {
    private static byte[] frame(int v) {
        byte[] f = new byte[16];
        java.util.Arrays.fill(f, (byte) v);
        return f;
    }

    @Test
    void prefillsBeforePlaying() throws Exception {
        JitterBuffer jb = new JitterBuffer(16);
        jb.offer(frame(1));
        byte[] first = jb.poll(0);
        assertEquals(0, first[0]); // still prefilling: silence
        jb.offer(frame(2));
        jb.offer(frame(3));
        assertEquals(1, jb.poll(0)[0]);
        assertEquals(2, jb.poll(0)[0]);
        assertEquals(3, jb.poll(0)[0]);
    }

    @Test
    void underrunConcealsOnceThenSilenceAndGrowsTarget() throws Exception {
        JitterBuffer jb = new JitterBuffer(16);
        int target = jb.targetFrames();
        for (int i = 0; i < target; i++) jb.offer(frame(100));
        for (int i = 0; i < target; i++) assertEquals(100, jb.poll(0)[0]);
        byte[] conceal = jb.poll(0);
        assertTrue(conceal[0] != 0 || conceal[1] != 0 || conceal[15] == 0, "faded copy of the last frame");
        byte[] silence = jb.poll(0);
        assertEquals(0, silence[0]);
        assertEquals(1, jb.underruns(), "a concealed then silent frame is one underrun episode start");
        assertEquals(target + 1, jb.targetFrames());
    }

    @Test
    void overflowDropsOldestToBoundLatency() throws Exception {
        JitterBuffer jb = new JitterBuffer(16);
        for (int i = 0; i < 50; i++) jb.offer(frame(i));
        assertTrue(jb.bufferedFrames() <= jb.targetFrames() + 3);
        assertTrue(jb.dropped() > 0);
        byte[] f = jb.poll(0);
        assertTrue(f[0] > 40, "oldest frames were dropped, got " + f[0]);
    }

    @Test
    void wrongSizedFramesIgnored() throws Exception {
        JitterBuffer jb = new JitterBuffer(16);
        jb.offer(new byte[7]);
        assertEquals(0, jb.bufferedFrames());
        byte[] s = jb.poll(0);
        assertSame(s, jb.poll(0)); // the shared silence frame
    }

    @Test
    void resamplesToStereo48k() {
        byte[] mono16k = new byte[320 * 2]; // 20 ms at 16 kHz mono
        for (int i = 0; i < 320; i++) Pcm.putLE(mono16k, i * 2, 1000);
        byte[] out = Pcm.toStereo48k(mono16k, 16000, 1);
        assertEquals(AudioPacket.FRAME_BYTES, out.length);
        assertEquals(1000, Pcm.sampleLE(out, 0));
        assertEquals(1000, Pcm.sampleLE(out, 2));
    }
}
