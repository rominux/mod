package moi.fusion_mod.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric event fired when the server updates a block state on the client.
 * Mirrors Skyblocker's WorldEvents.BLOCK_STATE_UPDATE pattern.
 *
 * Backed by a mixin on ClientLevel.setServerVerifiedBlockState.
 */
public interface BlockUpdateCallback {

    Event<BlockUpdateCallback> EVENT = EventFactory.createArrayBacked(
            BlockUpdateCallback.class,
            callbacks -> (pos, oldState, newState) -> {
                for (BlockUpdateCallback callback : callbacks) {
                    callback.onBlockUpdate(pos, oldState, newState);
                }
            }
    );

    /**
     * Called when the server sends a block state change.
     *
     * @param pos      Block position (may be mutable — use {@link BlockPos#immutable()} if storing)
     * @param oldState Previous block state at this position
     * @param newState New block state being set
     */
    void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState);
}
