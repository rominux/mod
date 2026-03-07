package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drill Fuel Bar HUD overlay.
 *
 * Positioned just above the vanilla hunger bar (right side of hotbar).
 * The vanilla hunger bar is at:
 *   x = screenWidth/2 + 10 (right of center)
 *   y = screenHeight - 39 (above hotbar)
 *   width = 81 pixels (9 hearts/shanks * 9px spacing)
 *
 * The fuel bar is placed 12px above the hunger bar.
 * Size matches vanilla hunger bar: 81px wide, 5px tall.
 */
public class DrillFuelBarHud implements JarvisGuiManager.JarvisHud {

    // Regex to extract fuel numbers: matches "1,000/10,000" or "1000/10000" etc.
    private static final Pattern FUEL_PATTERN = Pattern.compile("Fuel:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");
    // Fallback: strip everything except digits, spaces and slash
    private static final Pattern NOT_DURABILITY = Pattern.compile("[^0-9 /]");

    @Override
    public boolean isEnabled() {
        return FusionConfig.isDrillFuelBarEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 81;
    }

    @Override
    public int getUnscaledHeight() {
        return 16; // bar (5px) + text (9px) + gap (2px)
    }

    @Override
    public Vector2ic getPosition() {
        // Positioned above the hunger bar on the right side of the hotbar
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Vanilla hunger bar position:
        //   x = screenWidth/2 + 10
        //   y = screenHeight - 39
        // We place the fuel bar 12px above that
        int x = screenWidth / 2 + 10;
        int y = screenHeight - 39 - 12;
        return new Vector2i(x, y);
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null)
            return;

        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty())
            return;

        // Extract fuel from lore
        int currentFuel = -1;
        int maxFuel = -1;

        List<Component> lore = stack.getTooltipLines(Item.TooltipContext.EMPTY, mc.player, TooltipFlag.NORMAL);
        for (Component comp : lore) {
            String line = comp.getString();
            if (line.contains("Fuel: ") || line.contains("Fuel:")) {
                String stripped = ChatFormatting.stripFormatting(line);
                if (stripped == null) continue;

                // Try the precise regex first: "Fuel: 1,000/10,000"
                Matcher matcher = FUEL_PATTERN.matcher(stripped);
                if (matcher.find()) {
                    try {
                        currentFuel = Integer.parseInt(matcher.group(1).replace(",", ""));
                        maxFuel = Integer.parseInt(matcher.group(2).replace(",", ""));
                    } catch (NumberFormatException ignored) {}
                }

                // Fallback: strip non-digits except slash
                if (currentFuel < 0 || maxFuel < 0) {
                    String numOnly = NOT_DURABILITY.matcher(stripped).replaceAll("").trim();
                    String[] parts = numOnly.split("/");
                    if (parts.length >= 2) {
                        try {
                            currentFuel = Integer.parseInt(parts[0].trim());
                            maxFuel = Integer.parseInt(parts[1].trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
                break;
            }
        }

        if (currentFuel < 0 || maxFuel <= 0)
            return;

        // ── Render the bar ──────────────────────────────────────────────
        int barWidth = 81; // Same width as vanilla hunger bar
        int barHeight = 5;
        float percentage = Math.min(1.0f, (float) currentFuel / maxFuel);
        int filledWidth = (int) (barWidth * percentage);

        // Background (dark)
        graphics.fill(offsetX, offsetY, offsetX + barWidth, offsetY + barHeight, 0xAA000000);
        // Filled portion (green for >50%, yellow for 20-50%, red for <20%)
        int barColor;
        if (percentage > 0.5f) {
            barColor = 0xFF00CC00; // Green
        } else if (percentage > 0.2f) {
            barColor = 0xFFFFAA00; // Yellow/orange
        } else {
            barColor = 0xFFFF3333; // Red
        }
        graphics.fill(offsetX, offsetY, offsetX + filledWidth, offsetY + barHeight, barColor);
        // Border (thin outline)
        graphics.fill(offsetX, offsetY, offsetX + barWidth, offsetY + 1, 0xFF333333); // top
        graphics.fill(offsetX, offsetY + barHeight - 1, offsetX + barWidth, offsetY + barHeight, 0xFF333333); // bottom

        // ── Render fuel text (centered above bar) ───────────────────────
        String fuelText = formatNumber(currentFuel) + " / " + formatNumber(maxFuel);
        int textWidth = mc.font.width(fuelText);
        int textX = offsetX + barWidth / 2 - textWidth / 2;
        int textY = offsetY + barHeight + 1;
        graphics.drawString(mc.font, Component.literal(fuelText), textX, textY, 0xFFFFFFFF);
    }

    /**
     * Formats large numbers with k suffix: 10000 -> "10k", 1500 -> "1.5k"
     */
    private static String formatNumber(int n) {
        if (n >= 1000) {
            if (n % 1000 == 0) {
                return (n / 1000) + "k";
            } else {
                return String.format("%.1fk", n / 1000.0);
            }
        }
        return String.valueOf(n);
    }
}
