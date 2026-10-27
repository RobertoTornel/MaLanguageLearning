package com.ma.languagelearning.game;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Normalises and compares speech-to-text output against the expected word.
 *
 * <p>Recognition is never exact: engines drop punctuation, return full-width CJK, write
 * katakana where the word is hiragana, or add romanisation macrons. Normalising both sides
 * first turns those into a byte-for-byte comparison; a small Levenshtein tolerance then
 * absorbs the one-character slips that are unavoidable for real speakers.</p>
 */
public final class SpeechMatcher {

    private SpeechMatcher() {
    }

    /**
     * Canonical form used for comparison: lowercase, no diacritics, no punctuation or
     * whitespace, full-width CJK collapsed, katakana folded to hiragana, macrons dropped.
     */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }

        // Compatibility-decompose first so full-width latin/digits and half-width katakana
        // become their ordinary forms, then strip combining marks (this is what removes
        // macrons, accents and dakuten-style marks from the comparison).
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD);
        StringBuilder stripped = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            stripped.appendCodePoint(cp);
        }

        String lower = stripped.toString().toLowerCase(Locale.ROOT);

        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);

            // Katakana -> hiragana (U+30A1..U+30F6 -> U+3041..U+3096).
            if (cp >= 0x30A1 && cp <= 0x30F6) {
                cp = cp - 0x30A1 + 0x3041;
            }
            // Half-width katakana leftovers (U+FF66..U+FF9D) fold into hiragana too.
            else if (cp >= 0xFF66 && cp <= 0xFF9D) {
                int kana = cp - 0xFF66;
                int full = 0x30A1 + kana;
                if (full >= 0x30A1 && full <= 0x30F6) {
                    cp = full - 0x30A1 + 0x3041;
                }
            }

            if (Character.isWhitespace(cp)) {
                continue;
            }
            if (isPunctuation(cp)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /**
     * True when the spoken text is an acceptable rendering of the expected word.
     *
     * <p>Accepts on normalised equality, on a small edit distance for words of four or more
     * characters, or when the spoken text matches one of the accepted alternatives. Words
     * shorter than three characters (the CJK case, where one glyph is a whole word) must
     * match exactly.</p>
     *
     * @param expected     the word the player was asked to say
     * @param spoken       what the engine heard
     * @param alternatives other accepted renderings (another script, native translation)
     * @param language     BCP-47 target language tag, currently used only for logging
     */
    public static boolean matches(String expected, String spoken, List<String> alternatives, String language) {
        String want = normalize(expected);
        if (want.isEmpty()) {
            return false;
        }
        String got = normalize(spoken);
        if (got.isEmpty()) {
            return false;
        }

        if (want.equals(got)) {
            return true;
        }

        if (alternatives != null) {
            for (String alternative : alternatives) {
                String alt = normalize(alternative);
                if (!alt.isEmpty() && alt.equals(got)) {
                    return true;
                }
            }
        }

        // Fuzzy matching only where a mis-hearing is plausible but harmless: long enough to
        // have redundancy, and not kanji/ideographs (where one glyph changes the whole word).
        // Kana and hangul are phonetic — pronunciation slips are expected — so they keep
        // fuzzy tolerance. Kanji is logographic — each glyph is a distinct word — so it
        // stays exact-match-only.
        if (want.length() >= 4 && got.length() >= 4
                && !containsIdeographs(want) && !containsIdeographs(got)) {
            int distance = levenshtein(want, got);
            int tolerance = Math.max(1, (int) Math.floor(want.length() * 0.25));
            return distance <= tolerance;
        }
        return false;
    }

    /** Plain Levenshtein edit distance, two-row rolling buffer. */
    public static int levenshtein(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        if (a.equals(b)) {
            return 0;
        }
        int[] left = toCodePoints(a);
        int[] right = toCodePoints(b);
        if (left.length == 0) {
            return right.length;
        }
        if (right.length == 0) {
            return left.length;
        }

        int[] previous = new int[right.length + 1];
        int[] current = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length; i++) {
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                int cost = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length];
    }

    /** True when the text contains CJK ideographs, kana, hangul or full-width forms. */
    public static boolean containsCjk(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
                return true;
            }
        }
        return false;
    }

    /** True when the text contains kanji/ideographs (logographic: one glyph = one word).
     *  Kana and hangul are phonetic and return false here — they keep fuzzy tolerance.
     */
    public static boolean containsIdeographs(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static int[] toCodePoints(String s) {
        int count = s.codePointCount(0, s.length());
        int[] points = new int[count];
        int index = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            points[index++] = cp;
            i += Character.charCount(cp);
        }
        return points;
    }

    private static boolean isPunctuation(int cp) {
        // Strip everything that is not a letter or digit: punctuation, symbols, and the
        // engines' favourite filler marks all disappear here.
        return !Character.isLetterOrDigit(cp);
    }
}
