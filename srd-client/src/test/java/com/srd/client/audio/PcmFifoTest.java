package com.srd.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PcmFifoTest {

    /** Frames whose L sample is {@code start+i} and R sample is {@code -(start+i)}. */
    static short[] ramp(int start, int frames) {
        short[] s = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            s[2 * i] = (short) (start + i);
            s[2 * i + 1] = (short) -(start + i);
        }
        return s;
    }

    @Test
    void writeThenReadInOrder() {
        PcmFifo f = new PcmFifo(100);
        f.write(ramp(0, 10), 0, 10);
        assertEquals(10, f.available());
        short[] out = new short[20];
        assertEquals(10, f.read(10, out));
        assertArrayEquals(ramp(0, 10), out);
        assertEquals(0, f.available());
    }

    @Test
    void wrapAroundPreservesOrder() {
        PcmFifo f = new PcmFifo(10);
        short[] out = new short[40];
        f.write(ramp(0, 7), 0, 7);
        assertEquals(5, f.read(5, out));
        f.write(ramp(7, 6), 0, 6);           // wraps past the end of the ring
        assertEquals(8, f.available());
        assertEquals(8, f.read(8, out));
        for (int i = 0; i < 8; i++) {
            assertEquals(5 + i, out[2 * i]);
            assertEquals(-(5 + i), out[2 * i + 1]);
        }
    }

    @Test
    void partialReadReturnsWhatIsAvailable() {
        PcmFifo f = new PcmFifo(100);
        f.write(ramp(0, 3), 0, 3);
        short[] out = new short[20];
        assertEquals(3, f.read(10, out));
        assertEquals(0, f.read(10, out));
        assertEquals(0, f.available());
    }

    @Test
    void readIsLimitedByOutputArray() {
        PcmFifo f = new PcmFifo(100);
        f.write(ramp(0, 10), 0, 10);
        short[] out = new short[6];
        assertEquals(3, f.read(10, out));
        assertEquals(7, f.available());
    }

    @Test
    void overflowDropsOldest() {
        PcmFifo f = new PcmFifo(8);
        f.write(ramp(0, 6), 0, 6);
        f.write(ramp(6, 6), 0, 6);           // 4 oldest frames are dropped
        assertEquals(8, f.available());
        assertEquals(4, f.overflowFrames());
        short[] out = new short[16];
        f.read(8, out);
        assertEquals(4, out[0]);
        assertEquals(11, out[14]);
    }

    @Test
    void writeLargerThanCapacityKeepsNewest() {
        PcmFifo f = new PcmFifo(4);
        f.write(ramp(0, 10), 0, 10);
        short[] out = new short[8];
        assertEquals(4, f.read(4, out));
        assertEquals(6, out[0]);
        assertEquals(9, out[6]);
    }

    @Test
    void trimToDropsOldestEnd() {
        PcmFifo f = new PcmFifo(10);
        short[] out = new short[20];
        f.write(ramp(0, 6), 0, 6);
        f.read(4, out);                      // move the read position so trimming wraps
        f.write(ramp(6, 8), 0, 8);           // 10 frames: 4..13
        assertEquals(7, f.trimTo(3));
        assertEquals(3, f.available());
        assertEquals(3, f.read(10, out));
        assertEquals(11, out[0]);
        assertEquals(13, out[4]);
        assertEquals(0, f.trimTo(5));        // nothing to trim
    }

    @Test
    void clearEmpties() {
        PcmFifo f = new PcmFifo(10);
        f.write(ramp(0, 5), 0, 5);
        f.clear();
        assertEquals(0, f.available());
        f.write(ramp(100, 2), 0, 2);
        short[] out = new short[4];
        assertEquals(2, f.read(2, out));
        assertEquals(100, out[0]);
    }

    @Test
    void writeLEHandlesFramesSplitAcrossCalls() {
        PcmFifo f = new PcmFifo(100);
        byte[] b = new byte[4 * 5];
        for (int i = 0; i < 5; i++) {
            com.srd.common.audio.Pcm.putLE(b, 4 * i, 1000 + i);
            com.srd.common.audio.Pcm.putLE(b, 4 * i + 2, -1000 - i);
        }
        f.writeLE(b, 0, 3);       // partial first frame
        assertEquals(0, f.available());
        f.writeLE(b, 3, 2);       // completes frame 0, plus 1 byte of frame 1
        assertEquals(1, f.available());
        f.writeLE(b, 5, 15);      // rest
        assertEquals(5, f.available());
        short[] out = new short[10];
        f.read(5, out);
        for (int i = 0; i < 5; i++) {
            assertEquals(1000 + i, out[2 * i]);
            assertEquals(-1000 - i, out[2 * i + 1]);
        }
    }
}
