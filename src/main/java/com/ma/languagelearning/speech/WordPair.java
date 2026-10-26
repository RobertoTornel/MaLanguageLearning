package com.ma.languagelearning.speech;

import java.util.Map;

/**
 * A word rendered in the two-translation system.
 *
 * <p>{@link #primary()} is what the player must pronounce and what the popup shows first,
 * rendered in the script chosen in the settings. {@link #secondary()} is the companion
 * rendering (another script or the native-language translation), and {@link #phonetic()}
 * carries an IPA-style transcription when one is available.</p>
 *
 * @param primary   word in the primary script
 * @param secondary companion rendering for the secondary slot
 * @param phonetic  IPA/phonetic transcription, empty string when unavailable
 * @param script    name of the primary script, e.g. {@code "hiragana"}
 * @param variants  every available rendering keyed by script name
 * @param native    translation in the player's native language, when known
 */
public record WordPair(String primary,
                       String secondary,
                       String phonetic,
                       String script,
                       Map<String, String> variants,
                       String native_) {

    public WordPair {
        primary = primary == null ? "" : primary;
        secondary = secondary == null ? "" : secondary;
        phonetic = phonetic == null ? "" : phonetic;
        script = script == null ? "" : script;
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        native_ = native_ == null ? "" : native_;
    }

    /** Fallback rendering when the sidecar could not produce a translation. */
    public static WordPair raw(String word) {
        return new WordPair(word, word, "", "plain", Map.of("plain", word), "");
    }

    public boolean isEmpty() {
        return primary.isBlank();
    }
}
