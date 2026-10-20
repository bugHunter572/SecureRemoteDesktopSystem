package com.srd.client.audio;

import com.srd.common.audio.Pcm;
import com.srd.common.util.Log;
import com.srd.common.util.Os;
import com.srd.common.util.Threads;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Ole32Util;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Windows system-output capture via WASAPI shared-mode loopback on the default render device,
 * through raw COM vtable calls (JNA).
 *
 * <p>Self-healing: the whole COM session runs inside an outer loop. Any failing HRESULT
 * (AUDCLNT_E_DEVICE_INVALIDATED when headphones are unplugged, for example), any exception, or a
 * change of the default output device (checked every 2 s by comparing IMMDevice IDs) releases
 * everything and initialises again. Failures back off 250 ms, 500 ms, 1 s, then 2 s.
 *
 * <p>The mix format (usually 48 kHz float32 stereo) is converted to 48 kHz stereo s16: float32,
 * int16, int24 and int32 samples are handled, more than two channels are downmixed, mono is
 * duplicated, and other rates are resampled with phase kept across packets. While nothing plays,
 * loopback delivers no packets. That is expected; the mixer pads silence.
 *
 * <p>Vtable indices, from the Windows SDK headers (mmdeviceapi.h, audioclient.h), counting the
 * three IUnknown methods:
 * <pre>
 * IMMDeviceEnumerator: GetDefaultAudioEndpoint=4
 * IMMDevice:           Activate=3, GetId=5
 * IAudioClient:        Initialize=3, GetMixFormat=8, Start=10, Stop=11, GetService=14
 * IAudioCaptureClient: GetBuffer=3, ReleaseBuffer=4, GetNextPacketSize=5
 * IUnknown:            Release=2
 * </pre>
 */
public final class WasapiLoopbackSource implements AudioSource {
    private static final int COINIT_MULTITHREADED = 0;
    private static final int CLSCTX_ALL = 0x17;
    private static final int AUDCLNT_SHAREMODE_SHARED = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    private static final int E_RENDER = 0;
    private static final int E_CONSOLE = 0;
    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;
    private static final long BUFFER_100NS = 2_000_000L;        // 200 ms device buffer
    private static final long DEVICE_CHECK_NANOS = 2_000_000_000L;
    private static final long[] BACKOFF_MS = {250, 500, 1000, 2000};

    private static final String CLSID_MMDeviceEnumerator = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_IMMDeviceEnumerator = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    private static final String IID_IAudioClient = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}";
    private static final String IID_IAudioCaptureClient = "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}";

    // Speaker position bits (ksmedia.h).
    private static final int SPK_FL = 0x1, SPK_FR = 0x2, SPK_FC = 0x4, SPK_LFE = 0x8, SPK_BL = 0x10,
            SPK_BR = 0x20, SPK_FLC = 0x40, SPK_FRC = 0x80, SPK_BC = 0x100, SPK_SL = 0x200, SPK_SR = 0x400;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile String status = "WASAPI loopback (starting)";
    private PcmFifo out;

    /** True on Windows when JNA and Ole32 can be loaded. */
    public static boolean isSupported() {
        if (!Os.WINDOWS) return false;
        return Os.tryNative("WASAPI probe", () -> Ole32.INSTANCE != null, false);
    }

    @Override
    public synchronized void start(PcmFifo fifo) {
        if (running) return;
        out = fifo;
        running = true;
        thread = Threads.start("audio-wasapi-loopback", Thread.MAX_PRIORITY, this::outerLoop);
    }

    @Override
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String name() {
        return "WASAPI loopback";
    }

    @Override
    public Kind kind() {
        return Kind.SYSTEM;
    }

    @Override
    public String status() {
        return status;
    }

    // ------------------------------------------------------------------ outer, self-healing loop

    private void outerLoop() {
        boolean comInit = false;
        try {
            int hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED).intValue();
            comInit = hr >= 0;     // S_OK or S_FALSE; RPC_E_CHANGED_MODE means someone else owns it
        } catch (Throwable t) {
            Log.warn("audio: WASAPI unavailable (" + t + ")");
            status = "WASAPI unavailable: " + t;
            running = false;
            return;
        }
        int failures = 0;
        String lastError = null;
        try {
            while (running) {
                String result;
                try {
                    result = session();
                } catch (Throwable t) {
                    result = "error: " + t;
                }
                if (!running) break;
                if (result == null) {
                    // Clean, intentional re-init (default device changed): no backoff.
                    failures = 0;
                    lastError = null;
                    continue;
                }
                if (!result.equals(lastError)) {
                    Log.warn("audio: WASAPI loopback " + result + "; reinitialising");
                    lastError = result;
                }
                status = "WASAPI loopback (recovering: " + result + ")";
                long backoff = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
                failures++;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            if (comInit) {
                try {
                    Ole32.INSTANCE.CoUninitialize();
                } catch (Throwable ignored) {
                }
            }
            status = "WASAPI loopback (stopped)";
        }
    }

    /**
     * One capture session on the current default render device.
     *
     * @return null when the session ended to switch to a new default device (or on stop), else a
     *         short failure description
     */
    private String session() {
        Pointer pEnum = null, pDevice = null, pClient = null, pCapture = null, pFmt = null;
        boolean started = false;
        try {
            PointerByReference pp = new PointerByReference();
            int hr = Ole32.INSTANCE.CoCreateInstance(Ole32Util.getGUIDFromString(CLSID_MMDeviceEnumerator), null,
                    CLSCTX_ALL, Ole32Util.getGUIDFromString(IID_IMMDeviceEnumerator), pp).intValue();
            if (hr < 0) return fail("CoCreateInstance(MMDeviceEnumerator)", hr);
            pEnum = pp.getValue();

            pp = new PointerByReference();
            hr = call(pEnum, 4, pEnum, E_RENDER, E_CONSOLE, pp);                    // GetDefaultAudioEndpoint
            if (hr < 0) return fail("GetDefaultAudioEndpoint", hr);
            pDevice = pp.getValue();
            String deviceId = deviceId(pDevice);

            pp = new PointerByReference();
            Guid.GUID iidClient = Ole32Util.getGUIDFromString(IID_IAudioClient);
            hr = call(pDevice, 3, pDevice, iidClient, CLSCTX_ALL, null, pp);        // Activate
            if (hr < 0) return fail("Activate(IAudioClient)", hr);
            pClient = pp.getValue();

            pp = new PointerByReference();
            hr = call(pClient, 8, pClient, pp);                                     // GetMixFormat
            if (hr < 0) return fail("GetMixFormat", hr);
            pFmt = pp.getValue();
            Format fmt = Format.read(pFmt);
            if (fmt.encoding == Format.UNSUPPORTED) {
                return "unsupported mix format " + fmt;
            }

            hr = call(pClient, 3, pClient, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                    BUFFER_100NS, 0L, pFmt, null);                                  // Initialize
            if (hr < 0) return fail("Initialize", hr);

            pp = new PointerByReference();
            Guid.GUID iidCapture = Ole32Util.getGUIDFromString(IID_IAudioCaptureClient);
            hr = call(pClient, 14, pClient, iidCapture, pp);                        // GetService
            if (hr < 0) return fail("GetService(IAudioCaptureClient)", hr);
            pCapture = pp.getValue();

            hr = call(pClient, 10, pClient);                                        // Start
            if (hr < 0) return fail("Start", hr);
            started = true;

            Log.info("audio: WASAPI loopback started on " + deviceId + " (" + fmt + ")");
            status = "WASAPI loopback " + fmt.rate + "Hz/" + fmt.channels + "ch";
            return captureLoop(pEnum, pCapture, deviceId, fmt);
        } finally {
            if (started) {
                try {
                    call(pClient, 11, pClient);                                     // Stop
                } catch (Throwable ignored) {
                }
            }
            if (pFmt != null) {
                try {
                    Ole32.INSTANCE.CoTaskMemFree(pFmt);
                } catch (Throwable ignored) {
                }
            }
            release(pCapture);
            release(pClient);
            release(pDevice);
            release(pEnum);
        }
    }

    private String captureLoop(Pointer pEnum, Pointer pCapture, String deviceId, Format fmt) {
        Converter conv = new Converter(fmt);
        IntByReference nextFrames = new IntByReference();
        PointerByReference ppData = new PointerByReference();
        IntByReference numFrames = new IntByReference();
        IntByReference flags = new IntByReference();
        long nextDeviceCheck = System.nanoTime() + DEVICE_CHECK_NANOS;

        while (running) {
            long now = System.nanoTime();
            if (now >= nextDeviceCheck) {
                // Loopback keeps capturing the OLD device after the default output changes
                // (e.g. switching to headphones), so poll the default device id.
                nextDeviceCheck = now + DEVICE_CHECK_NANOS;
                String id = currentDefaultId(pEnum);
                if (id != null && deviceId != null && !id.equals(deviceId)) {
                    Log.info("audio: default output device changed -> reinitialising WASAPI loopback");
                    return null;
                }
            }
            int hr = call(pCapture, 5, pCapture, nextFrames);                       // GetNextPacketSize
            if (hr < 0) return fail("GetNextPacketSize", hr);
            if (nextFrames.getValue() == 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return null;
                }
                continue;
            }
            // Drain all available packets.
            while (running) {
                hr = call(pCapture, 3, pCapture, ppData, numFrames, flags, null, null); // GetBuffer
                if (hr < 0) return fail("GetBuffer", hr);
                int n = numFrames.getValue();
                if (hr == 0x08890001 || n == 0) {                    // AUDCLNT_S_BUFFER_EMPTY
                    if (n > 0) call(pCapture, 4, pCapture, n);
                    break;
                }
                try {
                    boolean silent = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                    conv.convert(silent ? null : ppData.getValue(), n, out);
                } finally {
                    hr = call(pCapture, 4, pCapture, n);                            // ReleaseBuffer
                }
                if (hr < 0) return fail("ReleaseBuffer", hr);
                hr = call(pCapture, 5, pCapture, nextFrames);
                if (hr < 0) return fail("GetNextPacketSize", hr);
                if (nextFrames.getValue() == 0) break;
            }
        }
        return null;
    }

    /** Default render device id, or null when it can't be read (no device, transient error). */
    private static String currentDefaultId(Pointer pEnum) {
        PointerByReference pp = new PointerByReference();
        int hr = call(pEnum, 4, pEnum, E_RENDER, E_CONSOLE, pp);
        if (hr < 0) return "<none:" + Integer.toHexString(hr) + ">";   // device gone: also a change
        Pointer dev = pp.getValue();
        try {
            return deviceId(dev);
        } finally {
            release(dev);
        }
    }

    private static String deviceId(Pointer pDevice) {
        PointerByReference ppId = new PointerByReference();
        int hr = call(pDevice, 5, pDevice, ppId);                                   // IMMDevice::GetId
        if (hr < 0 || ppId.getValue() == null) return null;
        Pointer s = ppId.getValue();
        try {
            return s.getWideString(0);
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(s);
        }
    }

    private static String fail(String what, int hr) {
        return what + " failed 0x" + Integer.toHexString(hr) + hrName(hr);
    }

    private static String hrName(int hr) {
        switch (hr) {
            case 0x88890004: return " (AUDCLNT_E_DEVICE_INVALIDATED)";
            case 0x88890001: return " (AUDCLNT_E_NOT_INITIALIZED)";
            case 0x8889000A: return " (AUDCLNT_E_DEVICE_IN_USE)";
            case 0x88890008: return " (AUDCLNT_E_UNSUPPORTED_FORMAT)";
            case 0x80070490: return " (E_NOTFOUND: no output device)";
            case 0x88890010: return " (AUDCLNT_E_SERVICE_NOT_RUNNING)";
            default: return "";
        }
    }

    private static int call(Pointer iface, int index, Object... args) {
        Pointer vtable = iface.getPointer(0);
        Pointer fn = vtable.getPointer((long) index * Native.POINTER_SIZE);
        return Function.getFunction(fn).invokeInt(args);
    }

    private static void release(Pointer iface) {
        if (iface == null) return;
        try {
            call(iface, 2, iface);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ format + conversion

    /** The WAVEFORMATEX(TENSIBLE) fields we need. */
    static final class Format {
        static final int FLOAT32 = 1, INT16 = 2, INT24 = 3, INT32 = 4, UNSUPPORTED = 0;
        final int rate, channels, bits, blockAlign, encoding, channelMask;

        Format(int rate, int channels, int bits, int blockAlign, int encoding, int channelMask) {
            this.rate = rate;
            this.channels = channels;
            this.bits = bits;
            this.blockAlign = blockAlign;
            this.encoding = encoding;
            this.channelMask = channelMask;
        }

        static Format read(Pointer p) {
            int tag = p.getShort(0) & 0xFFFF;
            int ch = p.getShort(2) & 0xFFFF;
            int rate = p.getInt(4);
            int align = p.getShort(12) & 0xFFFF;
            int bits = p.getShort(14) & 0xFFFF;
            int cb = p.getShort(16) & 0xFFFF;
            int mask = 0;
            boolean isFloat = tag == WAVE_FORMAT_IEEE_FLOAT;
            boolean isPcm = tag == WAVE_FORMAT_PCM;
            if (tag == WAVE_FORMAT_EXTENSIBLE && cb >= 22) {
                mask = p.getInt(20);
                int sub = p.getInt(24);     // first DWORD of SubFormat GUID: 1 = PCM, 3 = IEEE float
                isFloat = sub == 3;
                isPcm = sub == 1;
            }
            return new Format(rate, ch, bits, align, encodingOf(isFloat, isPcm, bits), mask);
        }

        static int encodingOf(boolean isFloat, boolean isPcm, int bits) {
            if (isFloat && bits == 32) return FLOAT32;
            if (isPcm && bits == 16) return INT16;
            if (isPcm && bits == 24) return INT24;
            if (isPcm && bits == 32) return INT32;
            return UNSUPPORTED;
        }

        @Override
        public String toString() {
            String e = encoding == FLOAT32 ? "float32" : encoding == INT16 ? "int16"
                    : encoding == INT24 ? "int24" : encoding == INT32 ? "int32" : "unsupported(" + bits + "bit)";
            return rate + "Hz " + channels + "ch " + e + (channelMask != 0 ? " mask=0x" + Integer.toHexString(channelMask) : "");
        }
    }

    /**
     * Converts device frames to 48 kHz stereo s16 in a FIFO. Package-private and pointer-free at its
     * core ({@link #convertFloat}) so it can be tested without a device.
     */
    static final class Converter {
        private final Format fmt;
        private final float[] coefL, coefR;
        private final StereoResampler resampler;
        private float[] stereo = new float[0];
        private float[] floatBuf = new float[0];
        private short[] shortBuf = new short[0];
        private int[] intBuf = new int[0];
        private byte[] byteBuf = new byte[0];

        Converter(Format fmt) {
            this.fmt = fmt;
            int ch = Math.max(1, fmt.channels);
            coefL = new float[ch];
            coefR = new float[ch];
            downmixCoefficients(ch, fmt.channelMask, coefL, coefR);
            resampler = new StereoResampler(fmt.rate > 0 ? fmt.rate : 48000);
        }

        /** {@code data == null} means a SILENT buffer of {@code frames} frames. */
        void convert(Pointer data, int frames, PcmFifo fifo) {
            int ch = Math.max(1, fmt.channels);
            int samples = frames * ch;
            if (floatBuf.length < samples) floatBuf = new float[samples];
            if (data == null) {
                java.util.Arrays.fill(floatBuf, 0, samples, 0f);
            } else if (fmt.blockAlign != 0 && fmt.blockAlign != ch * fmt.bits / 8) {
                // Padded container; read sample by sample.
                int bps = fmt.bits / 8;
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < ch; c++) {
                        floatBuf[f * ch + c] = readOne(data, (long) f * fmt.blockAlign + (long) c * bps);
                    }
                }
            } else {
                switch (fmt.encoding) {
                    case Format.FLOAT32:
                        data.read(0, floatBuf, 0, samples);
                        for (int i = 0; i < samples; i++) floatBuf[i] *= 32767f;
                        break;
                    case Format.INT16:
                        if (shortBuf.length < samples) shortBuf = new short[samples];
                        data.read(0, shortBuf, 0, samples);
                        for (int i = 0; i < samples; i++) floatBuf[i] = shortBuf[i];
                        break;
                    case Format.INT32:
                        if (intBuf.length < samples) intBuf = new int[samples];
                        data.read(0, intBuf, 0, samples);
                        for (int i = 0; i < samples; i++) floatBuf[i] = intBuf[i] / 65536f;
                        break;
                    case Format.INT24:
                        if (byteBuf.length < samples * 3) byteBuf = new byte[samples * 3];
                        data.read(0, byteBuf, 0, samples * 3);
                        for (int i = 0; i < samples; i++) {
                            int v = (byteBuf[3 * i] & 0xFF) | ((byteBuf[3 * i + 1] & 0xFF) << 8) | (byteBuf[3 * i + 2] << 16);
                            floatBuf[i] = v / 256f;
                        }
                        break;
                    default:
                        java.util.Arrays.fill(floatBuf, 0, samples, 0f);
                }
            }
            convertFloat(floatBuf, frames, fifo);
        }

        private float readOne(Pointer p, long off) {
            switch (fmt.encoding) {
                case Format.FLOAT32: return p.getFloat(off) * 32767f;
                case Format.INT16: return p.getShort(off);
                case Format.INT32: return p.getInt(off) / 65536f;
                case Format.INT24: {
                    int v = (p.getByte(off) & 0xFF) | ((p.getByte(off + 1) & 0xFF) << 8) | (p.getByte(off + 2) << 16);
                    return v / 256f;
                }
                default: return 0f;
            }
        }

        /** Interleaved device-channel samples at s16 scale -> downmix -> resample -> FIFO. */
        void convertFloat(float[] in, int frames, PcmFifo fifo) {
            int ch = coefL.length;
            if (stereo.length < frames * 2) stereo = new float[frames * 2];
            if (ch == 2) {
                System.arraycopy(in, 0, stereo, 0, frames * 2);
            } else if (ch == 1) {
                for (int f = 0; f < frames; f++) {
                    stereo[2 * f] = in[f];
                    stereo[2 * f + 1] = in[f];
                }
            } else {
                for (int f = 0; f < frames; f++) {
                    float l = 0, r = 0;
                    int base = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float s = in[base + c];
                        l += s * coefL[c];
                        r += s * coefR[c];
                    }
                    // Surround content can sum above full scale; limit smoothly rather than wrap.
                    stereo[2 * f] = Pcm.softClip(l);
                    stereo[2 * f + 1] = Pcm.softClip(r);
                }
            }
            resampler.process(stereo, frames, fifo);
        }

        /** ITU-style stereo downmix weights per channel (centre/surround at -3 dB, LFE dropped). */
        static void downmixCoefficients(int ch, int mask, float[] l, float[] r) {
            if (ch == 1) {
                l[0] = 1f;
                r[0] = 1f;
                return;
            }
            if (ch == 2) {
                l[0] = 1f;
                r[1] = 1f;
                return;
            }
            if (Integer.bitCount(mask) != ch) mask = defaultMask(ch);
            final float h = 0.7071f;
            int c = 0;
            for (int bit = 0; bit < 18 && c < ch; bit++) {
                int spk = 1 << bit;
                if ((mask & spk) == 0) continue;
                float cl, cr;
                switch (spk) {
                    case SPK_FL: cl = 1f; cr = 0f; break;
                    case SPK_FR: cl = 0f; cr = 1f; break;
                    case SPK_FC: case SPK_BC: cl = h; cr = h; break;
                    case SPK_LFE: cl = 0f; cr = 0f; break;
                    case SPK_BL: case SPK_SL: case SPK_FLC: cl = h; cr = 0f; break;
                    case SPK_BR: case SPK_SR: case SPK_FRC: cl = 0f; cr = h; break;
                    default: cl = 0.5f * h; cr = 0.5f * h; break;      // top/height channels
                }
                l[c] = cl;
                r[c] = cr;
                c++;
            }
            for (; c < ch; c++) {        // mask had fewer bits than channels: alternate L/R
                l[c] = (c % 2 == 0) ? h : 0f;
                r[c] = (c % 2 == 0) ? 0f : h;
            }
        }

        static int defaultMask(int ch) {
            switch (ch) {
                case 3: return SPK_FL | SPK_FR | SPK_FC;
                case 4: return SPK_FL | SPK_FR | SPK_BL | SPK_BR;
                case 5: return SPK_FL | SPK_FR | SPK_FC | SPK_BL | SPK_BR;
                case 6: return SPK_FL | SPK_FR | SPK_FC | SPK_LFE | SPK_BL | SPK_BR;
                case 7: return SPK_FL | SPK_FR | SPK_FC | SPK_LFE | SPK_BC | SPK_SL | SPK_SR;
                case 8: return SPK_FL | SPK_FR | SPK_FC | SPK_LFE | SPK_BL | SPK_BR | SPK_SL | SPK_SR;
                default: return 0;
            }
        }
    }
}
