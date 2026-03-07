package moi.fusion_mod.mixin.client;

import moi.fusion_mod.progression.ExperimentSolver;
import moi.fusion_mod.ui.screens.QuickNavOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public class MixinHandledScreen {

    @Inject(method = "render", at = @At("RETURN"))
    public void onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        ExperimentSolver.onScreenRender(screen, guiGraphics);
        QuickNavOverlay.renderButton(guiGraphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    public void onMouseClicked(MouseButtonEvent event, boolean consumed, CallbackInfoReturnable<Boolean> cir) {
        if (QuickNavOverlay.mouseClicked(event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }
}
