package com.ma.languagelearning.client;

import com.ma.languagelearning.MaLanguageLearning;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Key bindings. One key opens the settings screen; another lets the player re-record the
 * current word if their microphone was noisy.
 */
public final class KeyMappings {

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(MaLanguageLearning.MODID, "main"));

    public static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.malanguagelearning.settings",
            InputConstants.Type.KEYBOARD,
            0,
            CATEGORY);

    public static final KeyMapping RETRY_WORD = new KeyMapping(
            "key.malanguagelearning.retry",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R,
            CATEGORY);

    private KeyMappings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_SETTINGS);
        event.register(RETRY_WORD);
    }

    /** Poll the bindings; call once per client tick. */
    public static void poll() {
        while (OPEN_SETTINGS.consumeClick()) {
            ClientBootstrap.openSettings();
        }
        while (RETRY_WORD.consumeClick()) {
            var mgr = ClientBootstrap.challenges();
            if (mgr != null && (mgr.isActive() || !mgr.queueIsEmpty())) {
                mgr.retry();
            }
        }
    }
}
