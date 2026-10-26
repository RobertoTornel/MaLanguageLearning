package com.ma.languagelearning.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the matching rules.
 *
 * <p>These cases are taken from real parakeet-v3 and whisper output measured on this
 * machine, so they describe what the recogniser actually produces rather than what one
 * might hope it produces.</p>
 */
class SpeechMatcherTest {

    // ---------------------------------------------------------------- exact + near

    @Test
    @DisplayName("an exactly spoken word passes")
    void exactMatch() {
        assertTrue(SpeechMatcher.matches("cobblestone", "cobblestone", List.of(), "en"));
    }

    @Test
    @DisplayName("measured mis-hearings of a correct answer still pass")
    void realMishearingsPass() {
        // Parakeet hears a hard 'c' as 'p' on this microphone.
        assertTrue(SpeechMatcher.matches("cobblestone", "Pobblestone", List.of(), "en"));
        assertTrue(SpeechMatcher.matches("cobblestone", "Cobble stone", List.of(), "en"));
        assertTrue(SpeechMatcher.matches("cobblestone", "cobblestones", List.of(), "en"));
        // Trailing punctuation and casing are normalised away.
        assertTrue(SpeechMatcher.matches("stone", "Tone.", List.of(), "en"));
        assertTrue(SpeechMatcher.matches("sword", "Sword.", List.of(), "en"));
    }

    // ---------------------------------------------------------------- rejections

    @Test
    @DisplayName("unrelated speech never passes")
    void unrelatedSpeechFails() {
        // The whole point of the mod: saying anything at all must not count.
        for (String wrong : List.of("hello there", "banana", "thank you", "anything",
                "lorem ipsum", "no idea", "something else entirely")) {
            assertFalse(SpeechMatcher.matches("cobblestone", wrong, List.of(), "en"),
                    "should reject: " + wrong);
        }
    }

    @Test
    @DisplayName("empty input fails rather than passing vacuously")
    void emptyFails() {
        assertFalse(SpeechMatcher.matches("cobblestone", "", List.of(), "en"));
        assertFalse(SpeechMatcher.matches("cobblestone", null, List.of(), "en"));
        assertFalse(SpeechMatcher.matches("cobblestone", "   ", List.of(), "en"));
    }

    @Test
    @DisplayName("short words must match exactly, not fuzzily")
    void shortWordsAreStrict() {
        // "dirt" heard as "dir" is a truncation, not a pass; too much meaning is lost.
        assertFalse(SpeechMatcher.matches("dirt", "dir", List.of(), "en"));
        assertFalse(SpeechMatcher.matches("dirt", "no idea", List.of(), "en"));
    }

    // ---------------------------------------------------------------- Japanese

    @Test
    @DisplayName("Japanese kanji and kana match through the alternatives list")
    void japaneseVariantsMatch() {
        // Whisper may return either the kanji or the kana for the same word.
        assertTrue(SpeechMatcher.matches("土", "土", List.of("つち"), "ja"));
        assertTrue(SpeechMatcher.matches("つち", "つち", List.of("土"), "ja"));
        // A different word must not slip through on a one-glyph mismatch.
        assertFalse(SpeechMatcher.matches("土", "犬", List.of("つち"), "ja"));
    }

    @Test
    @DisplayName("long Japanese words match across script via alternatives")
    void japaneseLongWord() {
        assertTrue(SpeechMatcher.matches("こむぎのたね", "小麦の種", List.of("小麦の種"), "ja"));
    }

    // ---------------------------------------------------------------- alternatives

    @Test
    @DisplayName("the secondary rendering is accepted")
    void secondaryAccepted() {
        assertTrue(SpeechMatcher.matches("water", "wasser", List.of("wasser"), "de"));
    }

    // ---------------------------------------------------------------- normalise

    @Test
    @DisplayName("normalise strips case, punctuation and whitespace")
    void normalizeBasic() {
        assertTrue(SpeechMatcher.normalize("Cobble Stone!").equals("cobblestone"));
        assertTrue(SpeechMatcher.normalize("  Sword.  ").equals("sword"));
    }

    @Test
    @DisplayName("normalise folds katakana to hiragana")
    void normalizeKana() {
        // ツチ (katakana) and つち (hiragana) are the same word.
        assertTrue(SpeechMatcher.normalize("ツチ").equals(SpeechMatcher.normalize("つち")));
    }

    @Test
    @DisplayName("normalise drops macrons so romaji variants compare equal")
    void normalizeMacrons() {
        assertTrue(SpeechMatcher.normalize("tōkyō").equals(SpeechMatcher.normalize("tokyo")));
    }

    // ---------------------------------------------------------------- fuzz

    @Test
    @DisplayName("no false positives across a wide sample of wrong words")
    void noFalsePositives() {
        List<String> wants = List.of("cobblestone", "stone", "dirt", "apple", "sword",
                "oak_log", "diamond_pickaxe", "wheat_seeds");
        List<String> wrong = List.of("hello", "thank you", "banana", "what", "no", "yes",
                "the", "ok", "hi", "stop", "blah", "test", "one", "two", "good", "cobble",
                "pick", "axe", "oak", "log", "seeds of wheat");
        for (String want : wants) {
            for (String got : wrong) {
                assertFalse(SpeechMatcher.matches(want, got, List.of(), "en"),
                        want + " must not accept " + got);
            }
        }
    }
}
