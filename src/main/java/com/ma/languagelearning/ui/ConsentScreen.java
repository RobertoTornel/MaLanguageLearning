package com.ma.languagelearning.ui;

import com.ma.languagelearning.client.ClientBootstrap;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.net.ProvisionProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * First-run consent screen, shown on the title screen before the player joins a world.
 *
 * <p>Two jobs: keep the warning short enough to actually be read, and — once the player
 * accepts — show what the installer is doing. The download is genuinely slow, so a screen
 * that silently sat there would look like a hang.</p>
 */
public final class ConsentScreen extends Screen {

    /** Bump when the warning text changes so returning users are re-prompted. */
    public static final int CURRENT_VERSION = 3;

    private static final List<String> WARNING = List.of(
            "⚠ THIS MOD DOWNLOADS STUFF AND IS HEAVY",
            "",
            "First launch downloads ~800 MB, and up to ~2.5 GB more if you",
            "learn Japanese, Chinese or Korean. It installs in the background",
            "and can take a while — the game stays playable meanwhile.",
            "",
            "• Uses about 1–3 GB of RAM while running",
            "• GPU mode needs CUDA + extra VRAM (CPU/GPU is a setting)",
            "• Measurable performance cost — it runs alongside the game",
            "• Needs a working microphone",
            "",
            "Audio is recorded only during a word challenge and never leaves",
            "your machine. Everything runs locally on 127.0.0.1."
    );

    private final Screen parent;
    private boolean installing;

    public ConsentScreen(Screen parent) {
        super(Component.literal("MaLanguageLearning"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bottom = this.height - 40;

        this.addRenderableWidget(Button.builder(
                Component.literal(installing ? "Installing…" : "Accept & install"),
                b -> {
                    MllConfig cfg = MllConfig.get();
                    cfg.consentAccepted = true;
                    cfg.consentVersion = CURRENT_VERSION;
                    cfg.save();
                    // Kick the installer off right here, so the player watches it run instead
                    // of discovering a silent download later. The screen stays open to show it.
                    installing = true;
                    ClientBootstrap.startInstall();
                    this.rebuild();
                }).pos(cx - 160, bottom).width(155).build());

        Button done = Button.builder(
                Component.literal(installing ? "Play (installs in background)" : "Not now"),
                b -> this.onClose()).pos(cx + 5, bottom).width(155).build();
        this.addRenderableWidget(done);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int lineY = 30;

        for (String line : WARNING) {
            if (line.isBlank()) {
                lineY += this.font.lineHeight / 2;
                continue;
            }
            int color = line.startsWith("⚠") ? 0xFFFF5555 : 0xFFE0E0E0;
            if (line.startsWith("•")) {
                color = 0xFFFFC46B;
            }
            g.centeredText(this.font, line, cx, lineY, color);
            lineY += this.font.lineHeight + 2;
        }

        if (installing) {
            ProvisionProgress progress = ClientBootstrap.progress();
            // Close the moment the engine is ready. The player asked to install, not to
            // babysit a progress bar; leaving a finished screen up would just be in the way.
            if (progress != null && progress.isReady()) {
                this.onClose();
                return;
            }
            lineY += 14;
            g.centeredText(this.font, "Setting up the speech engine", cx, lineY, 0xFFB9A6E8);
            lineY += this.font.lineHeight + 3;

            String detail = progress == null ? "starting…" : progress.detail();
            int percent = progress == null ? 0 : progress.percent();
            g.centeredText(this.font, detail, cx, lineY, 0xFFCFCFCF);
            lineY += this.font.lineHeight + 3;

            // A plain bar; no textures needed, so it cannot fail to draw.
            int barW = 260;
            int barH = 6;
            int bx = cx - barW / 2;
            g.fill(bx, lineY, bx + barW, lineY + barH, 0xFF2A2136);
            int filled = Math.max(0, Math.min(100, percent)) * barW / 100;
            g.fill(bx, lineY, bx + filled, lineY + barH, 0xFF7A5CC4);
            lineY += barH + 4;
            g.centeredText(this.font, percent + "%", cx, lineY, 0xFF9A9AA8);
            lineY += this.font.lineHeight + 2;
            g.centeredText(this.font,
                    "You can keep playing — it installs in the background.",
                    cx, lineY, 0xFF8A8A9A);
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
