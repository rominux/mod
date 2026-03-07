package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.List;

public class PickobulusTimerHud implements JarvisGuiManager.JarvisHud {

    // Logic adapted from de.hysky.skyblocker.skyblock.dwarven.PickobulusHudWidget
    // and PickobulusHelper
    private final Vector2i position = new Vector2i(10, 80);

    @Override
    public boolean isEnabled() {
        return FusionConfig.isPickobulusTimerEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 120;
    }

    @Override
    public int getUnscaledHeight() {
        return 50;
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null || mc.player == null)
            return;

        List<String> lines = new ArrayList<>();
        lines.add("§9§lPickobulus");

        // Mocking the PickobulusHelper.getErrorMessage() logic
        boolean hasPickobulus = false;
        if (mc.player.getMainHandItem() != null) {
            String itemName = mc.player.getMainHandItem().getHoverName().getString();
            // Simplified check based on Skyblocker generic ItemAbility concept
            if (itemName.contains("Pickaxe") || itemName.contains("Drill")) {
                hasPickobulus = true;
            }
        }

        if (!hasPickobulus) {
            lines.add("§cNot holding a tool with pickobulus");
        } else {
            // Adapted logic for fetching cooldown from tab list (PickobulusHelper: Process
            // cooldown info)
            String cooldownString = "Pickobulus: Available";
            for (net.minecraft.client.multiplayer.PlayerInfo entry : mc.getConnection().getListedOnlinePlayers()) {
                Component displayName = entry.getTabListDisplayName();
                if (displayName != null) {
                    String string = displayName.getString().trim();
                    if (string.startsWith("Pickobulus: ")) {
                        cooldownString = string;
                        break;
                    }
                }
            }

            if (!cooldownString.equals("Pickobulus: Available")) {
                lines.add("§cOn Cooldown: " + cooldownString.substring(12));
            } else {
                lines.add("§aReady!");
                // Extra info like Total Blocks could be calculated here via raytracing
                lines.add("Total Blocks: ?");
            }
        }

        int y = 0;
        for (String line : lines) {
            graphics.drawString(mc.font, line, 0, y, 0xFFFFFF);
            y += 10;
        }
    }
}
