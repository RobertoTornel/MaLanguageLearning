package com.ma.languagelearning.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ma.languagelearning.MaLanguageLearning;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted user settings for MaLanguageLearning.
 *
 * <p>Stored as pretty-printed JSON at {@code config/malanguagelearning/config.json}.</p>
 */
public final class MllConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MllConfig INSTANCE;

    // ---- speech recognition -------------------------------------------------
    /** BCP-47 language tag the player is learning, e.g. {@code "ja"} or {@code "de"}. */
    public String targetLanguage = "ja";
    /** Secondary/companion translation language shown under the primary, e.g. {@code "en"}. */
    public String nativeLanguage = "en";

    // ---- script presentation ------------------------------------------------
    /** Script used for the PRIMARY line, e.g. {@code "hiragana"}, {@code "kanji"}, {@code "romaji"}. */
    public String primaryScript = "hiragana";
    /** Script used for the SECONDARY line, e.g. {@code "romaji"}, {@code "ipa"}. */
    public String secondaryScript = "romaji";
    /** Show IPA/phonetic transcription under the word. */
    public boolean showPhonetics = true;

    // ---- gameplay -----------------------------------------------------------
    /** Seconds the player has to speak the word after closing the inventory. */
    public int answerSeconds = 15;
    /** Drop the item to the floor when the player fails. */
    public boolean dropOnFailure = true;
    /** Speak the word aloud (TTS) when the player fails, before dropping. */
    public boolean speakWordOnFailure = true;
    /** Only require words for items the player has never said before. */
    public boolean learnOncePerItem = true;
    /** Play a chime on a pass and a softer tone on a fail. */
    public boolean resultSounds = true;
    /**
     * Quiet gap between two challenges, in seconds.
     *
     * <p>Picking up a stack of blocks produces several new items at once. Without a pause
     * they chain back to back and the player is stuck answering for minutes, so this puts
     * breathing room between prompts.</p>
     */
    public int globalCooldownSeconds = 4;
    /**
     * How long before the same item may be asked again, in seconds.
     *
     * <p>Stops one item from monopolising the flow when it is picked up repeatedly, which
     * is what made a burst of stone feel like the mod was stuck in a loop.</p>
     */
    public int itemCooldownSeconds = 90;
    /**
     * Minimum speech-recognition confidence for a pass, 0..1.
     *
     * <p>Measured on real parakeet-v3 output, a correctly spoken short word lands around
     * 0.4-0.9 depending on length and clarity — "dirt" scored 0.40 while "apple" scored
     * 0.99. A blanket 0.92 therefore rejects most honest answers, so the default is
     * lenient and the exact word match does the real work. Raise it for stricter marking.</p>
     */
    public float minConfidence = 0.55f;
    /** Reject a match when the transcript is this much longer than the target word. */
    public boolean strictMatching = false;
    /**
     * How learned words are remembered.
     *
     * <ul>
     *   <li>{@code "permanent"} — once learned, never asked again (the old behaviour).</li>
     *   <li>{@code "off"} — nothing is remembered; every pickup asks again.</li>
     *   <li>{@code "dynamic"} — remembered, but forgotten after {@link #memoryResetMinutes}
     *       so previously learned words come back for review.</li>
     * </ul>
     */
    public String memoryMode = "dynamic";
    /** Minutes a learned word is remembered in {@code "dynamic"} mode. */
    public int memoryResetMinutes = 30;
    /** Ignore items whose names are shorter than this (avoids one-letter noise). */
    public int minWordLength = 2;
    /** Item registry ids that are always exempt (e.g. {@code "minecraft:stone"}). */
    public java.util.List<String> ignoredItems = new java.util.ArrayList<>();

    // ---- engine -------------------------------------------------------------
    /** {@code "cpu"} or {@code "gpu"}; passed to the sidecar and its ORT provider. */
    public String inferenceDevice = "cpu";
    /** Preferred STT backend id; {@code "auto"} picks per language. */
    public String sttBackend = "auto";
    /** Local sidecar port. */
    public int sidecarPort = 8737;
    /** Master switch for the whole mechanic. */
    public boolean enabled = true;
    /** Play the TTS prompt automatically when a word appears. */
    public boolean announceNewWord = false;

    // ---- first-run consent --------------------------------------------------
    /** Set once the user has accepted the model-download / hardware-cost warning. */
    public boolean consentAccepted = false;
    /** Version of the consent text the user accepted (bumped when the warning changes). */
    public int consentVersion = 0;

    // -------------------------------------------------------------------------

    public static MllConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new MllConfig();
        }
        return INSTANCE;
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(MaLanguageLearning.MODID).resolve("config.json");
    }

    public void load() {
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                MllConfig loaded = GSON.fromJson(json, MllConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                    if (INSTANCE.ignoredItems == null) {
                        INSTANCE.ignoredItems = new java.util.ArrayList<>();
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            MaLanguageLearning.LOGGER.error("[{}] failed to read config, using defaults", MaLanguageLearning.MODID, e);
        }
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MaLanguageLearning.LOGGER.error("[{}] failed to write config", MaLanguageLearning.MODID, e);
        }
    }

    /** Directory where the sidecar, models and caches live. */
    public static Path dataDir() {
        return FMLPaths.CONFIGDIR.get().resolve(MaLanguageLearning.MODID);
    }
}
