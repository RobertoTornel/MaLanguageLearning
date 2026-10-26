package com.ma.languagelearning.game;

/**
 * Outcome of a learning challenge.
 */
public enum ChallengeOutcome {
    /** The player pronounced the word correctly within the time limit. */
    PASSED,
    /** The time limit expired, or the player gave up. */
    FAILED,
    /** No microphone or engine was usable; nothing was enforced. */
    SKIPPED,
    /** The challenge was superseded (inventory reopened, world left, mod disabled). */
    CANCELLED
}
