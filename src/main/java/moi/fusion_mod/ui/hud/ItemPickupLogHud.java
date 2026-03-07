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

public class ItemPickupLogHud implements JarvisGuiManager.JarvisHud {

    // Extracted architecture from
    // at.hannibal2.skyhanni.features.inventory.ItemPickupLog
    // Regex based on val shopPattern by patternGroup.pattern("shoppattern",
    // "^(?<itemName>.+?)(?: x\\d+)?\\$")

    private final Vector2i position = new Vector2i(150, 10);
    private final List<String> pickupLog = new ArrayList<>(); // Stub list for render demo

    @Override
    public boolean isEnabled() {
        return FusionConfig.isItemPickupLogEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 150;
    }

    @Override
    public int getUnscaledHeight() {
        return 100;
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null)
            return;

        // Emulating SkyHanni VerticalContainerRenderable logic
        // E.g. "+ 1x Mite Gel"
        int y = offsetY;

        // Background box
        int height = 12 + (pickupLog.size() * 10);
        graphics.fill(offsetX - 2, offsetY - 2, offsetX + 150, offsetY + height, 0x90000000);

        graphics.drawString(mc.font, Component.literal("\u00A7e\u00A7lItem Log"), offsetX, y, 0xFFFFFF, true);
        y += 12;

        for (String line : pickupLog) {
            graphics.drawString(mc.font, Component.literal(line), offsetX, y, 0xFFFFFF, true);
            y += 10;
        }
    }

    public void addPickup(String itemName, int amount) {
        // RenderList formatting mimicking SkyHanni compact lines
        // "+ amount itemName"
        pickupLog.add(0, "§a+ " + amount + "x " + itemName);
        if (pickupLog.size() > 5) {
            pickupLog.remove(pickupLog.size() - 1);
        }
    }
}
