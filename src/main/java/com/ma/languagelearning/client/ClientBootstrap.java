package com.ma.languagelearning.client;

import com.ma.languagelearning.MaLanguageLearning;
import com.ma.languagelearning.audio.AudioCapture;
import com.ma.languagelearning.audio.JavaSoundCapture;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.game.AcquisitionTracker;
import com.ma.languagelearning.game.ChallengeManager;
import com.ma.languagelearning.game.ItemNameResolver;
import com.ma.languagelearning.game.LedgerStore;
import com.ma.languagelearning.net.SidecarClient;
import com.ma.languagelearning.net.SidecarProvisioner;
import com.ma.languagelearning.speech.SttClient;
import com.ma.languagelearning.speech.TranslationProvider;
import com.ma.languagelearning.ui.ChallengeOverlay;
import com.ma.languagelearning.ui.ConsentScreen;
import com.ma.languagelearning.ui.MllSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side wiring. Owns the singletons and translates game events into calls on the
 * challenge manager.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBootstrap {

    private static final Identifier LAYER_ID =
            Identifier.fromNamespaceAndPath(MaLanguageLearning.MODID, "challenge");

    private static LedgerStore ledger;
    private static ItemNameResolver resolver;
    private static SttClient stt;
    private static TranslationProvider translations;
    private static AudioCapture mic;
    private static ChallengeManager challenges;
    private static AcquisitionTracker tracker;
    private static SidecarProvisioner provisioner;
    private static boolean initialised;

    private ClientBootstrap() {
    }

    /** Called from the mod constructor on the client. */
    public static void init(IEventBus modBus) {
        MaLanguageLearning.LOGGER.info("[{}] client bootstrap", MaLanguageLearning.MODID);

        modBus.addListener(ClientBootstrap::onRegisterGuiLayers);
        modBus.addListener(ClientBootstrap::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onScreenClosing);
    }

    /**
     * Lazily build the engine stack. Deferred until first use so the game starts even when
     * the sidecar is missing or the user declined the consent screen.
     */
    private static synchronized void ensureInitialised() {
        if (initialised) {
            return;
        }
        try {
            MllConfig cfg = MllConfig.get();

            ledger = new LedgerStore();
            resolver = new ItemNameResolver();
            stt = new SidecarClient();
            translations = (TranslationProvider) stt;
            mic = new JavaSoundCapture();

            challenges = new ChallengeManager(ledger, resolver, stt, translations, mic);
            tracker = new AcquisitionTracker(resolver, ledger);

            provisioner = new SidecarProvisioner();
            if (cfg.consentAccepted) {
                provisioner.ensureReady();
            }

            initialised = true;
            MaLanguageLearning.LOGGER.info("[{}] engine ready", MaLanguageLearning.MODID);
        } catch (Throwable t) {
            MaLanguageLearning.LOGGER.error("[{}] failed to initialise engine", MaLanguageLearning.MODID, t);
        }
    }

    public static ChallengeManager challenges() {
        ensureInitialised();
        return challenges;
    }

    public static SidecarProvisioner provisioner() {
        ensureInitialised();
        return provisioner;
    }

    // ------------------------------------------------------------------ events

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, (graphics, delta) -> {
            MllConfig cfg = MllConfig.get();
            if (!cfg.enabled || !cfg.consentAccepted) {
                return;
            }
            ChallengeManager mgr = challenges();
            if (mgr != null) {
                ChallengeOverlay.render(graphics, delta, mgr);
            }
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        KeyMappings.register(event);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        MllConfig cfg = MllConfig.get();
        if (!cfg.enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }

        ensureInitialised();

        // First-run consent gate: show it once, before anything is downloaded.
        if (!cfg.consentAccepted && cfg.consentVersion < ConsentScreen.CURRENT_VERSION && mc.gui.screen() == null) {
            mc.gui.setScreen(new ConsentScreen(null));
            cfg.consentVersion = ConsentScreen.CURRENT_VERSION;
            cfg.save();
            return;
        }

        KeyMappings.poll();

        if (challenges != null) {
            challenges.tick();
        }

        if (tracker != null && challenges != null) {
            tracker.tick();
            for (String key : tracker.pollNewItemKeys()) {
                net.minecraft.world.item.ItemStack stack = tracker.stackForKey(key);
                String word = resolver.wordFor(stack);
                if (word != null) {
                    challenges.queue(key, word, stack);
                }
            }
        }
    }

    private static void onScreenClosing(ScreenEvent.Closing event) {
        MllConfig cfg = MllConfig.get();
        if (!cfg.enabled || !cfg.consentAccepted) {
            return;
        }
        if (event.getScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
            ChallengeManager mgr = challenges();
            if (mgr != null) {
                mgr.onInventoryClosed();
            }
        }
    }

    /** Opens the settings screen, used by the keybind. */
    public static void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.gui.setScreen(new MllSettingsScreen(mc.gui.screen()));
        }
    }
}
