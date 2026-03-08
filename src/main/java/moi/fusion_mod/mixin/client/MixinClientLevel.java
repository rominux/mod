package moi.fusion_mod.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import moi.fusion_mod.events.BlockUpdateCallback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on ClientLevel to intercept server-verified block state changes.
 * Fires BlockUpdateCallback.EVENT with old and new state.
 *
 * Pattern from Skyblocker's ClientLevelMixin.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements BlockGetter {

    @Inject(
            method = "setServerVerifiedBlockState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    )
    private void fusionmod$captureOldState(CallbackInfo ci,
                                            @Local(argsOnly = true) BlockPos pos,
                                            @Share("oldState") LocalRef<BlockState> oldState) {
        oldState.set(getBlockState(pos));
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
    private void fusionmod$fireBlockUpdate(CallbackInfo ci,
                                            @Local(argsOnly = true) BlockPos pos,
                                            @Local(argsOnly = true) BlockState state,
                                            @Share("oldState") LocalRef<BlockState> oldState) {
        BlockState old = oldState.get();
        if (old != null) {
            BlockUpdateCallback.EVENT.invoker().onBlockUpdate(pos, old, state);
        }
    }
}
