package com.ma.languagelearning.game;

import com.ma.languagelearning.config.MllConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three memory modes.
 *
 * <p>Regression guard: "it doesn't remember that I picked up items before" was a report
 * where the ledger silently forgot everything, so each mode is pinned down here.</p>
 */
class LedgerMemoryModeTest {

    @TempDir
    Path tempDir;

    private MllConfig cfg;
    private String originalMode;
    private int originalMinutes;

    @BeforeEach
    void captureConfig() {
        cfg = MllConfig.get();
        originalMode = cfg.memoryMode;
        originalMinutes = cfg.memoryResetMinutes;
    }

    @AfterEach
    void restoreConfig() {
        cfg.memoryMode = originalMode;
        cfg.memoryResetMinutes = originalMinutes;
    }

    @Test
    @DisplayName("permanent mode remembers a word indefinitely")
    void permanentRemembers() {
        cfg.memoryMode = "permanent";
        LedgerStore ledger = new LedgerStore(tempDir.resolve("learned.json"));

        assertFalse(ledger.isLearned("minecraft:dirt"));
        assertTrue(ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis()));
        assertTrue(ledger.isLearned("minecraft:dirt"));
    }

    @Test
    @DisplayName("off mode never remembers, so every pickup asks again")
    void offNeverRemembers() {
        cfg.memoryMode = "off";
        LedgerStore ledger = new LedgerStore(tempDir.resolve("learned.json"));

        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertFalse(ledger.isLearned("minecraft:dirt"),
                "off mode must not treat anything as known");
    }

    @Test
    @DisplayName("dynamic mode forgets a word once the window passes")
    void dynamicExpires() {
        cfg.memoryMode = "dynamic";
        cfg.memoryResetMinutes = 30;
        LedgerStore ledger = new LedgerStore(tempDir.resolve("learned.json"));

        // Learned just now: still remembered.
        ledger.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        assertTrue(ledger.isLearned("minecraft:dirt"));

        // Simulate the same entry being two hours old by rewriting it with an old stamp.
        LedgerStore aged = new LedgerStore(tempDir.resolve("aged.json"));
        aged.markLearned("minecraft:stone", "石",
                System.currentTimeMillis() - 2L * 60L * 60L * 1000L);
        assertFalse(aged.isLearned("minecraft:stone"),
                "a word older than the window must be asked again");
    }

    @Test
    @DisplayName("derivatives stay distinct items")
    void derivativesAreSeparate() {
        cfg.memoryMode = "permanent";
        LedgerStore ledger = new LedgerStore(tempDir.resolve("learned.json"));

        ledger.markLearned("minecraft:cobblestone", "cobblestone", System.currentTimeMillis());
        // Learning cobblestone must not exempt its slab or stairs.
        assertFalse(ledger.isLearned("minecraft:cobblestone_slab"));
        assertFalse(ledger.isLearned("minecraft:cobblestone_stairs"));
    }

    @Test
    @DisplayName("learned words survive a reload from disk")
    void persistsAcrossReload() {
        cfg.memoryMode = "permanent";
        Path file = tempDir.resolve("learned.json");

        LedgerStore first = new LedgerStore(file);
        first.markLearned("minecraft:dirt", "土", System.currentTimeMillis());
        first.save();

        LedgerStore reloaded = new LedgerStore(file);
        assertTrue(reloaded.isLearned("minecraft:dirt"));
    }
}
