package moi.fusion_mod.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.DeltaTracker;

/**
 * HUD rendering has been moved to HudRenderCallback (Fabric API).
 * This mixin is kept as a placeholder; it no longer calls JarvisGuiManager.
 */
@Mixin(Gui.class)
public class MixinInGameHud {
    // Intentionally empty — HUD rendering now uses HudRenderCallback in JarvisGuiManager
}
