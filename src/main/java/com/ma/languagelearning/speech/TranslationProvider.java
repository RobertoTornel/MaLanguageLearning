package com.ma.languagelearning.speech;

/**
 * Resolves the display form of a word in the two-translation system.
 */
public interface TranslationProvider {

    /**
     * Resolve a word into its primary/secondary renderings.
     *
     * @param sourceText    the English name of the item (or any source word)
     * @param language      BCP-47 target language tag
     * @param primaryScript script name to put in the primary slot, e.g. {@code "kanji"}
     * @param secondaryScript script name for the secondary slot, or {@code "native"}
     * @return the resolved pair; implementations must never return null
     */
    WordPair resolve(String sourceText, String language, String primaryScript, String secondaryScript);

    /** Text-to-speech of a word, returning 16 kHz mono WAV bytes, or null when unavailable. */
    byte[] synthesize(String text, String language);

    /** True when the translation backend (not the STT engine) is reachable. */
    boolean isAvailable();

    default void close() {
    }
}
