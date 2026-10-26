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
    private record Entry(String word, long learnedAt) {
    }

    private final Map<String, Entry> words = new LinkedHashMap<>();

    private boolean loaded;
    private boolean dirty;
    private long lastSaveMs;
    private Path pathOverride;

    public LedgerStore() {
    }

    /** Test seam: use a non-default file instead of {@link MllConfig#dataDir()}. */
    LedgerStore(Path path) {
        this.pathOverride = path;
    }

    // ------------------------------------------------------------------ public API

    @Override
    public synchronized boolean isLearned(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return false;
        }
        ensureLoaded();
        return words.containsKey(itemKey);
    }

    @Override
    public synchronized boolean markLearned(String itemKey, String word, long epochMillis) {
        if (itemKey == null || itemKey.isBlank()) {
            return false;
        }
        ensureLoaded();
        Entry previous = words.get(itemKey);
        if (previous != null) {
            // Already learned: keep the original first-success timestamp, only refresh the
            // recorded spelling when the old one was blank.
            if (previous.word() == null || previous.word().isBlank()) {
                words.put(itemKey, new Entry(word, epochMillis));
                dirty = true;
                maybeSave(epochMillis);
            }
            maybeSave(epochMillis);
            return false;
        }
        words.put(itemKey, new Entry(word, epochMillis));
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

    private Path path() {
        return pathOverride != null ? pathOverride : MllConfig.dataDir().resolve("learned.json");
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
                words.put(wordEntry.getKey(), new Entry(word, learnedAt));
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
