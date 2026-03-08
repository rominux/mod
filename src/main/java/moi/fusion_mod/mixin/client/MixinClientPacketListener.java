package moi.fusion_mod.mixin.client;

import moi.fusion_mod.macros.FarmHelper;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts server-sent particle packets so FarmHelper can detect
 * ANGRY_VILLAGER particles from the vacuum (pest direction trail).
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleParticleEvent", at = @At("RETURN"))
    private void fusionMod$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        FarmHelper.onParticlePacket(packet);
    }
}
