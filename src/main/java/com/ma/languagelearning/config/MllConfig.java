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
     * Minimum speech-recognition confidence for a pass, 0..1, or {@code -1} to skip the
     *  confidence gate entirely (text match alone decides).
     *
     * <p>Measured on real parakeet-v3 output, a correctly spoken short word lands around
     * 0.4-0.9 depending on length and clarity — "dirt" scored 0.40 while "apple" scored
     * 0.99. A blanket 0.92 therefore rejects most honest answers. The default is lenient
     * because the exact word match (Levenshtein tolerance + alternatives) does the real
     * discrimination; confidence is only a coarse second gate. Raise it for stricter
     * marking, or set to -1 to disable the gate.</p>
     */
    public float minConfidence = 0.25f;
    /**
     * How a learned word is remembered.
     *
     * <ul>
     *   <li>{@code "cooldown"} — remembered until {@link #memoryResetSeconds} of <em>not
     *       picking that item up</em> passes, then it is asked again. Picking it up resets
     *       the clock, so a chest you keep visiting stays quiet while a one-off stays learned
     *       only until you have left it alone long enough.</li>
     *   <li>{@code "session"} — remembered until the game is restarted.</li>
     *   <li>{@code "permanent"} — never asked again.</li>
     * </ul>
     */
    public String memoryMode = "cooldown";
    /** Seconds of not picking an item up before it must be said again, in cooldown mode. */
    public int memoryResetSeconds = 270;
    /** Remember progress per world/save instead of globally. */
    public boolean perWorldMemory = true;
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
