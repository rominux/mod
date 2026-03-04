package moi.fusion_mod.progression;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class HotmOverlay {
    public void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        // Extracted & Adapted logic from SkyHanni -> HotxFeatures.kt
        // SkyHanni normally reads this from its HotmData singleton.
        String guiName = "Sky Mall";
        String perkDescriptionFormat = "Unknown! Run /hotm to fix this.";
        
        // Format taken exactly from val finalFormat = "§b${rotatingPerkEntry.guiName}§8: $perkDescriptionFormat"
        String finalFormat = "§b" + guiName + "§8: " + perkDescriptionFormat;
        
        // Render element coordinates setup (SkyHanni configPos.renderRenderable implementation)
        int x = 10; // Default config pos
        int y = 50; // Default config pos
        
        graphics.drawString(mc.font, finalFormat, x, y, 0xFFFFFF, true);
    }
}
