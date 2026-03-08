package moi.fusion_mod.macros;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.hud.ZoneInfoHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * FarmHelper — collision-based S-Shape vertical crop farming macro + basic pest killer.
 * Ported from FarmHelper v2 (1.8.9 Forge) to Fabric 1.21.10 Mojang mappings.
 *
 * Movement uses direct key presses (keyLeft / keyRight) with collision detection:
 *   - Hold LEFT or RIGHT (relative to yaw) while holding attack to break crops
 *   - When the player stops moving (stuck against wall) for 10+ ticks, switch lane
 *   - Lane switch holds forward/backward until 1 block of distance is covered
 *   - Then reverse direction (LEFT <-> RIGHT)
 *
 * No waypoints, no vector projection — just direct strafing with wall detection.
 * Restricted to Garden area only.
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

    private static boolean enabled = false;
    private static State currentState = State.NONE;
    private static State previousState = State.NONE;

    // Fixed camera orientation for the farming session
    private static float yaw = 0f;
    private static float pitch = 3f;

    // ── Collision detection ─────────────────────────────────────────────
    // Previous position to measure movement delta each tick
    private static double prevX = 0;
    private static double prevY = 0;
    private static double prevZ = 0;
    // Counter: how many consecutive ticks the player has been stuck
    private static int stuckTicks = 0;
    // Threshold: consider "stuck" after this many ticks of near-zero movement
    private static final int STUCK_THRESHOLD = 10; // 0.5 seconds

    // ── Lane switching ──────────────────────────────────────────────────
    private static boolean switchForward = true;
    private static double laneStartX = 0;
    private static double laneStartZ = 0;

    // ── Pest killing ────────────────────────────────────────────────────
    private static Entity pestTarget = null;
    private static int pestKillTicks = 0;
    private static int pestScanCounter = 0;
    private static final int PEST_SCAN_INTERVAL = 40; // every 2 seconds

    // Known pest skull texture prefixes (Base64 encoded skin data)
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

    /**
     * Toggle the farm helper. Checks that the player is in the Garden before enabling.
     */
    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled) {
            // Check if we are in the Garden
            String area = ZoneInfoHud.getCurrentAreaName().toLowerCase();
            if (!area.contains("garden") && !area.contains("plot") && !area.contains("barn")) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7cFarmHelper can only be used in the Garden!"), false);
                }
                return; // Do not enable
            }
            enabled = true;
            onEnable(mc);
        } else {
            enabled = false;
            onDisable(mc);
        }
    }

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null)
            return;

        // ── Enforce fixed camera orientation ────────────────────────────
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        // ── Pest scanning (every PEST_SCAN_INTERVAL ticks) ─────────────
        pestScanCounter++;
        if (pestScanCounter >= PEST_SCAN_INTERVAL) {
            pestScanCounter = 0;
            if (currentState != State.PEST_KILLING) {
                Entity pest = findNearestPest(mc);
                if (pest != null) {
                    if (currentState != State.NONE) {
                        previousState = currentState;
                    }
                    currentState = State.PEST_KILLING;
                    pestTarget = pest;
                    pestKillTicks = 0;
                    stuckTicks = 0;
                    stopAllKeys(mc);
                    switchToVacuum(mc);
                }
            }
        }

        // ── State machine ──────────────────────────────────────────────
        switch (currentState) {
            case PEST_KILLING:
                tickPestKilling(mc);
                break;
            default:
                tickFarming(mc);
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Enable / Disable
    // ══════════════════════════════════════════════════════════════════════

    private static void onEnable(Minecraft mc) {
        currentState = State.NONE;
        previousState = State.NONE;
        stuckTicks = 0;
        pestTarget = null;
        pestScanCounter = 0;

        // Set pitch from config
        pitch = FusionConfig.getFarmHelperCustomPitch();

        // Set yaw: use config if nonzero, otherwise snap to nearest 90 degrees
        float configYaw = FusionConfig.getFarmHelperCustomYaw();
        if (configYaw != 0f) {
            yaw = configYaw;
        } else if (mc.player != null) {
            yaw = getClosest90(mc.player.getYRot());
        }

        // Apply initial rotation and record position
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            prevX = mc.player.getX();
            prevY = mc.player.getY();
            prevZ = mc.player.getZ();
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("\u00A7aFarmHelper enabled"), true);
        }
    }

    private static void onDisable(Minecraft mc) {
        stopAllKeys(mc);
        currentState = State.NONE;
        stuckTicks = 0;
        pestTarget = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("\u00A7cFarmHelper disabled"), true);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // S-Shape Farming — Collision-Based Movement
    // ══════════════════════════════════════════════════════════════════════

    private static void tickFarming(Minecraft mc) {
        double playerX = mc.player.getX();
        double playerY = mc.player.getY();
        double playerZ = mc.player.getZ();

        // ── If NONE, pick initial direction and start ──────────────────
        if (currentState == State.NONE) {
            currentState = calculateInitialDirection(mc);
            stuckTicks = 0;
            prevX = playerX;
            prevY = playerY;
            prevZ = playerZ;
        }

        // ── Handle LEFT / RIGHT states ─────────────────────────────────
        if (currentState == State.LEFT || currentState == State.RIGHT) {
            // Hold the strafe key and attack
            stopMovementKeys(mc);
            if (currentState == State.LEFT) {
                mc.options.keyLeft.setDown(true);
            } else {
                mc.options.keyRight.setDown(true);
            }
            mc.options.keyAttack.setDown(true);

            // Collision detection: measure how much the player actually moved
            double movementSqr = squaredDistance2D(playerX, playerZ, prevX, prevZ);
            if (movementSqr < 0.0001) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }

            // If stuck for more than STUCK_THRESHOLD ticks, end of row reached
            if (stuckTicks >= STUCK_THRESHOLD) {
                stopAllKeys(mc);
                stuckTicks = 0;

                // Transition to SWITCHING_LANE
                previousState = currentState;
                currentState = State.SWITCHING_LANE;
                laneStartX = playerX;
                laneStartZ = playerZ;
                decideLaneDirection(mc);
            }

            prevX = playerX;
            prevY = playerY;
            prevZ = playerZ;
            return;
        }

        // ── Handle SWITCHING_LANE state ────────────────────────────────
        if (currentState == State.SWITCHING_LANE) {
            stopMovementKeys(mc);
            mc.options.keyAttack.setDown(false);

            // Hold forward or backward to move to the next crop row
            if (switchForward) {
                mc.options.keyUp.setDown(true);
            } else {
                mc.options.keyDown.setDown(true);
            }
            mc.options.keySprint.setDown(true);

            // Measure distance moved during the lane switch
            double switchDist = Math.sqrt(squaredDistance2D(playerX, playerZ, laneStartX, laneStartZ));

            if (switchDist >= 1.0) {
                // Done switching — go the opposite direction
                stopAllKeys(mc);
                stuckTicks = 0;

                if (previousState == State.LEFT) {
                    currentState = State.RIGHT;
                } else {
                    currentState = State.LEFT;
                }
            }

            prevX = playerX;
            prevY = playerY;
            prevZ = playerZ;
        }
    }

    /**
     * Determine initial farming direction (LEFT or RIGHT).
     * Checks which side has more walkable space.
     */
    private static State calculateInitialDirection(Minecraft mc) {
        boolean rightWalkable = isWalkable(mc, getRelativeBlockPos(mc, 1, 0, 0));
        boolean leftWalkable = isWalkable(mc, getRelativeBlockPos(mc, -1, 0, 0));

        if (rightWalkable && !leftWalkable) return State.RIGHT;
        if (leftWalkable && !rightWalkable) return State.LEFT;
        // Default: try right
        return State.RIGHT;
    }

    /**
     * Decide whether to switch lane forward or backward.
     */
    private static void decideLaneDirection(Minecraft mc) {
        boolean frontWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, 1));
        boolean backWalkable = isWalkable(mc, getRelativeBlockPos(mc, 0, 0, -1));

        if (frontWalkable) {
            switchForward = true;
        } else if (backWalkable) {
            switchForward = false;
        } else {
            // Neither direction works, just try forward
            switchForward = true;
        }
    }

    /**
     * Squared 2D distance (XZ plane only).
     */
    private static double squaredDistance2D(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pest Killing
    // ══════════════════════════════════════════════════════════════════════

    private static void tickPestKilling(Minecraft mc) {
        if (pestTarget == null || !pestTarget.isAlive() || pestTarget.isRemoved()) {
            // Pest is dead or gone, return to farming
            pestTarget = null;
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
            stuckTicks = 0;
            return;
        }

        // Check distance
        double dist = mc.player.distanceToSqr(pestTarget);
        if (dist > 20 * 20) {
            // Too far, give up
            pestTarget = null;
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
            stuckTicks = 0;
            return;
        }

        // Aim at pest (temporarily override the fixed camera)
        Vec3 pestPos = new Vec3(pestTarget.getX(),
                pestTarget.getY() + pestTarget.getEyeHeight(),
                pestTarget.getZ());
        Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), pestPos);

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
            pestTarget = null;
            mc.options.keyAttack.setDown(false);
            currentState = previousState != State.PEST_KILLING ? previousState : State.NONE;
            stuckTicks = 0;
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
            ItemStack helmet = armorStand.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet.isEmpty()) continue;

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
     */
    private static boolean isPestSkull(ItemStack stack) {
        try {
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

    /** Release all keys including attack, sprint, jump. */
    private static void stopAllKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Release only movement keys (up/down/left/right/sprint), keep attack untouched. */
    private static void stopMovementKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Block / Walkability Utilities
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get a block position relative to the player's facing direction (using fixed yaw).
     * dx = right(+)/left(-), dy = up/down, dz = forward(+)/backward(-)
     */
    private static BlockPos getRelativeBlockPos(Minecraft mc, int dx, int dy, int dz) {
        if (mc.player == null) return BlockPos.ZERO;

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
        boolean feetAir = mc.level.getBlockState(pos).getBlock() instanceof AirBlock;
        boolean headAir = mc.level.getBlockState(pos.above()).getBlock() instanceof AirBlock;
        return feetAir && headAir;
    }

    /**
     * Snap an angle to the nearest 90-degree increment.
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
