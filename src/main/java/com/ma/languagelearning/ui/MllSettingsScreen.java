package com.ma.languagelearning.ui;

import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.speech.Languages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen: target language, the two translation slots, script choice, phonetics,
 * timers, drop behaviour and the CPU/GPU device toggle.
 */
@OnlyIn(Dist.CLIENT)
public final class MllSettingsScreen extends Screen {

    private final Screen parent;
    private final List<Runnable> syncers = new ArrayList<>();

    public MllSettingsScreen(Screen parent) {
        super(Component.literal("MaLanguageLearning — Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MllConfig cfg = MllConfig.get();
        this.syncers.clear();

        int half = 150;
        int gap = 8;
        int totalW = half * 2 + gap;
        int x0 = this.width / 2 - totalW / 2;
        int y = 36;
        int rowH = 24;

        // ---- target language -------------------------------------------------
        CycleButton<String> languageBtn = CycleButton.<String>builder(
                        v -> Component.literal(Languages.displayName(v)),
                        cfg.targetLanguage)
                .withValues(Languages.ALL_CODES)
                .create(x0, y, half, 20, Component.literal("Learning"),
                        (btn, value) -> {
                            cfg.targetLanguage = value;
                            // A language change invalidates the script choice.
                            if (!Languages.scriptsFor(value).contains(cfg.primaryScript)) {
                                cfg.primaryScript = Languages.scriptsFor(value).get(0);
                                cfg.secondaryScript = Languages.scriptsFor(value).size() > 1
                                        ? Languages.scriptsFor(value).get(1) : "plain";
                            }
                            cfg.save();
                            this.rebuild();
                        });
        this.addRenderableWidget(languageBtn);

        // ---- native language -------------------------------------------------
        CycleButton<String> nativeBtn = CycleButton.<String>builder(
                        v -> Component.literal(Languages.displayName(v)),
                        cfg.nativeLanguage)
                .withValues(Languages.NATIVE_CODES)
                .create(x0 + half + gap, y, half, 20, Component.literal("Native"),
                        (btn, value) -> {
                            cfg.nativeLanguage = value;
                            cfg.save();
                        });
        this.addRenderableWidget(nativeBtn);
        y += rowH;

        // ---------------------------------------------------------------- primary
        List<String> scripts = Languages.scriptsFor(cfg.targetLanguage);
        CycleButton<String> primaryBtn = CycleButton.<String>builder(
                        v -> Component.literal(cap(v)),
                        cfg.primaryScript)
                .withValues(scripts)
                .create(x0, y, half, 20, Component.literal("Primary script"),
                        (btn, value) -> {
                            cfg.primaryScript = value;
                            cfg.save();
                        });
        this.addRenderableWidget(primaryBtn);

        List<String> secondaryOptions = new ArrayList<>(scripts);
        secondaryOptions.add("native");
        if (!secondaryOptions.contains(cfg.secondaryScript)) {
            cfg.secondaryScript = secondaryOptions.get(Math.min(1, secondaryOptions.size() - 1));
        }
        CycleButton<String> secondaryBtn = CycleButton.<String>builder(
                        v -> Component.literal(cap(v)),
                        cfg.secondaryScript)
                .withValues(secondaryOptions)
                .create(x0 + half + gap, y, half, 20, Component.literal("Second translation"),
                        (btn, value) -> {
                            cfg.secondaryScript = value;
                            cfg.save();
                        });
        this.addRenderableWidget(secondaryBtn);
        y += rowH;

        // --------------------------------------------------------------- phonetics
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showPhonetics)
                .create(x0, y, half, 20, Component.literal("Show phonetics"),
                        (btn, value) -> {
                            cfg.showPhonetics = value;
                            cfg.save();
                        }));

        // ------------------------------------------------------------------ device
        this.addRenderableWidget(CycleButton.<String>builder(
                        v -> Component.literal(v.equalsIgnoreCase("gpu") ? "GPU (CUDA)" : "CPU"),
                        cfg.inferenceDevice)
                .withValues("cpu", "gpu")
                .create(x0 + half + gap, y, half, 20, Component.literal("Inference device"),
                        (btn, value) -> {
                            cfg.inferenceDevice = value;
                            cfg.save();
                        }));
        y += rowH;

        // ------------------------------------------------------------- behaviour
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.dropOnFailure)
                .create(x0, y, half, 20, Component.literal("Drop item on failure"),
                        (btn, value) -> {
                            cfg.dropOnFailure = value;
                            cfg.save();
                        }));

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.speakWordOnFailure)
                .create(x0 + half + gap, y, half, 20, Component.literal("Speak word on failure"),
                        (btn, value) -> {
                            cfg.speakWordOnFailure = value;
                            cfg.save();
                        }));
        y += rowH;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.enabled)
                .create(x0, y, half, 20, Component.literal("Mod enabled"),
                        (btn, value) -> {
                            cfg.enabled = value;
                            cfg.save();
                        }));

        this.addRenderableWidget(CycleButton.<Integer>builder(
                        v -> Component.literal(v + " seconds"),
                        cfg.answerSeconds)
                .withValues(5, 10, 15, 20, 30, 45, 60)
                .create(x0 + half + gap, y, half, 20, Component.literal("Answer time"),
                        (btn, value) -> {
                            cfg.answerSeconds = value;
                            cfg.save();
                        }));

        // ------------------------------------------------------------------ done
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .pos(this.width / 2 - 100, this.height - 30).width(200).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, "MaLanguageLearning — Settings", this.width / 2, 12, 0xFFB9A6E8);
        MllConfig cfg = MllConfig.get();
        String hint = "Learning " + Languages.displayName(cfg.targetLanguage)
                + "  ·  primary: " + cap(cfg.primaryScript)
                + "  ·  second: " + cap(cfg.secondaryScript)
                + "  ·  " + cap(cfg.inferenceDevice);
        g.centeredText(this.font, hint, this.width / 2, 24, 0xFF9A9AA8);
    }

    /** Rebuild the widget list after a language change invalidates the script options. */
    private void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.gui.setScreen(new MllSettingsScreen(this.parent));
        }
    }

    @Override
    public void onClose() {
        MllConfig.get().save();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
