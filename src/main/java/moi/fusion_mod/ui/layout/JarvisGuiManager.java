package moi.fusion_mod.ui.layout;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector2ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages all HUD overlays. Renders via Fabric API's {@link HudRenderCallback},
 * which is the proven approach for 1.21.10 HUD rendering (used by pasunhack).
 */
public class JarvisGuiManager implements HudRenderCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger("JarvisGuiManager");

    public interface JarvisHud {
        boolean isEnabled();

        int getUnscaledWidth();

        int getUnscaledHeight();

        Vector2ic getPosition();

        /**
         * Render the HUD at the given offset position.
         *
         * @param graphics the gui graphics context
         * @param tickDelta frame delta
         * @param offsetX absolute X offset from getPosition()
         * @param offsetY absolute Y offset from getPosition()
         */
        void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY);
    }

    private static final List<JarvisHud> huds = new ArrayList<>();

    /**
     * Initialize all HUD instances and register this manager with Fabric's
     * HudRenderCallback. This replaces the old mixin-based approach.
     */
    public static void initializeHuds() {
        huds.add(new moi.fusion_mod.ui.hud.CommissionHud());
        huds.add(new moi.fusion_mod.ui.hud.DrillFuelBarHud());
        huds.add(new moi.fusion_mod.ui.hud.PickobulusTimerHud());
        huds.add(new moi.fusion_mod.ui.hud.ItemPickupLogHud());
        huds.add(new moi.fusion_mod.progression.HotmOverlay());
        huds.add(new moi.fusion_mod.hollows.CrystalHollowsMapHud());

        // Register with Fabric API's HUD render callback (proven to work in 1.21.10)
        HudRenderCallback.EVENT.register(new JarvisGuiManager());
    }

    /**
     * Called by Fabric API each frame to render HUD overlays.
     * In Mojang mappings: GuiGraphics = DrawContext, DeltaTracker = RenderTickCounter.
     */
    @Override
    public void onHudRender(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;

        // ── Render all registered HUDs ──────────────────────────────────
        float tickDelta = deltaTracker.getGameTimeDeltaTicks();
        for (JarvisHud hud : huds) {
            if (hud.isEnabled()) {
                try {
                    Vector2ic pos = hud.getPosition();
                    hud.render(graphics, tickDelta, pos.x(), pos.y());
                } catch (Exception e) {
                    LOGGER.error("[FusionMod] Error rendering HUD: {}", hud.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
