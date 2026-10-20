package com.srd.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Resampling, channel conversion and WASAPI-format downmix, without any device. */
class ConversionTest {

    @Test
    void passthroughAt48k() {
        StereoResampler rs = new StereoResampler(48000);
        PcmFifo f = new PcmFifo(48000);
        float[] in = {100, -100, 200, -200, 300, -300};
        rs.process(in, 3, f);
        short[] out = new short[6];
        assertEquals(3, f.read(3, out));
        assertEquals(200, out[2]);
        assertEquals(-300, out[5]);
    }

    @Test
    void resample44100KeepsRateAcrossChunks() {
        StereoResampler rs = new StereoResampler(44100);
        PcmFifo f = new PcmFifo(96000);
        // 1 s of a slow ramp in 441-frame (10 ms) chunks with an awkward split.
        int total = 44100;
        int pos = 0;
        int[] sizes = {441, 300, 141, 1000, 7};
        int k = 0;
        while (pos < total) {
            int n = Math.min(sizes[k++ % sizes.length], total - pos);
            float[] in = new float[n * 2];
            for (int i = 0; i < n; i++) {
                in[2 * i] = (pos + i) * 0.5f;
                in[2 * i + 1] = -(pos + i) * 0.5f;
            }
            rs.process(in, n, f);
            pos += n;
        }
        int frames = f.available();
        assertTrue(Math.abs(frames - 48000) <= 2, "output frames " + frames);
        short[] out = new short[frames * 2];
        f.read(frames, out);
        // Continuous across chunk boundaries: a ramp stays a ramp (no clicks/discontinuities).
        double step = 0.5 * 44100.0 / 48000.0;
        for (int i = 1; i < frames; i++) {
            double d = out[2 * i] - out[2 * (i - 1)];
            assertTrue(Math.abs(d - step) <= 1.01, "jump at " + i + ": " + d);
            assertEquals(-out[2 * i], out[2 * i + 1], 1);
        }
    }

    @Test
    void resample16kMonoStyleUpsample() {
        StereoResampler rs = new StereoResampler(16000);
        PcmFifo f = new PcmFifo(48000);
        float[] in = new float[160 * 2];
        for (int c = 0; c < 100; c++) rs.process(in, 160, f);     // 1 s
        assertTrue(Math.abs(f.available() - 48000) <= 3, "frames " + f.available());
    }

    @Test
    void javaSoundMonoAndBigEndianConversion() {
        byte[] le = new byte[4];
        com.srd.common.audio.Pcm.putLE(le, 0, 1234);
        com.srd.common.audio.Pcm.putLE(le, 2, -42);
        float[] st = new float[4];
        JavaSoundMicSource.toStereoFloat(le, 2, 1, false, st);       // mono LE -> duplicated
        assertEquals(1234f, st[0]);
        assertEquals(1234f, st[1]);
        assertEquals(-42f, st[2]);
        assertEquals(-42f, st[3]);
        byte[] be = {(byte) 0x04, (byte) 0xD2, (byte) 0xFF, (byte) 0xD6};   // 1234, -42 big-endian stereo
        JavaSoundMicSource.toStereoFloat(be, 1, 2, true, st);
        assertEquals(1234f, st[0]);
        assertEquals(-42f, st[1]);
    }

    private static WasapiLoopbackSource.Format fmt(int rate, int ch, int mask) {
        return new WasapiLoopbackSource.Format(rate, ch, 32, ch * 4, WasapiLoopbackSource.Format.FLOAT32, mask);
    }

    @Test
    void wasapiStereoPassthrough() {
        WasapiLoopbackSource.Converter c = new WasapiLoopbackSource.Converter(fmt(48000, 2, 0x3));
        PcmFifo f = new PcmFifo(1000);
        c.convertFloat(new float[]{1000, -2000, 3000, -4000}, 2, f);
        short[] out = new short[4];
        assertEquals(2, f.read(2, out));
        assertEquals(1000, out[0]);
        assertEquals(-2000, out[1]);
        assertEquals(-4000, out[3]);
    }

    @Test
    void wasapiMonoIsDuplicated() {
        WasapiLoopbackSource.Converter c = new WasapiLoopbackSource.Converter(fmt(48000, 1, 0x4));
        PcmFifo f = new PcmFifo(1000);
        c.convertFloat(new float[]{500, 600}, 2, f);
        short[] out = new short[4];
        f.read(2, out);
        assertEquals(500, out[0]);
        assertEquals(500, out[1]);
        assertEquals(600, out[3]);
    }

    @Test
    void wasapi51DownmixDropsLfeAndFoldsCentre() {
        // FL FR FC LFE BL BR
        WasapiLoopbackSource.Converter c = new WasapiLoopbackSource.Converter(fmt(48000, 6, 0x3F));
        PcmFifo f = new PcmFifo(1000);
        c.convertFloat(new float[]{1000, 0, 0, 0, 0, 0,    // FL only
                0, 0, 1000, 0, 0, 0,                       // FC only
                0, 0, 0, 10000, 0, 0,                      // LFE only
                0, 0, 0, 0, 0, 1000}, 4, f);               // BR only
        short[] out = new short[8];
        assertEquals(4, f.read(4, out));
        assertEquals(1000, out[0]);
        assertEquals(0, out[1]);
        assertEquals(707, out[2], 1);
        assertEquals(707, out[3], 1);
        assertEquals(0, out[4]);
        assertEquals(0, out[5]);
        assertEquals(0, out[6]);
        assertEquals(707, out[7], 1);
    }

    @Test
    void wasapiFormatEncodingSelection() {
        assertEquals(WasapiLoopbackSource.Format.FLOAT32, WasapiLoopbackSource.Format.encodingOf(true, false, 32));
        assertEquals(WasapiLoopbackSource.Format.INT16, WasapiLoopbackSource.Format.encodingOf(false, true, 16));
        assertEquals(WasapiLoopbackSource.Format.INT24, WasapiLoopbackSource.Format.encodingOf(false, true, 24));
        assertEquals(WasapiLoopbackSource.Format.INT32, WasapiLoopbackSource.Format.encodingOf(false, true, 32));
        assertEquals(WasapiLoopbackSource.Format.UNSUPPORTED, WasapiLoopbackSource.Format.encodingOf(true, false, 64));
    }

    @Test
    void loopbackNamesAreRecognised() {
        assertTrue(LoopbackDeviceDetector.isLoopbackName("Stereo Mix (Realtek(R) Audio)"));
        assertTrue(LoopbackDeviceDetector.isLoopbackName("BlackHole 2ch"));
        assertTrue(LoopbackDeviceDetector.isLoopbackName("CABLE Output (VB-Audio Virtual Cable)"));
        assertTrue(!LoopbackDeviceDetector.isLoopbackName("Microphone (Realtek(R) Audio)"));
        assertTrue(!LoopbackDeviceDetector.isLoopbackName("Primary Sound Capture Driver"));
    }

    @Test
    void configParsing() {
        assertTrue(AudioConfig.parseBool("on", false));
        assertTrue(!AudioConfig.parseBool("No", true));
        assertTrue(AudioConfig.parseBool("garbage", true));
        assertEquals(1.5f, AudioConfig.parseFloat(" 1.5 ", 1f));
        assertEquals(1f, AudioConfig.parseFloat("x", 1f));
        assertEquals(AudioConfig.MAX_GAIN, AudioConfig.clampGain(100f));
        assertEquals(0f, AudioConfig.clampGain(-1f));
    }
}
