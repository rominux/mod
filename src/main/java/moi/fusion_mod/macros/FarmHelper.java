package moi.fusion_mod.macros;

import moi.fusion_mod.config.FusionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * FarmHelper — simplified S-Shape vertical crop farming macro + basic pest killer.
 * Ported from FarmHelper v2 (1.8.9 Forge) to Fabric 1.21.10 Mojang mappings.
 *
 * Only implements core farming logic:
 *  - S-Shape vertical crop: walk LEFT/RIGHT with attack, switch lane on wall
 *  - Yaw/Pitch setup from config (or auto nearest 90-degree)
 *  - Key simulation via mc.options.key*.setDown()
 *  - Basic pest detection (armor stands with skull textures) + aim & vacuum
 *
 * Does NOT implement: failsafes, discord webhooks, remote controls, custom GUIs,
 * rotation handlers, FlyPathFinder, anti-stuck, warping, plot management.
 */
public class FarmHelper {

    // ══════════════════════════════════════════════════════════════════════
    // State Machine
    // ══════════════════════════════════════════════════════════════════════

    public enum State {
        NONE,
        LEFT,
        RIGHT,
        SWITCHING_LANE,
        PEST_KILLING
    }

    public enum ChangeLaneDirection {
        FORWARD,
        BACKWARD
    }

    private static boolean enabled = false;
    private static State currentState = State.NONE;
    private static State previousState = State.NONE;
    private static ChangeLaneDirection changeLaneDir = null;

    private static float yaw = 0f;
    private static float pitch = 3f;
    private static boolean yawSet = false;

    // Pest killing
    private static Entity pestTarget = null;
    private static int pestAimTicks = 0;
    private static int pestKillTicks = 0;
    private static final int PEST_SCAN_INTERVAL = 40; // every 2 seconds
    private static int pestScanCounter = 0;

    // Known pest skull texture prefixes (Base64 encoded skin data)
    // From FarmHelper PestsDestroyer — these are the first ~40 chars of each texture
    private static final String[] PEST_TEXTURE_PREFIXES = {
            "ewogICJ0aW1lc3RhbXAiIDogMTcyMzE3OTc4", // Beetle
            "ewogICJ0aW1lc3RhbXAiIDogMTcyMzE3OTgx", // Cricket
            "ewogICJ0aW1lc3RhbXAiIDogMTY5NzQ3MDQ1OT", // Earthworm
            "ewogICJ0aW1lc3RhbXAiIDogMTY5Njk0NTA2Mz", // Fly
            "ewogICJ0aW1lc3RhbXAiIDogMTY5NzU1NzA3Nz", // Locust
            "ewogICJ0aW1lc3RhbXAiIDogMTY5Njg3MDQxOTcy", // Mite
            "ewogICJ0aW1lc3RhbXAiIDogMTY5Njk0NTAyOT", // Mosquito
            "ewogICJ0aW1lc3RhbXAiIDogMTY5Njg3MDQwNT", // Moth
            "ewogICJ0aW1lc3RhbXAiIDogMTYxODQxOTcwMT", // Rat
            "ewogICJ0aW1lc3RhbXAiIDogMTY5NzQ3MDQ0Mz", // Slug
            "ewogICJ0aW1lc3RhbXAiIDogMTc2MDQ1MDQxOTYx", // Praying Mantis
            "ewogICJ0aW1lc3RhbXAiIDogMTc2MDQ1MDQyMj", // Firefly
            "ewogICJ0aW1lc3RhbXAiIDogMTc2MDQ1MDQxODQz"  // Dragonfly
    };

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        enabled = !enabled;
        if (enabled) {
            onEnable(mc);
        } else {
            onDisable(mc);
        }
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null)
            return;

        // Pest scanning (every PEST_SCAN_INTERVAL ticks)
        pestScanCounter++;
        if (pestScanCounter >= PEST_SCAN_INTERVAL) {
            pestScanCounter = 0;
            if (currentState != State.PEST_KILLING) {
                Entity pest = findNearestPest(mc);
                if (pest != null) {
                    // Switch to pest killing mode
                    if (currentState != State.NONE) {
                        previousState = currentState;
                    }
                    currentState = State.PEST_KILLING;
                    pestTarget = pest;
                    pestAimTicks = 0;
                    pestKillTicks = 0;
                    stopAllKeys(mc);
                    // Switch to vacuum if possible
                    switchToVacuum(mc);
                }
            }
        }

        // State machine
        switch (currentState) {
            case PEST_KILLING:
                tickPestKilling(mc);
                break;
            default:
                // Normal farming
                updateState(mc);
                invokeState(mc);
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Enable / Disable
    // ══════════════════════════════════════════════════════════════════════

    private static void onEnable(Minecraft mc) {
        currentState = State.NONE;
        previousState = State.NONE;
        changeLaneDir = null;
        pestTarget = null;
        pestScanCounter = 0;

        // Set pitch from config
        pitch = FusionConfig.getFarmHelperCustomPitch();

        // Set yaw: use config if nonzero, otherwise snap to nearest 90 degrees
        float configYaw = FusionConfig.getFarmHelperCustomYaw();
        if (configYaw != 0f) {
            yaw = configYaw;
            yawSet = true;
        } else if (mc.player != null) {
            yaw = getClosest90(mc.player.getYRot());
            yawSet = true;
        }

        // Apply initial rotation
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("\u00A7aFarmHelper enabled"), true);
        }
    }

    private static void onDisable(Minecraft mc) {
        stopAllKeys(mc);
        currentState = State.NONE;
        pestTarget = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("\u00A7cFarmHelper disabled"), true);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // S-Shape Vertical Crop — State Update
    // ══════════════════════════════════════════════════════════════════════

    private static void updateState(Minecraft mc) {
        switch (currentState) {
            case LEFT:
            case RIGHT: {
                // Check if we hit a wall (can't go further in current direction)
                boolean canContinue;
                if (currentState == State.LEFT) {
                    canContinue = isWalkable(mc, getRelativeBlockPos(mc, -1, 0, 0));
                } else {
                    canContinue = isWalkable(mc, getRelativeBlockPos(mc, 1, 0, 0));
                }

                if (!canContinue) {
                    // Check if we can switch lane (forward or backward)
                    boolean frontWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, 1));
                    boolean backWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, -1));

                    if (frontWalkable || backWalkable) {
                        changeState(State.SWITCHING_LANE);
                    } else {
                        // Try the other direction
                        if (currentState == State.LEFT) {
                            changeState(State.RIGHT);
                        } else {
                            changeState(State.LEFT);
                        }
                    }
                }
                break;
            }
            case SWITCHING_LANE: {
                // After switching lane, go the opposite direction
                boolean leftWalkable = isWalkable(mc, getRelativeBlockPos(mc, -1, 0, 0));
                boolean rightWalkable = isWalkable(mc, getRelativeBlockPos(mc, 1, 0, 0));

                if (leftWalkable) {
                    changeState(State.LEFT);
                } else if (rightWalkable) {
                    changeState(State.RIGHT);
                } else {
                    changeState(State.NONE);
                }
                break;
            }
            case NONE: {
                // Determine initial direction
                changeState(calculateDirection(mc));
                break;
            }
            default:
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // S-Shape Vertical Crop — State Invocation (key presses)
    // ══════════════════════════════════════════════════════════════════════

    private static void invokeState(Minecraft mc) {
        switch (currentState) {
            case LEFT:
                // Hold A + Attack (+ optionally W)
                holdThese(mc, mc.options.keyLeft, mc.options.keyAttack);
                break;
            case RIGHT:
                // Hold D + Attack (+ optionally W)
                holdThese(mc, mc.options.keyRight, mc.options.keyAttack);
                break;
            case SWITCHING_LANE: {
                if (changeLaneDir == null) {
                    boolean frontWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, 1));
                    boolean backWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, -1));
                    if (frontWalkable) {
                        changeLaneDir = ChangeLaneDirection.FORWARD;
                    } else if (backWalkable) {
                        changeLaneDir = ChangeLaneDirection.BACKWARD;
                    } else {
                        changeState(State.NONE);
                        return;
                    }
                }
                if (changeLaneDir == ChangeLaneDirection.FORWARD) {
                    holdThese(mc, mc.options.keyUp, mc.options.keySprint);
                } else {
                    holdThese(mc, mc.options.keyDown);
                }
                break;
            }
            case NONE:
                changeState(calculateDirection(mc));
                break;
            default:
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Direction calculation
    // ══════════════════════════════════════════════════════════════════════

    private static State calculateDirection(Minecraft mc) {
        // Check if right or left has crops (non-air blocks at feet level offset)
        boolean rightWalkable = isWalkable(mc, getRelativeBlockPos(mc, 1, 0, 0));
        boolean leftWalkable = isWalkable(mc, getRelativeBlockPos(mc, -1, 0, 0));

        if (rightWalkable) {
            return State.RIGHT;
        } else if (leftWalkable) {
            return State.LEFT;
        }
        // Default: try right
        return State.RIGHT;
    }

    private static void changeState(State newState) {
        if (newState == currentState) return;
        previousState = currentState;
        currentState = newState;
        if (newState == State.SWITCHING_LANE) {
            changeLaneDir = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pest Killing
    // ══════════════════════════════════════════════════════════════════════

    private static void tickPestKilling(Minecraft mc) {
        if (pestTarget == null || !pestTarget.isAlive() || pestTarget.isRemoved()) {
            // Pest is dead or gone, return to farming
            pestTarget = null;
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
            return;
        }

        // Check distance
        double dist = mc.player.distanceToSqr(pestTarget);
        if (dist > 20 * 20) {
            // Too far, give up
            pestTarget = null;
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
            return;
        }

        // Aim at pest
        Vec3 pestPos = new Vec3(pestTarget.getX(), pestTarget.getY() + pestTarget.getEyeHeight(), pestTarget.getZ());
        Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), pestPos);

        // Smooth look
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        double angleDist = AutoMiner.getAngleDistance(currentYaw, currentPitch, angle.x, angle.y);

        if (angleDist >= 1.0) {
            float newYaw = Mth.rotLerp(0.4f, currentYaw, angle.x);
            float newPitch = Mth.rotLerp(0.4f, currentPitch, angle.y);
            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);
        }

        // Hold attack (vacuum suction)
        mc.options.keyAttack.setDown(true);

        pestKillTicks++;
        if (pestKillTicks > 200) { // 10 seconds timeout
            // Give up on this pest
            pestTarget = null;
            mc.options.keyAttack.setDown(false);
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
        }
    }

    private static Entity findNearestPest(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || !entity.isAlive()) continue;
            if (entity.getY() < 50) continue;
            if (!(entity instanceof ArmorStand armorStand)) continue;

            // Check head item for pest skull texture
            ItemStack helmet = armorStand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            if (helmet.isEmpty()) continue;

            // Check if it has a skull with a pest texture
            if (!isPestSkull(helmet)) continue;

            double dist = mc.player.distanceToSqr(entity);
            if (dist < closestDist) {
                closestDist = dist;
                closest = entity;
            }
        }

        return closest;
    }

    /**
     * Check if an ItemStack is a player skull with a known pest texture.
     * In 1.21.10 the skull profile data is stored in components.
     */
    private static boolean isPestSkull(ItemStack stack) {
        try {
            // Get the NBT/component string representation and check for pest textures
            String itemStr = stack.toString();
            // Also try component data
            var components = stack.getComponents();
            if (components == null) return false;

            String compStr = components.toString();
            for (String prefix : PEST_TEXTURE_PREFIXES) {
                if (compStr.contains(prefix)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void switchToVacuum(Minecraft mc) {
        if (mc.player == null) return;
        // Search hotbar for an item containing "Vacuum" in its name
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().contains("Vacuum")) {
                mc.player.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Key Simulation Utilities
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Releases all movement + attack keys, then presses only the specified ones.
     * Mirrors FarmHelper's KeyBindUtils.holdThese() pattern.
     */
    private static void holdThese(Minecraft mc, net.minecraft.client.KeyMapping... keys) {
        // Release all movement keys first
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);

        // Press only the requested keys
        Set<net.minecraft.client.KeyMapping> keySet = new HashSet<>(Arrays.asList(keys));
        for (net.minecraft.client.KeyMapping key : keySet) {
            if (key != null) {
                key.setDown(true);
            }
        }
    }

    private static void stopAllKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Block / Walkability Utilities
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get a block position relative to the player's facing direction.
     * dx = right(+)/left(-), dy = up/down, dz = forward(+)/backward(-)
     * relative to the player's yaw.
     */
    private static BlockPos getRelativeBlockPos(Minecraft mc, int dx, int dy, int dz) {
        if (mc.player == null) return BlockPos.ZERO;

        // Use the macro yaw for direction calculation
        float radYaw = (float) Math.toRadians(yaw);
        float sinYaw = Mth.sin(radYaw);
        float cosYaw = Mth.cos(radYaw);

        // Forward = -sinYaw, cosYaw (Minecraft convention)
        // Right = cosYaw, sinYaw
        double worldX = mc.player.getX() + (dx * cosYaw + dz * (-sinYaw));
        double worldZ = mc.player.getZ() + (dx * sinYaw + dz * cosYaw);
        double worldY = mc.player.getY() + dy;

        return new BlockPos((int) Math.floor(worldX), (int) Math.floor(worldY), (int) Math.floor(worldZ));
    }

    /**
     * Check if a block position is walkable (air at feet and head level).
     */
    private static boolean isWalkable(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        // Check that the block at feet level and head level are both air
        boolean feetAir = mc.level.getBlockState(pos).getBlock() instanceof AirBlock;
        boolean headAir = mc.level.getBlockState(pos.above()).getBlock() instanceof AirBlock;
        return feetAir && headAir;
    }

    /**
     * Snap an angle to the nearest 90-degree increment (0, 90, 180, 270 / -90 / -180).
     */
    private static float getClosest90(float angle) {
        float wrapped = Mth.wrapDegrees(angle);
        float[] snaps = {0, 90, 180, -90, -180};
        float closest = snaps[0];
        float minDist = Math.abs(Mth.wrapDegrees(wrapped - snaps[0]));
        for (int i = 1; i < snaps.length; i++) {
            float dist = Math.abs(Mth.wrapDegrees(wrapped - snaps[i]));
            if (dist < minDist) {
                minDist = dist;
                closest = snaps[i];
            }
        }
        return closest;
    }
}
