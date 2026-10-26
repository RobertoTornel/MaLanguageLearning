package com.ma.languagelearning.game;

import net.minecraft.world.item.ItemStack;

/**
 * Normalises item stacks into the spellable word the player must say.
 *
 * <p>Words come from Minecraft's own official translations, so the mod works for every
 * item in the game and every language the game ships without a curated dictionary.</p>
 */
public interface WordResolver {

    /**
     * The word for this stack in the configured target language.
     *
     * @param stack the stack being learned
     * @return the display/pronunciation word, or null when the item should be skipped
     */
    String wordFor(ItemStack stack);

    /** Stable registry id used as the ledger key, e.g. {@code "minecraft:cobblestone"}. */
    String keyFor(ItemStack stack);
}
