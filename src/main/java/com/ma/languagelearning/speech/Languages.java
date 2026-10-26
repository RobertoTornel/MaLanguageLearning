package com.ma.languagelearning.speech;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supported languages and their script options.
 *
 * <p>This table mirrors the sidecar's own registry ({@code GET /languages}) exactly: the same
 * codes, the same script names, and the same split between Parakeet v3 (25 European
 * languages, recognised locally on CPU or GPU) and Whisper (everything else). Keeping the
 * two in step matters — the mod sends the chosen script name straight through to the
 * sidecar, so a name that is not in this list produces a fallback rendering.</p>
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
        // ---- the 25 Parakeet v3 languages (fast, fully local, CPU or GPU) ----
        add("en", "English", true, "latin", "ipa");
        add("de", "German", true, "latin", "ipa");
        add("es", "Spanish", true, "latin", "ipa");
        add("fr", "French", true, "latin", "ipa");
        add("it", "Italian", true, "latin", "ipa");
        add("pt", "Portuguese", true, "latin", "ipa");
        add("nl", "Dutch", true, "latin", "ipa");
        add("pl", "Polish", true, "latin", "ipa");
        add("cs", "Czech", true, "latin", "ipa");
        add("sk", "Slovak", true, "latin", "ipa");
        add("sl", "Slovene", true, "latin", "ipa");
        add("hr", "Croatian", true, "latin", "ipa");
        add("bg", "Bulgarian", true, "cyrillic", "ipa");
        add("ru", "Russian", true, "cyrillic", "ipa");
        add("uk", "Ukrainian", true, "cyrillic", "ipa");
        add("el", "Greek", true, "greek", "ipa");
        add("hu", "Hungarian", true, "latin", "ipa");
        add("ro", "Romanian", true, "latin", "ipa");
        add("da", "Danish", true, "latin", "ipa");
        add("sv", "Swedish", true, "latin", "ipa");
        add("fi", "Finnish", true, "latin", "ipa");
        add("et", "Estonian", true, "latin", "ipa");
        add("lv", "Latvian", true, "latin", "ipa");
        add("lt", "Lithuanian", true, "latin", "ipa");
        add("mt", "Maltese", true, "latin", "ipa");

        // ---- beyond Parakeet: routed to the Whisper backend -------------------
        add("ja", "Japanese", false, "hiragana", "katakana", "kanji", "romaji", "furigana");
        add("zh", "Chinese (Mandarin)", false, "han", "pinyin", "latin");
        add("ko", "Korean", false, "hangul", "romaja");
        add("ar", "Arabic", false, "arabic", "latin");
        add("hi", "Hindi", false, "devanagari", "latin");
        add("tr", "Turkish", false, "latin", "ipa");
        add("vi", "Vietnamese", false, "latin", "ipa");
        add("th", "Thai", false, "thai", "latin");
        add("he", "Hebrew", false, "hebrew", "latin");
        add("id", "Indonesian", false, "latin", "ipa");
        add("fa", "Persian", false, "arabic", "latin");
    }

    /** Every supported language code, in declaration order. */
    public static final List<String> ALL_CODES = List.copyOf(BY_CODE.keySet());

    /** Codes sensible as a native/companion language, most common first. */
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
