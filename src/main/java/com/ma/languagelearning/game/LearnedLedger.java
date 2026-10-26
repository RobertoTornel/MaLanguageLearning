package com.ma.languagelearning.game;

import java.util.List;

/**
 * Tracks which words the player has already spoken correctly.
 *
 * <p>Keyed by item registry id, deliberately NOT by translation key: {@code stone} and
 * {@code stone_slab} are different words and both must be said, while re-acquiring the
 * exact same item never asks twice.</p>
 */
public interface LearnedLedger {

    /** True when the player has already pronounced the word for this item correctly. */
    boolean isLearned(String itemKey);

    /** Record a successful pronunciation. Returns false if it was already learned. */
    boolean markLearned(String itemKey, String word, long epochMillis);

    /**
     * Record that the player picked this item up.
     *
     * <p>Cooldown mode counts from the last pickup, so touching an item again restarts its
     * quiet period. Ignored for items that are not learned yet.</p>
     */
    void markSeen(String itemKey, long epochMillis);

    /** Select the world whose progress applies, or null when not in one. */
    void setWorld(String name);

    /** Forget everything, for session mode's "the game restarted" reset. */
    void clearSession();

    /** The word recorded for this item, or null. */
    String wordOf(String itemKey);

    /** All learned item keys, for the stats screen. */
    List<String> learnedKeys();

    /** Number of learned items. */
    int size();

    /** Persist to disk immediately. */
    void save();

    /** Drop all progress (used by the "reset my progress" button). */
    void reset();
}
