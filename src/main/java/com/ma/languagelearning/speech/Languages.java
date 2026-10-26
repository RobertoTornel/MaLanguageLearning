package com.ma.languagelearning.speech;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supported languages and their script options.
 *
 * <p>The first 25 entries are exactly the languages NVIDIA Parakeet TDT 0.6B v3 can
 * recognise (verified against the model card). Everything else routes to a Whisper
 * backend in the sidecar, which is why the engine is model-agnostic.</p>
 */
public final class Languages {

    /** One entry per language: display name, script options, and which engine handles it. */
    public record Lang(String code, String name, List<String> scripts, boolean parakeet) {
    }

    private static final Map<String, Lang> BY_CODE = new LinkedHashMap<>();

    private static void add(String code, String name, boolean parakeet, String... scripts) {
        BY_CODE.put(code, new Lang(code, name, List.of(scripts), parakeet));
    }

    static {
        // ---- the 25 Parakeet v3 languages -----------------------------------
        add("en", "English", true, "plain", "phonetic");
        add("de", "German", true, "plain", "phonetic");
        add("fr", "French", true, "plain", "phonetic");
        add("es", "Spanish", true, "plain", "phonetic");
        add("it", "Italian", true, "plain", "phonetic");
        add("pt", "Portuguese", true, "plain", "phonetic");
        add("nl", "Dutch", true, "plain", "phonetic");
        add("pl", "Polish", true, "plain", "phonetic");
        add("cs", "Czech", true, "plain", "phonetic");
        add("sk", "Slovak", true, "plain", "phonetic");
        add("sl", "Slovenian", true, "plain", "phonetic");
        add("hr", "Croatian", true, "plain", "phonetic");
        add("bg", "Bulgarian", true, "cyrillic", "romanized", "phonetic");
        add("ru", "Russian", true, "cyrillic", "romanized", "phonetic");
        add("uk", "Ukrainian", true, "cyrillic", "romanized", "phonetic");
        add("el", "Greek", true, "greek", "romanized", "phonetic");
        add("hu", "Hungarian", true, "plain", "phonetic");
        add("ro", "Romanian", true, "plain", "phonetic");
        add("da", "Danish", true, "plain", "phonetic");
        add("sv", "Swedish", true, "plain", "phonetic");
        add("fi", "Finnish", true, "plain", "phonetic");
        add("et", "Estonian", true, "plain", "phonetic");
        add("lv", "Latvian", true, "plain", "phonetic");
        add("lt", "Lithuanian", true, "plain", "phonetic");
        add("mt", "Maltese", true, "plain", "phonetic");

        // ---- beyond Parakeet: Whisper backend --------------------------------
        add("ja", "Japanese", false, "hiragana", "katakana", "kanji", "romaji", "furigana", "phonetic");
        add("zh", "Chinese", false, "simplified", "traditional", "pinyin", "phonetic");
        add("ko", "Korean", false, "hangul", "romanized", "phonetic");
        add("ar", "Arabic", false, "arabic", "romanized", "phonetic");
        add("hi", "Hindi", false, "devanagari", "romanized", "phonetic");
        add("tr", "Turkish", false, "plain", "phonetic");
        add("vi", "Vietnamese", false, "plain", "phonetic");
        add("th", "Thai", false, "thai", "romanized", "phonetic");
        add("he", "Hebrew", false, "hebrew", "romanized", "phonetic");
        add("id", "Indonesian", false, "plain", "phonetic");
        add("fa", "Persian", false, "arabic", "romanized", "phonetic");
        add("sr", "Serbian", false, "cyrillic", "latin", "phonetic");
    }

    /** Every supported language code, in declaration order. */
    public static final List<String> ALL_CODES = List.copyOf(BY_CODE.keySet());

    /** Codes sensible as a native/companion language (the common ones first). */
    public static final List<String> NATIVE_CODES = buildNativeCodes();

    private static List<String> buildNativeCodes() {
        List<String> list = new ArrayList<>();
        for (String c : new String[]{"en", "es", "de", "fr", "ja", "zh", "ko", "pt", "ru", "it"}) {
            if (BY_CODE.containsKey(c)) {
                list.add(c);
            }
        }
        for (String c : BY_CODE.keySet()) {
            if (!list.contains(c)) {
                list.add(c);
            }
        }
        return List.copyOf(list);
    }

    public static Lang get(String code) {
        Lang lang = BY_CODE.get(code);
        return lang != null ? lang : BY_CODE.get("en");
    }

    public static String displayName(String code) {
        Lang lang = BY_CODE.get(code);
        return lang == null ? code : lang.name();
    }

    public static List<String> scriptsFor(String code) {
        return get(code).scripts();
    }

    /** True when Parakeet v3 can handle this language without a Whisper fallback. */
    public static boolean isParakeet(String code) {
        return get(code).parakeet();
    }

    private Languages() {
    }
}
