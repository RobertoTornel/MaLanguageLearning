package com.ma.languagelearning.speech;

/**
 * Speech-to-text client contract.
 *
 * <p>Implementations must be safe to call from any thread and must never throw for
 * ordinary network/engine failures — they return {@link SpeechResult#empty(String)}
 * and report the problem through {@link #lastError()}.</p>
 */
public interface SttClient {

    /**
     * Transcribe 16 kHz mono 16-bit PCM audio.
     *
     * @param pcm16kMono raw little-endian PCM samples
     * @param language   BCP-47 tag to hint the engine with
     * @return the transcription, never null
     */
    SpeechResult transcribe(byte[] pcm16kMono, String language);

    /** Human-readable description of the most recent failure, or null if the last call succeeded. */
    String lastError();

    /** True when the underlying engine reports it is reachable and ready. */
    boolean isAvailable();

    /** Shut the client down and release any transport resources. */
    default void close() {
    }
}
