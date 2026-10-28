package com.ma.languagelearning.audio;

/**
 * Microphone capture contract.
 *
 * <p>Records fixed-duration 16 kHz mono 16-bit little-endian PCM. Implementations are
 * client-only and must fail soft: {@link #start()} returns false when no capture device
 * is usable so the game can fall back to a manual skip prompt.</p>
 */
public interface AudioCapture {

    /** Begin capturing for {@code maxSeconds}. Returns false when no microphone is available. */
    boolean start(int maxSeconds);

    /** True while a recording is in progress. */
    boolean isRecording();

    /** Peak amplitude of the last capture (16-bit full scale = 32767), or 0 if unknown. */
    int peakAmplitude();

    /** True once speech was heard and then followed by enough silence to stop. */
    boolean speechEnded();

    /** Stop capturing and return the recorded PCM (may be shorter than the requested window). */
    byte[] stop();

    /** Abort a capture and discard buffered audio. */
    void cancel();

    /** Reason the last start/stop failed, or null. */
    String lastError();

    /** Free the mixer/line resources. */
    void close();
}
