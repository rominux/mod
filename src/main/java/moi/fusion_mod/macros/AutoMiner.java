package moi.fusion_mod.macros;

import moi.fusion_mod.config.FusionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AutoMiner — automatically mines configured block types within range.
 * Ported from pasunhack AutoMiner.java (Yarn mappings → Mojang 1.21.10).
 *
 * Scans radius 5 around the player for configured blocks, picks closest by angle,
 * lerps camera toward target, validates via crosshair raycast, holds attack key.
 *
 * Failsafes:
 *  - Movement detection (auto-disable if player moves > 0.005 sq dist)
 *  - 5s mining timeout per block
 *  - 2s stuck timeout (40 ticks)
 *  - Aim validation (10 tick timeout if crosshair never hits target)
 *  - Per-block blacklist (resets on toggle off)
 */
public class AutoMiner {

    private static boolean enabled = false;
    private static long miningStartTime = 0;
    private static Vec3 lastPlayerPos = null;
    private static int aimWaitTicks = 0;
    private static int stuckTicks = 0;

    public static Vec3 precisionTarget = null;
    public static BlockPos currentTarget = null;

    public static final Set<BlockPos> BLACKLIST_TEMP = new HashSet<>();

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            cancelMining(Minecraft.getInstance());
            BLACKLIST_TEMP.clear();
        }
        lastPlayerPos = null;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null)
            return;

        Vec3 currentPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Failsafe 1: Auto-disable if player moves (fall, walk)
        if (lastPlayerPos != null && currentPos.distanceToSqr(lastPlayerPos) > 0.005) {
            enabled = false;
            mc.player.displayClientMessage(Component.literal("\u00A7cAutoMiner paused (Movement)"), true);
            cancelMining(mc);
            return;
        }
        lastPlayerPos = currentPos;

        // Failsafe 2: Timeout if stuck on same block too long (5 seconds)
        if (currentTarget != null && miningStartTime > 0 && (System.currentTimeMillis() - miningStartTime) > 5000) {
            BLACKLIST_TEMP.add(currentTarget);
            cancelMining(mc);
        }

        if (currentTarget == null) {
            findAndAimBestTarget(mc);
        } else {
            mineAndValidateTarget(mc);
        }
    }

    private static void findAndAimBestTarget(Minecraft mc) {
        int r = 5;
        BlockPos playerPos = mc.player.blockPosition();
        Vec3 currentPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Iterate through requested blocks IN ORDER of priority
        // The target block from config takes top priority
        List<String> blockIds = new ArrayList<>();
        String primaryBlock = FusionConfig.getAutoMinerTargetBlock();
        if (primaryBlock != null && !primaryBlock.trim().isEmpty()) {
            blockIds.add(primaryBlock.trim());
        }
        // Add the rest from the block list, skipping duplicates
        for (String id : FusionConfig.getAutoMinerBlocks()) {
            if (!blockIds.contains(id)) {
                blockIds.add(id);
            }
        }
        for (String id : blockIds) {
            Block targetBlock = null;
            try {
                ResourceLocation resLoc = ResourceLocation.parse(id);
                if (resLoc != null && BuiltInRegistries.BLOCK.containsKey(resLoc)) {
                    targetBlock = BuiltInRegistries.BLOCK.getValue(resLoc);
                }
            } catch (Throwable t) {
                // Ignore invalid IDs
            }

            if (targetBlock == null)
                continue;

            BlockPos bestTarget = null;
            double minAngleDist = Double.MAX_VALUE;

            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = playerPos.offset(x, y, z);

                        if (Vec3.atCenterOf(pos).distanceToSqr(currentPos) > 4.5 * 4.5)
                            continue;
                        if (BLACKLIST_TEMP.contains(pos))
                            continue;

                        Block block = mc.level.getBlockState(pos).getBlock();

                        if (block == targetBlock) {
                            Vec3 targetCenter = Vec3.atCenterOf(pos);
                            Vec3[] pointsToTest = {
                                    targetCenter,
                                    targetCenter.add(0.45, 0, 0),
                                    targetCenter.add(-0.45, 0, 0),
                                    targetCenter.add(0, 0.45, 0),
                                    targetCenter.add(0, -0.45, 0),
                                    targetCenter.add(0, 0, 0.45),
                                    targetCenter.add(0, 0, -0.45)
                            };

                            Vec3 validPoint = null;
                            for (Vec3 p : pointsToTest) {
                                BlockHitResult sightCheck = mc.level.clip(new ClipContext(
                                        mc.player.getEyePosition(),
                                        p,
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.NONE,
                                        mc.player));

                                if (sightCheck.getType() == HitResult.Type.MISS
                                        || sightCheck.getBlockPos().equals(pos)) {
                                    validPoint = p;
                                    break;
                                }
                            }

                            if (validPoint == null) {
                                continue;
                            }

                            Vec2 targetAngle = getYawPitch(mc.player.getEyePosition(), validPoint);
                            double angleDist = getAngleDistance(mc.player.getYRot(), mc.player.getXRot(),
                                    targetAngle.x, targetAngle.y);

                            if (angleDist < minAngleDist) {
                                minAngleDist = angleDist;
                                bestTarget = pos;
                            }
                        }
                    }
                }
            }

            if (bestTarget != null) {
                Vec3 targetCenter = Vec3.atCenterOf(bestTarget);
                Vec2 angle = getYawPitch(mc.player.getEyePosition(), targetCenter);

                smoothLook(mc, angle);

                currentTarget = bestTarget;
                precisionTarget = null;
                aimWaitTicks = 0;
                stuckTicks = 0;
                return; // Priority matched!
            }
        }
    }

    private static void mineAndValidateTarget(Minecraft mc) {
        if (currentTarget != null && mc.player != null) {
            if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(currentTarget)) > 4.5) {
                BLACKLIST_TEMP.add(currentTarget);
                cancelMining(mc);
                return;
            }
            // Keep aligning camera toward the block
            Vec3 targetCenter = Vec3.atCenterOf(currentTarget);
            Vec2 angle = getYawPitch(mc.player.getEyePosition(), targetCenter);
            smoothLook(mc, angle);
        }

        // Verify block is still a whitelisted type
        if (!getWhitelistedBlocks().contains(mc.level.getBlockState(currentTarget).getBlock())) {
            cancelMining(mc);
            return;
        }

        // Use crosshair to validate exact line-of-sight
        HitResult hit = mc.hitResult;

        if (hit != null && hit.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) hit).getBlockPos().equals(currentTarget)) {
            // Raycast hit our target, reset aim wait
            aimWaitTicks = 0;

            if (miningStartTime == 0) {
                miningStartTime = System.currentTimeMillis();
            }
            mc.options.keyAttack.setDown(true);

            // Increment stuck check to abandon if mining takes too long
            stuckTicks++;
            if (stuckTicks > 40) { // 2 seconds stuck mining max
                BLACKLIST_TEMP.add(currentTarget);
                cancelMining(mc);
            }
        } else {
            // Raycast missed
            aimWaitTicks++;
            if (aimWaitTicks > 10) { // Aggressive on line-of-sight failure
                BLACKLIST_TEMP.add(currentTarget);
                cancelMining(mc);
            }
        }
    }

    private static void cancelMining(Minecraft mc) {
        if (mc.options != null) {
            mc.options.keyAttack.setDown(false);
        }
        if (currentTarget != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        currentTarget = null;
        precisionTarget = null;
        miningStartTime = 0;
        aimWaitTicks = 0;
        stuckTicks = 0;
    }

    // Dynamically fetch whitelisted blocks from config
    private static Set<Block> getWhitelistedBlocks() {
        Set<Block> blocks = new HashSet<>();
        for (String id : FusionConfig.getAutoMinerBlocks()) {
            try {
                ResourceLocation resLoc = ResourceLocation.parse(id);
                if (resLoc != null && BuiltInRegistries.BLOCK.containsKey(resLoc)) {
                    blocks.add(BuiltInRegistries.BLOCK.getValue(resLoc));
                }
            } catch (Throwable t) {
                // Silently ignore invalid IDs
            }
        }
        return blocks;
    }

    public static Vec2 getYawPitch(Vec3 eyePos, Vec3 target) {
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        return new Vec2(yaw, pitch);
    }

    private static void smoothLook(Minecraft mc, Vec2 targetAngle) {
        if (mc.player == null)
            return;
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        Vec2 target = targetAngle;
        if (FusionConfig.isAutoMinerPrecision() && precisionTarget != null) {
            target = getYawPitch(mc.player.getEyePosition(), precisionTarget);
        }

        if (getAngleDistance(currentYaw, currentPitch, target.x, target.y) >= 1.0) {
            float newYaw = Mth.rotLerp(0.45f, currentYaw, target.x);
            float newPitch = Mth.rotLerp(0.45f, currentPitch, target.y);

            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);
        }
    }

    public static double getAngleDistance(float yaw1, float pitch1, float yaw2, float pitch2) {
        float dyaw = Mth.wrapDegrees(yaw1 - yaw2);
        float dpitch = pitch1 - pitch2;
        return Math.sqrt(dyaw * dyaw + dpitch * dpitch);
    }
}
