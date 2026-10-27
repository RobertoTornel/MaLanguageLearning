package com.ma.languagelearning.ui;

import com.ma.languagelearning.MaLanguageLearning;
import com.ma.languagelearning.config.MllConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Audible feedback for a challenge result.
 *
 * <p>Uses amethyst block sounds because they are short, pitched, and already part of the
 * vanilla palette — no custom assets, and they cut through game noise without being
 * obnoxious. The pass is the bright "hit" chime; the fail is the deeper "break", played
 * quieter so it reads as a nudge rather than a punishment.</p>
 */
public final class ChallengeSounds {

    /** Volume for a pass. */
    private static final float PASS_VOLUME = 0.9f;
    /** Volume for a fail — deliberately lower; the player already lost the item. */
    private static final float FAIL_VOLUME = 0.45f;

    private ChallengeSounds() {
    }

    /** Play the pass chime. Safe to call from the tick/render thread. */
    public static void passed() {
        play(SoundEvents.AMETHYST_BLOCK_HIT, PASS_VOLUME, 1.6f);
    }

    /** Play the fail tone, quieter than the pass. */
    public static void failed() {
        play(SoundEvents.AMETHYST_BLOCK_BREAK, FAIL_VOLUME, 0.8f);
    }

    /** Play a short beep when TTS is unavailable (e.g. no voice for this language).
     *  Safe to call from any thread. */
    public static void beep() {
        play(SoundEvents.AMETHYST_BLOCK_HIT, 0.6f, 2.0f);
    }

    private static void play(SoundEvent sound, float volume, float pitch) {
        try {
            MllConfig cfg = MllConfig.get();
            if (!cfg.enabled || !cfg.resultSounds) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            // Entity-bound so it plays at the player's ears, not at a world position that
            // could be far away or in another dimension.
            level.playLocalSound(mc.player, sound, SoundSource.PLAYERS, volume, pitch);
        } catch (Exception e) {
            MaLanguageLearning.LOGGER.debug("[{}] could not play result sound",
                    MaLanguageLearning.MODID, e);
        }
    }
}
