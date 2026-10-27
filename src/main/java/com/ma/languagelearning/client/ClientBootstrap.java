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
import com.ma.languagelearning.net.ProvisionProgress;
import com.ma.languagelearning.net.SidecarProvisioner;
import com.ma.languagelearning.speech.SttClient;
import com.ma.languagelearning.speech.TranslationProvider;
import com.ma.languagelearning.ui.ChallengeOverlay;
import com.ma.languagelearning.ui.ConsentScreen;
import com.ma.languagelearning.ui.MllSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
    /** True when the player was in a world on the previous tick, used to detect world leave. */
    private static boolean wasInWorld;
    /** Ticks elapsed since the client starting; paces the automatic install kick-off. */
    private static int startupTicks;
    /** How long to wait after startup before provisioning, so the menu draws first. */
    private static final int STARTUP_INSTALL_DELAY_TICKS = 40;

    private ClientBootstrap() {
    }

    /** Called from the mod constructor on the client. */
    public static void init(IEventBus modBus) {
        MaLanguageLearning.LOGGER.info("[{}] client bootstrap", MaLanguageLearning.MODID);

        modBus.addListener(ClientBootstrap::onRegisterGuiLayers);
        modBus.addListener(ClientBootstrap::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onScreenClosing);

        // Shutdown hook: stop the sidecar process when the game closes so models unload
        // and GPU VRAM is released. Without this the Python sidecar becomes an orphaned
        // process that keeps running (and consuming RAM/VRAM) after Minecraft exits.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        provisioner().stopSidecar();
                                    } catch (Exception ignored) {
                                        // Best effort; the JVM is exiting anyway.
                                    }
                                },
                                "MaLanguageLearning-Shutdown"));
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
            // Session mode means "until the game restarts", and this is that moment. Done
            // before the first world is selected so it applies to every world in the run.
            if ("session".equalsIgnoreCase(MllConfig.get().memoryMode)) {
                ledger.clearSession();
            }
            resolver = new ItemNameResolver();
            stt = new SidecarClient();
            translations = (TranslationProvider) stt;
            mic = new JavaSoundCapture();

            challenges = new ChallengeManager(ledger, resolver, stt, translations, mic);
            tracker = new AcquisitionTracker(resolver, ledger);

            provisioner = new SidecarProvisioner();

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

    /**
     * Start provisioning the speech engine, from the consent screen.
     *
     * <p>Called the moment the player accepts, so the download begins while they are still
     * reading the screen rather than on first use. Safe to call repeatedly.</p>
     */
    public static void startInstall() {
        ensureInitialised();
        if (provisioner == null) {
            return;
        }
        // Provision first, then start the process. Previously only the first half ran,
        // so the engine installed itself perfectly and was never launched: the mod sat
        // there with a working venv and nothing listening on the port.
        provisioner.ensureReady().whenComplete((ignored, error) -> {
            if (error != null) {
                MaLanguageLearning.LOGGER.warn("[{}] speech engine install failed: {}",
                        MaLanguageLearning.MODID, error.getMessage());
                return;
            }
            try {
                provisioner.startSidecar();
            } catch (Exception e) {
                MaLanguageLearning.LOGGER.warn("[{}] could not start the speech engine",
                        MaLanguageLearning.MODID, e);
            }
        });
    }

    /** Current installer progress for the consent screen, never null. */
    public static ProvisionProgress progress() {
        if (provisioner == null) {
            return ProvisionProgress.idle();
        }
        return provisioner.progress();
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
        if (mc == null) {
            return;
        }

        // First-run consent gate. This must fire on the title screen, before the player
        // ever joins a world: the warning is about a ~700 MB download and a microphone,
        // and that is not something to spring on someone mid-game.
        // Re-prompt when the warning has changed materially, not only on a first run. The
        // version stamp exists for that: a bump means the download size or the hardware
        // cost changed, and the player agreed to the old numbers, not these.
        if (cfg.consentVersion < ConsentScreen.CURRENT_VERSION && mc.gui.screen() == null) {
            mc.gui.setScreen(new ConsentScreen(null));
            cfg.consentVersion = ConsentScreen.CURRENT_VERSION;
            cfg.save();
            return;
        }

        // Already accepted (a returning player): start the installer on the title screen so
        // the engine is ready by the time they are in a world. Deferred a few ticks so the
        // main menu finishes drawing before a background download begins.
        if (cfg.consentAccepted) {
            if (startupTicks < STARTUP_INSTALL_DELAY_TICKS) {
                startupTicks++;
                if (startupTicks == STARTUP_INSTALL_DELAY_TICKS) {
                    ClientBootstrap.startInstall();
                }
            }
        }

        // Keybinds are polled before the in-world check: the settings screen is the one
        // thing a player must be able to reach from the title screen, and it used to sit
        // behind a `player == null` return, so the key did nothing outside a world.
        KeyMappings.poll();

        // Detect world leave: reset the challenge manager so queued challenges, cooldown
        // state, and the current stack don't leak into the next world. LedgerStore handles
        // per-world progress isolation via setWorld(), but ChallengeManager has no world
        // concept of its own.
        if (wasInWorld && mc.player == null) {
            if (challenges != null) {
                challenges.reset();
            }
            if (tracker != null) {
                tracker.reset();
            }
        }
        wasInWorld = mc.player != null;

        if (mc.player == null) {
            return;
        }

        ensureInitialised();

        if (ledger != null) {
            ledger.setWorld(currentWorldName(mc));
        }

        if (challenges != null) {
            challenges.tick();
        }

        if (tracker != null && challenges != null) {
            tracker.tick();
            for (String key : tracker.pollNewItemKeys()) {
                net.minecraft.world.item.ItemStack stack = tracker.stackForKey(key);
                String word = resolver.wordFor(stack);
                MaLanguageLearning.LOGGER.info("[{}] acquired {} -> word={}",
                        MaLanguageLearning.MODID, key, word);
                if (word != null) {
                    challenges.queue(key, word, stack);
                }
            }
        }
    }

    /**
     * Name of the save the player is in, or null outside a world.
     *
     * <p>Uses the singleplayer save folder name so each world gets its own progress file;
     * on a multiplayer server the server address is used instead, since that is the closest
     * stable identity available on the client.</p>
     */
    private static String currentWorldName(Minecraft mc) {
        try {
            if (mc == null || mc.player == null) {
                return null;
            }
            var server = mc.getSingleplayerServer();
            if (server != null) {
                return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .getFileName().toString();
            }
            var connection = mc.getConnection();
            if (connection != null && connection.getServerData() != null) {
                String ip = connection.getServerData().ip;
                if (ip != null && !ip.isBlank()) {
                    return "server_" + ip;
                }
            }
            return "unknown";
        } catch (Throwable t) {
            return "unknown";
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
