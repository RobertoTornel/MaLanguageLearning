package com.ma.languagelearning.game;

import com.ma.languagelearning.config.MllConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three memory modes and per-world storage.
 *
 * <p>Regression guard: "it doesn't remember that I picked up items before" was a report
 * where the ledger silently forgot everything, so each mode is pinned down here.</p>
 */
class LedgerMemoryModeTest {

    private static final long MINUTE = 60_000L;

    @TempDir
    Path tempDir;

    private MllConfig cfg;
    private String originalMode;
    private int originalSeconds;
    private boolean originalPerWorld;

    @BeforeEach
    void captureConfig() {
        cfg = MllConfig.get();
        originalMode = cfg.memoryMode;
        originalSeconds = cfg.memoryResetSeconds;
        originalPerWorld = cfg.perWorldMemory;
        // Most tests drive the file path directly, so per-world must not interfere.
        cfg.perWorldMemory = false;
    }

    @AfterEach
    void restoreConfig() {
        cfg.memoryMode = originalMode;
        cfg.memoryResetSeconds = originalSeconds;
        cfg.perWorldMemory = originalPerWorld;
    }

    private LedgerStore ledger(String name) {
        return new LedgerStore(tempDir.resolve(name));
    }

    // ------------------------------------------------------------------ permanent

    @Test
    @DisplayName("permanent mode never asks again")
    void permanentRemembers() {
        cfg.memoryMode = "permanent";
        LedgerStore ledger = ledger("permanent.json");

        assertFalse(ledger.isLearned("minecraft:dirt"));
        assertTrue(ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis()));
        assertTrue(ledger.isLearned("minecraft:dirt"));
    }

    // ------------------------------------------------------------------ session

    @Test
    @DisplayName("session mode remembers until the game restarts")
    void sessionRemembersUntilCleared() {
        cfg.memoryMode = "session";
        LedgerStore ledger = ledger("session.json");

        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertTrue(ledger.isLearned("minecraft:dirt"), "must still be known in this session");

        ledger.clearSession();
        assertFalse(ledger.isLearned("minecraft:dirt"),
                "a restart must make previously learned words ask again");
    }

    @Test
    @DisplayName("session mode still registers a word that already exists on disk")
    void sessionLearnsWordAlreadyOnDisk() {
        cfg.memoryMode = "permanent";
        Path file = tempDir.resolve("carried.json");
        LedgerStore prior = new LedgerStore(file);
        prior.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        prior.save();

        // Restart in session mode: the old entry must not count, but saying it must.
        cfg.memoryMode = "session";
        LedgerStore ledger = new LedgerStore(file);
        assertFalse(ledger.isLearned("minecraft:dirt"), "last session's words do not count");

        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertTrue(ledger.isLearned("minecraft:dirt"),
                "a word said this session must count even if it was already on disk");
    }

    // ------------------------------------------------------------------ cooldown

    @Test
    @DisplayName("cooldown mode forgets after the quiet window")
    void cooldownExpires() {
        cfg.memoryMode = "cooldown";
        cfg.memoryResetSeconds = 270;
        LedgerStore ledger = ledger("cooldown.json");

        // Learned 5 minutes ago and not seen since: window has passed.
        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis() - 5 * MINUTE);
        assertFalse(ledger.isLearned("minecraft:dirt"),
                "270s of not picking it up must make it ask again");
    }

    @Test
    @DisplayName("picking the item up again restarts the cooldown")
    void cooldownResetsOnPickup() {
        cfg.memoryMode = "cooldown";
        cfg.memoryResetSeconds = 270;
        LedgerStore ledger = ledger("resets.json");

        // Learned five minutes ago, which on its own would have expired.
        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis() - 5 * MINUTE);
        ledger.markSeen("minecraft:dirt", System.currentTimeMillis());

        // Seeing it just now restarts the clock, so it is known again.
        assertTrue(ledger.isLearned("minecraft:dirt"),
                "picking the item up must restart its quiet period");
    }

    @Test
    @DisplayName("a fresh pickup inside the window stays learned")
    void cooldownWithinWindow() {
        cfg.memoryMode = "cooldown";
        cfg.memoryResetSeconds = 270;
        LedgerStore ledger = ledger("within.json");

        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        ledger.markSeen("minecraft:dirt", System.currentTimeMillis());
        assertTrue(ledger.isLearned("minecraft:dirt"));
    }

    @Test
    @DisplayName("markSeen on an unknown item does not mark it learned")
    void markSeenDoesNotLearn() {
        cfg.memoryMode = "cooldown";
        LedgerStore ledger = ledger("seenonly.json");

        ledger.markSeen("minecraft:stone", System.currentTimeMillis());
        assertFalse(ledger.isLearned("minecraft:stone"),
                "seeing an item must not count as having said its word");
    }

    // ------------------------------------------------------------------ identity

    @Test
    @DisplayName("derivatives stay distinct items")
    void derivativesAreSeparate() {
        cfg.memoryMode = "permanent";
        LedgerStore ledger = ledger("derivatives.json");

        ledger.markLearned("minecraft:cobblestone", "cobblestone", System.currentTimeMillis());
        assertFalse(ledger.isLearned("minecraft:cobblestone_slab"));
        assertFalse(ledger.isLearned("minecraft:cobblestone_stairs"));
    }

    @Test
    @DisplayName("learned words survive a reload from disk")
    void persistsAcrossReload() {
        cfg.memoryMode = "permanent";
        Path file = tempDir.resolve("reload.json");

        LedgerStore first = new LedgerStore(file);
        first.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        first.save();

        LedgerStore reloaded = new LedgerStore(file);
        assertTrue(reloaded.isLearned("minecraft:dirt"));
    }

    @Test
    @DisplayName("a zero window asks every time")
    void zeroWindowAlwaysAsks() {
        cfg.memoryMode = "cooldown";
        cfg.memoryResetSeconds = 0;
        LedgerStore ledger = ledger("zero.json");

        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertFalse(ledger.isLearned("minecraft:dirt"),
                "a zero cooldown must behave like 'never remember'");
    }

    // ------------------------------------------------------------------ per world

    @Test
    @DisplayName("per-world mode keeps separate progress for separate saves")
    void perWorldSeparatesProgress() {
        cfg.memoryMode = "permanent";
        cfg.perWorldMemory = true;

        // Redirect the per-world directory into the temp dir so the test never touches
        // the real config folder.
        LedgerStore ledger = new LedgerStore(tempDir, true);

        ledger.setWorld("Survival World");
        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertTrue(ledger.isLearned("minecraft:dirt"));

        // A different save starts clean: the word must be asked again there.
        ledger.setWorld("Test World");
        assertFalse(ledger.isLearned("minecraft:dirt"),
                "progress from one world must not leak into another");

        // Switching back restores the original world's progress.
        ledger.setWorld("Survival World");
        assertTrue(ledger.isLearned("minecraft:dirt"),
                "returning to a world must restore its own progress");
    }

    @Test
    @DisplayName("global mode ignores the world")
    void globalModeSharesProgress() {
        cfg.memoryMode = "permanent";
        cfg.perWorldMemory = false;

        LedgerStore ledger = new LedgerStore(tempDir.resolve("global.json"));
        ledger.setWorld("World A");
        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());

        ledger.setWorld("World B");
        assertTrue(ledger.isLearned("minecraft:dirt"),
                "with per-world memory off, progress is shared");
    }

    @Test
    @DisplayName("the defaults are cooldown mode with a 270 second window")
    void defaultsAreCooldown270() {
        MllConfig fresh = new MllConfig();
        assertEquals("cooldown", fresh.memoryMode);
        assertEquals(270, fresh.memoryResetSeconds);
    }
}
