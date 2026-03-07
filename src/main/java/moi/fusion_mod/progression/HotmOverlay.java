package moi.fusion_mod.progression;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    public void render(GuiGraphics graphics, float tickDelta) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;

        // Render box and text relative to the translated position (0, 0) since
        // JarvisGuiManager uses graphics.pose().translate()
        graphics.fill(-2, -2, 148, 12, 0x80000000); // Background box
        graphics.drawString(font, "§bSky Mall§8: " + activePerk, 0, 0, 0xFFFFFF); // Perk label
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
