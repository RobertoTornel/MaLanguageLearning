package com.ma.languagelearning.ui;

import com.ma.languagelearning.MaLanguageLearning;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.game.ChallengeManager;
import com.ma.languagelearning.game.ChallengeOutcome;
import com.ma.languagelearning.speech.WordPair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the learning popup over the HUD: the word in the primary script, the secondary
 * script underneath, the phonetic reading, and the countdown.
 */
public final class ChallengeOverlay {

    private static final int PANEL_WIDTH = 260;
    /** Item icons are 16x16 logical pixels in Minecraft's GUI scale. */
    private static final int ICON_SIZE = 16;
    private static final int PAD = 10;

    private ChallengeOverlay() {
    }

    /**
     * Render the popup.
     *
     * @param g      the 26.3 gui extractor (formerly GuiGraphics)
     * @param delta  partial tick, unused but kept for parity with the event
     * @param mgr    the challenge manager driving the state
     */
    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta, ChallengeManager mgr) {
        if (mgr == null || !mgr.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null || mc.getWindow() == null) {
            return;
        }
        // 26.3: GUI visibility lives on the Hud instance, not Options.
        if (mc.gui != null && mc.gui.hud != null && mc.gui.hud.isHidden()) {
            return;
        }

        WordPair pair = mgr.currentPair();
        String word = pair == null ? mgr.currentWord() : pair.primary();
        if (word == null || word.isBlank()) {
            return;
        }

        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int seconds = mgr.secondsRemaining();
        boolean urgent = seconds <= 5;

        // ---- collect the lines we are going to draw -------------------------
        String secondary = pair == null ? "" : pair.secondary();
        String phonetic = pair == null ? "" : pair.phonetic();
        boolean wantPhonetic = MllConfig.get().showPhonetics && phonetic != null && !phonetic.isBlank() && !phonetic.equals(secondary);

        int wordHeight = font.lineHeight;
        int smallHeight = font.lineHeight;

        int contentW = font.width(word);
        contentW = Math.max(contentW, secondary.isBlank() ? 0 : font.width(secondary));
        contentW = Math.max(contentW, wantPhonetic ? font.width(phonetic) : 0);
        String header = "Say this out loud";
        String timerText = seconds + "s";
        contentW = Math.max(contentW, font.width(header) + font.width(timerText) + 12);
        contentW = Math.min(contentW, PANEL_WIDTH - PAD * 2);

        ItemStack icon = mgr.currentStack();
        boolean showIcon = !icon.isEmpty();
        // The icon sits above the word, centred, with a little breathing room.
        int iconBlock = showIcon ? ICON_SIZE + 6 : 0;

        int panelW = Math.max(140, contentW + PAD * 2);
        int panelH = iconBlock + PAD + smallHeight + 4 + wordHeight + 3
                + (secondary.isBlank() ? 0 : smallHeight + 2)
                + (wantPhonetic ? smallHeight + 2 : 0)
                + 10;

        int x = (screenW - panelW) / 2;
        // Place the panel in the upper-middle so it never covers the crosshair (screen center).
        // Clamp: bottom edge must stay above screenH/2.
        int y = (int) (screenH * 0.18);
        if (y + panelH > screenH / 2) {
            y = screenH / 2 - panelH;
        }
        if (y < 4) {
            y = 4;
        }

        // ---- result tint ----------------------------------------------------
        // On a pass the whole panel goes green for a beat, which reads instantly even
        // without looking at the text. The alpha fades out over the hold so it does not
        // vanish abruptly.
        boolean passed = mgr.currentOutcome() == ChallengeOutcome.PASSED && !mgr.isSkipped();
        boolean failed = mgr.currentOutcome() == ChallengeOutcome.FAILED;
        int panelColor = 0xD0181220;
        if (passed) {
            panelColor = 0xE0143A22;
        } else if (failed) {
            panelColor = 0xE03A1414;
        }

        // ---- panel background ----------------------------------------------
        g.fill(x, y, x + panelW, y + panelH, panelColor);
        int border = passed ? 0xFF5BE07A : (failed ? 0xFFFF4545 : (urgent ? 0xFFFF4545 : 0xFF7A5CC4));
        g.fill(x, y, x + panelW, y + 1, border);
        g.fill(x, y + panelH - 1, x + panelW, y + panelH, border);
        g.fill(x, y, x + 1, y + panelH, border);
        g.fill(x + panelW - 1, y, x + panelW, y + panelH, border);

        int cx = x + panelW / 2;
        int cy = y + PAD;

        // ---- header + timer -------------------------------------------------
        g.text(font, header, x + PAD, cy, 0xFFB9A6E8, false);
        int timerColor = urgent ? 0xFFFF6060 : 0xFF8FE3A0;
        g.text(font, timerText, x + panelW - PAD - font.width(timerText), cy, timerColor, false);
        cy += smallHeight + 4;

        // ---- item icon ------------------------------------------------------
        if (showIcon) {
            // 26.3 renders items through the same extractor that draws the rest of the GUI.
            g.item(icon, cx - ICON_SIZE / 2, cy);
            cy += iconBlock;
        }

        // ---- primary word ---------------------------------------------------
        g.centeredText(font, word, cx, cy, 0xFFFFFFFF);
        cy += wordHeight + 3;

        // ---- secondary script ----------------------------------------------
        if (!secondary.isBlank()) {
            g.centeredText(font, secondary, cx, cy, 0xFFC8B8F0);
            cy += smallHeight + 2;
        }

        // ---- phonetics ------------------------------------------------------
        if (wantPhonetic) {
            g.centeredText(font, phonetic, cx, cy, 0xFF8A8A9A);
        }

        // ---- live status ----------------------------------------------------
        // Drawn inside the panel so it's always in the player's focal area.
        String status = mgr.statusText();
        if (status != null && !status.isBlank()) {
            int statusY = cy + smallHeight + 4;
            if (statusY < y + panelH - 4) {
                g.centeredText(font, status, cx, statusY, 0xFFCFCFCF);
            }
        }
    }

    private static void logIfDev(String msg) {
        if (MaLanguageLearning.LOGGER.isDebugEnabled()) {
            MaLanguageLearning.LOGGER.debug(msg);
        }
    }
}
