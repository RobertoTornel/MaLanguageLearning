package com.ma.languagelearning.speech;

/**
 * Result of a speech-to-text request against the local sidecar.
 *
 * @param text       the recognised text, never null
 * @param confidence model confidence in [0,1], or -1 when the backend does not report one
 * @param language   BCP-47 tag the engine actually used
 * @param model      backend id that produced the result, e.g. {@code "parakeet-v3"}
 * @param latencyMs  wall-clock time the sidecar spent, in milliseconds
 */
public record SpeechResult(String text, float confidence, String language, String model, long latencyMs) {

    public static SpeechResult empty(String language) {
        return new SpeechResult("", -1f, language, "none", 0L);
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
