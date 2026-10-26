package com.ma.languagelearning.audio;

/**
 * Microphone capture contract.
 *
 * <p>Records fixed-duration 16 kHz mono 16-bit little-endian PCM. Implementations are
 * client-only and must fail soft: {@link #start()} returns false when no capture device
 * is usable so the game can fall back to a manual skip prompt.</p>
 */
public interface AudioCapture {

    /** Begin capturing. Returns false when no microphone is available. */
    boolean start();

    /** True while a recording is in progress. */
    boolean isRecording();

    /** Stop capturing and return the recorded PCM (may be shorter than the requested window). */
    byte[] stop();

    /** Abort a capture and discard buffered audio. */
    void cancel();

    /** Reason the last start/stop failed, or null. */
    String lastError();

    /** Free the mixer/line resources. */
    void close();
}
