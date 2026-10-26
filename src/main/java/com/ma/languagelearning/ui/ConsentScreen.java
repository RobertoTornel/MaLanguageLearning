package com.ma.languagelearning.ui;

import com.ma.languagelearning.config.MllConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * First-run consent screen. Nothing is downloaded and no microphone is touched until the
 * player explicitly accepts the hardware and download warning shown here.
 */
@OnlyIn(Dist.CLIENT)
public final class ConsentScreen extends Screen {

    /** Bump when the warning text changes so returning users are re-prompted. */
    public static final int CURRENT_VERSION = 1;

    private static final List<String> WARNING = List.of(
            "!!  BIG WARNING — READ BEFORE CONTINUING  !!",
            "",
            "THIS MOD DOWNLOADS A SPEECH RECOGNITION MODEL TO YOUR COMPUTER.",
            "IT IS LARGE AND IT IS NOT INCLUDED IN THE JAR.",
            "",
            "  • PARAKEET V3 (INT8) IS ABOUT 671 MB ON DISK.",
            "  • THE FULL PRECISION MODEL IS ABOUT 2.5 GB.",
            "  • EXPECT ROUGHLY 1-3 GB OF SYSTEM RAM WHILE RUNNING.",
            "  • GPU MODE (CUDA) NEEDS ADDITIONAL VRAM AND A CUDA RUNTIME.",
            "  • YOU CAN SWITCH BETWEEN GPU AND CPU IN THE SETTINGS SCREEN.",
            "",
            "IT ALSO DOWNLOADS A SMALL PYTHON RUNTIME (uv + CPython).",
            "TOTAL FIRST-RUN DOWNLOAD CAN EXCEED 1 GB. A NETWORK IS REQUIRED.",
            "",
            "THIS MOD HAS A NOTICEABLE PERFORMANCE IMPACT. SPEECH RECOGNITION",
            "RUNS AS A SEPARATE LOCAL PROCESS AND WILL USE CPU OR GPU TIME.",
            "",
            "MICROPHONE: AUDIO IS RECORDED WHILE A WORD CHALLENGE IS ACTIVE",
            "AND SENT ONLY TO THE LOCAL PROCESS ON 127.0.0.1. IT NEVER LEAVES",
            "YOUR MACHINE."
    );

    private final Screen parent;
    private int scroll;

    public ConsentScreen(Screen parent) {
        super(Component.literal("MaLanguageLearning — Before You Start"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bottom = this.height - 32;

        this.addRenderableWidget(Button.builder(
                Component.literal("I understand — enable the mod"),
                b -> {
                    MllConfig cfg = MllConfig.get();
                    cfg.consentAccepted = true;
                    cfg.consentVersion = CURRENT_VERSION;
                    cfg.save();
                    this.onClose();
                }).pos(cx - 160, bottom).width(155).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Decline"),
                b -> {
                    MllConfig cfg = MllConfig.get();
                    cfg.consentAccepted = false;
                    cfg.save();
                    this.onClose();
                }).pos(cx + 5, bottom).width(155).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int top = 24;
        int lineY = top;
        int cx = this.width / 2;

        g.centeredText(this.font, "MaLanguageLearning", cx, 8, 0xFFB9A6E8);

        for (String line : WARNING) {
            int color = 0xFFE0E0E0;
            if (line.startsWith("!!")) {
                color = 0xFFFF5555;
            } else if (line.startsWith("  •")) {
                color = 0xFFFFC46B;
            } else if (line.isBlank()) {
                lineY += this.font.lineHeight / 2;
                continue;
            }
            g.centeredText(this.font, line, cx, lineY, color);
            lineY += this.font.lineHeight + 1;
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
