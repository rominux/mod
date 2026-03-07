package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.regex.Pattern;

public class DrillFuelBarHud implements JarvisGuiManager.JarvisHud {

    // Logic extracted exactly from de.hysky.skyblocker.utils.ItemUtils
    public static final Pattern NOT_DURABILITY = Pattern.compile("[^0-9 /]");
    private final Vector2i position = new Vector2i(250, 200);

    @Override
    public boolean isEnabled() {
        return FusionConfig.isDrillFuelBarEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 100;
    }

    @Override
    public int getUnscaledHeight() {
        return 20;
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null)
            return;

        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty())
            return;

        // Custom durability check mimicking ItemUtils.hasCustomDurability and
        // getDurability
        String drillFuel = null;

        // This normally uses custom DataComponents.LORE accessors in Skyblocker.
        // We simulate reading the lore lines directly since Fabric provides access to
        // it.
        // In 1.21.10 lore strings require extracting from components, but sticking to
        // logic structure:
        List<net.minecraft.network.chat.Component> lore = stack.getTooltipLines(mc.player,
                net.minecraft.world.item.TooltipFlag.NORMAL);
        for (net.minecraft.network.chat.Component comp : lore) {
            String line = comp.getString();
            if (line.contains("Fuel: ")) {
                drillFuel = ChatFormatting.stripFormatting(line);
                break;
            }
        }

        if (drillFuel != null) {
            // "Fuel: 1000/3000" -> "1000/3000" -> [1000, 3000]
            String stripped = NOT_DURABILITY.matcher(drillFuel).replaceAll("").trim();
            String[] drillFuelStrings = stripped.split("/");
            if (drillFuelStrings.length >= 2) {
                try {
                    int currentFuel = Integer.parseInt(drillFuelStrings[0]);
                    int maxFuel = Integer.parseInt(drillFuelStrings[1]); // In Skyblocker they multiply max by 1000, but
                                                                         // we'll visualize standard max.

                    float percentage = (float) currentFuel / maxFuel;
                    int barWidth = 100;
                    int filledWidth = (int) (barWidth * percentage);

                    // Draw Background
                    graphics.fill(0, 0, barWidth, 10, 0x80000000);
                    // Draw Fuel Bar (Green)
                    graphics.fill(0, 0, filledWidth, 10, 0xFF00FF00);
                    // Draw Text
                    graphics.drawCenteredString(mc.font, currentFuel + " / " + maxFuel, barWidth / 2, 1, 0xFFFFFF);

                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
