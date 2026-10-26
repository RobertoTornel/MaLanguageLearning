package com.ma.languagelearning.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ma.languagelearning.config.MllConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON-backed {@link LearnedLedger}.
 *
 * <p>File layout ({@code config/malanguagelearning/learned.json}):</p>
 * <pre>
 * {
 *   "version": 1,
 *   "words": {
 *     "minecraft:cobblestone": { "word": "cobblestone", "learned_at": 1234567890 }
 *   }
 * }
 * </pre>
 *
 * <p>Every public method synchronises on this instance, so the class is safe to use from
 * the client tick thread and from the speech worker threads. Loading happens lazily on the
 * first access; {@link #markLearned} debounces disk writes to at most one per
 * {@link #SAVE_DEBOUNCE_MS}, with {@link #save()} forcing an immediate flush.</p>
 */
public final class LedgerStore implements LearnedLedger {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk schema version; bump when the layout changes. */
    private static final int SCHEMA_VERSION = 1;
    /** Minimum gap between debounced writes. */
    private static final long SAVE_DEBOUNCE_MS = 2_000L;

    /** One learned word as stored on disk. */
    /**
     * One learned word.
     *
     * @param word      the spelling that was accepted
     * @param learnedAt when it was last spoken correctly
     * @param seenAt    when the item was last picked up; cooldown mode counts from here, so
     *                  picking an item up again restarts its quiet period
     */
    private record Entry(String word, long learnedAt, long seenAt) {
    }

    private final Map<String, Entry> words = new LinkedHashMap<>();

    private boolean loaded;
    private boolean dirty;
    /** Current world id (save folder name), or null when not in a world. */
    private String worldName;
    /**
     * Words said correctly during this process.
     *
     * <p>Session mode is defined by the process lifetime, so it cannot be stored on disk —
     * a file would survive the restart it is supposed to forget. This set is the truth in
     * that mode.</p>
     */
    private final java.util.Set<String> sessionLearned = new java.util.HashSet<>();
    /** Base directory for per-world files; null means the real config dir. */
    private Path worldBaseOverride;
    /** Path the currently loaded data came from, so a world switch can reload. */
    private Path loadedFrom;
    private long lastSaveMs;
    private Path pathOverride;

    public LedgerStore() {
    }

    /** Test seam: use a non-default file instead of {@link MllConfig#dataDir()}. */
    LedgerStore(Path path) {
        this.pathOverride = path;
    }

    /** Test seam: use a non-default directory for per-world files. */
    LedgerStore(Path worldBase, boolean perWorldOnly) {
        this.worldBaseOverride = worldBase;
    }

    // ------------------------------------------------------------------ public API

    @Override
    public synchronized boolean isLearned(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return false;
        }
        MllConfig cfg = MllConfig.get();
        String mode = cfg.memoryMode == null ? "cooldown" : cfg.memoryMode.toLowerCase(Locale.ROOT);

        ensureLoaded();
        Entry entry = words.get(itemKey);
        if (entry == null) {
            return false;
        }

        switch (mode) {
            case "permanent" -> {
                return true;
            }
            case "session" -> {
                // Only what was said since this process started counts.
                return sessionLearned.contains(itemKey);
            }
            case "off" -> {
                // Legacy value from before the three-mode system: "never remember".
                return false;
            }
            case "cooldown", "dynamic" -> {
                if (cfg.memoryResetSeconds <= 0) {
                    // A zero window means ask every time, which is what "off" used to mean.
                    return false;
                }
                long windowMillis = cfg.memoryResetSeconds * 1000L;
                long anchor = entry.seenAt() > 0 ? entry.seenAt() : entry.learnedAt();
                if (System.currentTimeMillis() - anchor > windowMillis) {
                    // Quiet for long enough: ask again, and make the file agree.
                    words.remove(itemKey);
                    dirty = true;
                    maybeSave(System.currentTimeMillis());
                    return false;
                }
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /**
     * Record that the item was picked up.
     *
     * <p>In cooldown mode this restarts the clock, so an item you keep touching stays quiet
     * while one you have not seen for a while is asked again.</p>
     */
    @Override
    public synchronized void markSeen(String itemKey, long epochMillis) {
        if (itemKey == null || itemKey.isBlank()) {
            return;
        }
        Entry entry = words.get(itemKey);
        if (entry == null) {
            // Not learned yet: nothing to refresh, and adding an entry here would wrongly
            // mark it as known.
            return;
        }
        words.put(itemKey, new Entry(entry.word(), entry.learnedAt(), epochMillis));
        dirty = true;
        maybeSave(epochMillis);
    }

    @Override
    public synchronized void clearSession() {
        // Session mode forgets everything when the game starts. Only the in-memory set is
        // authoritative, so clearing that is enough; the word list stays as a record of
        // what was learned, which matters if the mode is changed mid-run.
        sessionLearned.clear();
        if (words.isEmpty()) {
            return;
        }
        words.clear();
        dirty = true;
        writeToDisk();
    }

    @Override
    public synchronized boolean markLearned(String itemKey, String word, long epochMillis) {
        if (itemKey == null || itemKey.isBlank()) {
            return false;
        }
        ensureLoaded();
        Entry previous = words.get(itemKey);
        if (previous != null) {
            // Already known on disk. In session mode that is not enough — the word must have
            // been said since this process started — so record it now that it has been.
            sessionLearned.add(itemKey);
            // Keep the original first-success timestamp; only refresh a blank spelling.
            if (previous.word() == null || previous.word().isBlank()) {
                words.put(itemKey, new Entry(word, epochMillis, epochMillis));
                dirty = true;
                maybeSave(epochMillis);
            }
            maybeSave(epochMillis);
            return false;
        }
        words.put(itemKey, new Entry(word, epochMillis, epochMillis));
        sessionLearned.add(itemKey);
        dirty = true;
        maybeSave(epochMillis);
        return true;
    }

    @Override
    public synchronized String wordOf(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return null;
        }
        ensureLoaded();
        Entry entry = words.get(itemKey);
        return entry == null ? null : entry.word();
    }

    @Override
    public synchronized List<String> learnedKeys() {
        ensureLoaded();
        return new ArrayList<>(words.keySet());
    }

    @Override
    public synchronized int size() {
        ensureLoaded();
        return words.size();
    }

    @Override
    public synchronized void save() {
        ensureLoaded();
        if (!dirty && Files.exists(path())) {
            return;
        }
        writeToDisk();
    }

    @Override
    public synchronized void reset() {
        words.clear();
        loaded = true;
        dirty = true;
        writeToDisk();
        lastSaveMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ internals

    /** Called when the current challenge is abandoned; forgets a word learnt only this session. */
    public synchronized void forget(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return;
        }
        ensureLoaded();
        if (words.remove(itemKey) != null) {
            dirty = true;
            maybeSave(System.currentTimeMillis());
        }
    }

    /**
     * Switch the active world.
     *
     * <p>Progress is per world by default: learning stone in one save should not exempt it in
     * another, and a creative test world should not pollute a survival one. Passing a name
     * that differs from the loaded one drops the in-memory copy and reloads from that world's
     * file.</p>
     */
    @Override
    public synchronized void setWorld(String name) {
        String normalised = name == null || name.isBlank() ? null : name;
        if (java.util.Objects.equals(normalised, worldName) && loaded) {
            return;
        }
        // Persist whatever we have before switching, or the previous world loses progress.
        if (loaded && dirty) {
            writeToDisk();
        }
        worldName = normalised;
        loaded = false;
        words.clear();
        loadedFrom = null;
    }

    private Path path() {
        if (pathOverride != null) {
            return pathOverride;
        }
        MllConfig cfg = MllConfig.get();
        if (cfg.perWorldMemory && worldName != null && !worldName.isBlank()) {
            // One file per save, under a subfolder so listings stay readable and a world
            // name cannot escape the directory.
            String safe = sanitise(worldName);
            Path base = worldBaseOverride != null ? worldBaseOverride : MllConfig.dataDir();
            return base.resolve("worlds").resolve(safe + ".json");
        }
        Path base = worldBaseOverride != null ? worldBaseOverride : MllConfig.dataDir();
        return base.resolve("learned.json");
    }

    /** Reduce a world name to something safe to use as a file name. */
    private static String sanitise(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ';
            out.append(ok ? c : '_');
        }
        String trimmed = out.toString().trim();
        if (trimmed.isEmpty()) {
            return "world";
        }
        // Windows rejects leading/trailing dots and spaces and these are also awkward on Linux.
        trimmed = trimmed.replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        return trimmed.isEmpty() ? "world" : trimmed;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = path();
        if (!Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                LOG.warn("[malanguagelearning] learned.json is not an object, starting empty");
                return;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement wordsElement = object.get("words");
            if (wordsElement == null || !wordsElement.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> wordEntry : wordsElement.getAsJsonObject().entrySet()) {
                JsonElement value = wordEntry.getValue();
                if (value == null || !value.isJsonObject()) {
                    continue;
                }
                JsonObject record = value.getAsJsonObject();
                String word = record.has("word") && !record.get("word").isJsonNull()
                        ? record.get("word").getAsString()
                        : "";
                long learnedAt = record.has("learned_at") && !record.get("learned_at").isJsonNull()
                        ? record.get("learned_at").getAsLong()
                        : 0L;
                long seenAt = record.has("seen_at") && !record.get("seen_at").isJsonNull()
                        ? record.get("seen_at").getAsLong()
                        : learnedAt;
                words.put(wordEntry.getKey(), new Entry(word, learnedAt, seenAt));
            }
            LOG.debug("[malanguagelearning] ledger loaded: {} words", words.size());
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to read learned.json, starting empty", e);
            words.clear();
        }
    }

    private void maybeSave(long nowMs) {
        if (!dirty) {
            return;
        }
        if (nowMs - lastSaveMs < SAVE_DEBOUNCE_MS) {
            return;
        }
        writeToDisk();
    }

    private void writeToDisk() {
        Path path = path();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonObject wordsObject = new JsonObject();
            for (Map.Entry<String, Entry> entry : words.entrySet()) {
                JsonObject record = new JsonObject();
                record.addProperty("word", entry.getValue().word());
                record.addProperty("learned_at", entry.getValue().learnedAt());
                record.addProperty("seen_at", entry.getValue().seenAt());
                wordsObject.add(entry.getKey(), record);
            }

            JsonObject root = new JsonObject();
            root.addProperty("version", SCHEMA_VERSION);
            root.add("words", wordsObject);

            // Write to a sibling temp file and move it into place so a crash mid-write cannot
            // leave a truncated ledger behind.
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }

            dirty = false;
            lastSaveMs = System.currentTimeMillis();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to write learned.json", e);
        }
    }
}
