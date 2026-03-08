package moi.fusion_mod.mixin.client;

import moi.fusion_mod.hollows.CrystalHollowsMapHud;
import moi.fusion_mod.social.ChatFilter;
import moi.fusion_mod.social.PartyCommands;
import moi.fusion_mod.solvers.FetchurSolver;
import moi.fusion_mod.solvers.PuzzlerSolver;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class MixinChatListener {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    public void onAddMessage(Component message, CallbackInfo ci) {
        if (!ChatFilter.shouldAllowGame(message, false)) {
            ci.cancel();
            return;
        }

        String text = message.getString();
        // Strip formatting codes for solver pattern matching
        String stripped = text.replaceAll("\u00A7[0-9a-fk-or]", "");

        PartyCommands.handleMessage(text);

        // Route chat messages to Crystal Hollows map for zone auto-discovery
        CrystalHollowsMapHud.onChatMessage(text);

        // Fetchur & Puzzler solvers
        FetchurSolver.onChatMessage(stripped);
        PuzzlerSolver.onChatMessage(stripped);
    }
}
