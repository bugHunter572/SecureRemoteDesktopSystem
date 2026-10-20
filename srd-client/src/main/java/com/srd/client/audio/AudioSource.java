package com.srd.client.audio;

/**
 * A capture source feeding the {@link AudioEngine} mixer.
 *
 * <p>A source runs its own capture thread and pushes 48 kHz stereo s16 PCM into the
 * {@link PcmFifo} given to {@link #start}. It must never block the mixer and must heal itself:
 * when its device fails or changes it reopens (with backoff) instead of giving up. A source does
 * not produce silence while its device is idle; the mixer pads missing data with silence.
 */
public interface AudioSource {
    enum Kind { MIC, SYSTEM }

    /** Starts capturing into {@code out}. Must return quickly (open devices on the capture thread). */
    void start(PcmFifo out);

    /** Stops capturing and releases the device. Idempotent; may block briefly (at most ~1 s). */
    void stop();

    /** Short human-readable name, e.g. "WASAPI loopback". */
    String name();

    /** Whether this is the microphone or the system-output (loopback) source; selects the gain. */
    default Kind kind() {
        return Kind.MIC;
    }

    /** Current state for diagnostics, e.g. device name or last error. */
    default String status() {
        return name();
    }

    /**
     * How much audio the mixer should buffer for this source before reading it, in ms. Sources
     * that deliver in large bursts ask for more.
     */
    default int primeMs() {
        return 40;
    }
}
