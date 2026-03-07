package moi.fusion_mod.progression;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HotmOverlay implements JarvisGuiManager.JarvisHud {

    private String activePerk = "Unknown! Open HotM to update.";
    // SkyHanni rotating perk pattern hook
    private static final Pattern PERK_PATTERN = Pattern.compile("Sky Mall \\(Active\\) .*? granting (.+)!");

    @Override
    public boolean isEnabled() {
        return FusionConfig.isHotmOverlayEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 150;
    }

    @Override
    public int getUnscaledHeight() {
        return 14;
    }

    @Override
    public Vector2ic getPosition() {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Position at center right, matches previously defined coordinates
        return new Vector2i(screenWidth - 150, screenHeight / 2 - 20);
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;

        // Render box and text at the absolute offset position
        graphics.fill(offsetX - 2, offsetY - 2, offsetX + 148, offsetY + 12, 0x80000000);
        graphics.drawString(font, Component.literal("\u00A7bSky Mall\u00A78: " + activePerk),
                offsetX, offsetY, 0xFFFFFFFF);
    }

    /**
     * Intercepts chat or scoreboards to update the parsed perk string
     */
    public void feedChat(String message) {
        Matcher m = PERK_PATTERN.matcher(message.replaceAll("§.", ""));
        if (m.find()) {
            this.activePerk = "§a" + m.group(1).trim();
        }
    }
}
