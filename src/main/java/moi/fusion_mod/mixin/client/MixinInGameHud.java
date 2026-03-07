package moi.fusion_mod.mixin.client;

import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.DeltaTracker;

@Mixin(Gui.class)
public class MixinInGameHud {
    @Inject(method = "render", at = @At("RETURN"))
    public void onRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        // Render custom HUDs
        JarvisGuiManager.render(guiGraphics, deltaTracker.getGameTimeDeltaTicks());
    }
}
