package com.srd.common.audio;

import com.srd.common.protocol.WireReader;
import com.srd.common.protocol.WireWriter;
import javax.sound.sampled.AudioFormat;

/**
 * AUDIO payload: {@code u32 seq, i32 sampleRate, u8 channels, PCM s16le}. The stream format is
 * fixed at 48 kHz stereo in 20 ms frames (3,840 bytes, about 1.5 Mbit/s, trivial on a LAN). The
 * old 16 kHz mono stream made music and video audio sound like a phone call.
 */
public final class AudioPacket {
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;
    public static final int FRAME_MS = 20;
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000;   // 960
    public static final int FRAME_BYTES = SAMPLES_PER_FRAME * CHANNELS * 2;       // 3840
    public static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);

    public final int seq;
    public final int sampleRate;
    public final int channels;
    public final byte[] pcm;

    public AudioPacket(int seq, int sampleRate, int channels, byte[] pcm) {
        this.seq = seq;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.pcm = pcm;
    }

    public byte[] encode() {
        return new WireWriter(pcm.length + 12).i32(seq).i32(sampleRate).u8(channels).bytes(pcm).toByteArray();
    }

    public static AudioPacket decode(byte[] payload) {
        WireReader r = new WireReader(payload);
        int seq = r.i32();
        int rate = r.i32();
        int ch = r.u8();
        return new AudioPacket(seq, rate, ch, r.bytes(r.remaining()));
    }
}
