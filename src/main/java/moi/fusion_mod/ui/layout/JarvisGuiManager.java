package moi.fusion_mod.ui.layout;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector2ic;
import java.util.ArrayList;
import java.util.List;

public class JarvisGuiManager {

    // Extracted architecture from doc/jarvis/api/moe/nea/jarvis/api/JarvisHud.java
    public interface JarvisHud {
        boolean isEnabled();

        int getUnscaledWidth();

        int getUnscaledHeight();

        Vector2ic getPosition();

        void render(GuiGraphics graphics, float tickDelta);
    }

    private static final List<JarvisHud> huds = new ArrayList<>();

    public static void initializeHuds() {
        // Register all HUD implementations here
        huds.add(new moi.fusion_mod.ui.hud.CommissionHud());
        huds.add(new moi.fusion_mod.ui.hud.DrillFuelBarHud());
        huds.add(new moi.fusion_mod.ui.hud.PickobulusTimerHud());
        huds.add(new moi.fusion_mod.ui.hud.ItemPickupLogHud());
        huds.add(new moi.fusion_mod.progression.HotmOverlay());
        huds.add(new moi.fusion_mod.hollows.CrystalHollowsMapHud());
    }

    // Called from MixinInGameHud
    public static void render(GuiGraphics graphics, float tickDelta) {
        for (JarvisHud hud : huds) {
            if (hud.isEnabled()) {
                graphics.pose().pushMatrix();
                Vector2ic pos = hud.getPosition();
                graphics.pose().translate(pos.x(), pos.y());
                hud.render(graphics, tickDelta);
                graphics.pose().popMatrix();
            }
        }
    }
}
