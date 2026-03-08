package moi.fusion_mod.macros;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.hud.ZoneInfoHud;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * FarmHelper — Waypoint-following crop farming macro + pest killer.
 * Ported from FarmAuto.py's Maps_waypoints() function to Fabric 1.21.10 Mojang mappings.
 *
 * Movement uses vector projection onto forward/right axes derived from the fixed yaw,
 * pressing W/A/S/D based on dot products to navigate between waypoints.
 * Restricted to Garden area only.
 */
public class FarmHelper {

    // ══════════════════════════════════════════════════════════════════════
    // State Machine
    // ══════════════════════════════════════════════════════════════════════

    public enum State {
        NONE,
        /** Actively navigating toward the current waypoint. */
        NAVIGATING,
        /** Pausing between waypoints to kill momentum (turn delay). */
        TURN_DELAY,
        /** Drop-down delay when next waypoint is significantly lower. */
        DROP_DELAY,
        /** Pest detected — aiming and vacuuming. */
        PEST_KILLING
    }

    private static boolean enabled = false;
    private static State currentState = State.NONE;
    /** State to return to after pest killing finishes. */
    private static State stateBeforePest = State.NONE;

    /** The active crop name (used for AutoTool selection and FarmConfig loading). */
    private static String activeCropName = null;

    // ── Fixed camera orientation ────────────────────────────────────────
    private static float yaw = 0f;
    private static float pitch = 3f;

    // ── Forward and Right vectors (computed once from yaw) ──────────────
    private static double fx, fz; // Forward vector (W key direction)
    private static double rx, rz; // Right vector (D key direction)

    // ── Waypoint system ─────────────────────────────────────────────────
    private static List<Vec3> waypoints = new ArrayList<>();
    private static int currentWaypointIndex = 0;

    // ── Stuck detection ─────────────────────────────────────────────────
    private static double prevX = 0;
    private static double prevZ = 0;
    private static int stuckTicks = 0;

    // ── Turn delay counter (4 ticks = 0.2s at 20 TPS) ──────────────────
    private static int turnDelayTicks = 0;
    private static final int TURN_DELAY_DURATION = 4;

    // ── Drop-down delay counter (4 ticks = 0.2s) ───────────────────────
    private static int dropDelayTicks = 0;
    private static final int DROP_DELAY_DURATION = 4;
    /** Y difference threshold to trigger drop-down delay. */
    private static final double DROP_Y_THRESHOLD = 0.5;

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
     * Inject a waypoint list and camera angles from an external source.
     * Index 0 is the "start" position; movement begins at index 1.
     */
    public static void setWaypoints(List<Vec3> newWaypoints, float newYaw, float newPitch) {
        waypoints = new ArrayList<>(newWaypoints);
        yaw = newYaw;
        pitch = newPitch;
    }

    /**
     * Load waypoints from FarmConfig for the given crop name.
     * Returns true if the farm was found and waypoints were loaded.
     */
    public static boolean loadFarmFromConfig(String cropName) {
        FarmConfig.FarmData farm = FarmConfig.getFarm(cropName);
        if (farm == null || farm.waypoints.size() < 2) return false;

        activeCropName = cropName;
        setWaypoints(farm.toVec3List(), farm.yaw, farm.pitch);
        return true;
    }

    /** Get the currently active crop name (null if not set). */
    public static String getActiveCropName() {
        return activeCropName;
    }

    /** Set the crop name (used by FarmSetupScreen or external callers). */
    public static void setActiveCropName(String cropName) {
        activeCropName = cropName;
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
                return;
            }

            // Check that waypoints are loaded — try auto-loading from FarmConfig if needed
            if (waypoints.size() < 2 && activeCropName != null) {
                loadFarmFromConfig(activeCropName);
            }
            if (waypoints.size() < 2) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7cFarmHelper: No waypoints loaded! Set a crop with /farmsetup or configure via the GUI."), false);
                }
                return;
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

        // ── Pest scanning (every PEST_SCAN_INTERVAL ticks) ─────────────
        pestScanCounter++;
        if (pestScanCounter >= PEST_SCAN_INTERVAL) {
            pestScanCounter = 0;
            if (currentState != State.PEST_KILLING) {
                Entity pest = findNearestPest(mc);
                if (pest != null) {
                    stateBeforePest = currentState;
                    currentState = State.PEST_KILLING;
                    pestTarget = pest;
                    pestKillTicks = 0;
                    stopAllKeys(mc);
                    AutoTool.selectVacuum(mc);
                }
            }
        }

        // ── State machine ──────────────────────────────────────────────
        switch (currentState) {
            case PEST_KILLING:
                tickPestKilling(mc);
                break;
            case TURN_DELAY:
                tickTurnDelay(mc);
                break;
            case DROP_DELAY:
                tickDropDelay(mc);
                break;
            default:
                tickNavigating(mc);
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Enable / Disable
    // ══════════════════════════════════════════════════════════════════════

    private static void onEnable(Minecraft mc) {
        currentState = State.NAVIGATING;
        stateBeforePest = State.NONE;
        stuckTicks = 0;
        turnDelayTicks = 0;
        dropDelayTicks = 0;
        pestTarget = null;
        pestScanCounter = 0;

        // Set pitch from config (may be overridden by setWaypoints)
        if (pitch == 3f) {
            pitch = FusionConfig.getFarmHelperCustomPitch();
        }

        // Set yaw: use setWaypoints value if already set, else config, else snap to 90
        float configYaw = FusionConfig.getFarmHelperCustomYaw();
        if (configYaw != 0f && yaw == 0f) {
            yaw = configYaw;
        } else if (yaw == 0f && mc.player != null) {
            yaw = getClosest90(mc.player.getYRot());
        }

        // Compute forward and right vectors from yaw (done ONCE, like the Python)
        computeDirectionVectors();

        // Start at waypoint index 1 (index 0 is the start position)
        currentWaypointIndex = 1;

        // Apply initial rotation and record position
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            prevX = mc.player.getX();
            prevZ = mc.player.getZ();
        }

        // Hold attack throughout farming
        mc.options.keyAttack.setDown(true);

        // Auto-select the correct farming tool for this crop
        if (activeCropName != null) {
            if (!AutoTool.selectToolForCrop(mc, activeCropName)) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7eWarning: No tool found for " + activeCropName + " in hotbar"), false);
                }
            }
        }

        if (mc.player != null) {
            String cropInfo = activeCropName != null ? " [" + activeCropName + "]" : "";
            mc.player.displayClientMessage(
                    Component.literal("\u00A7aFarmHelper enabled" + cropInfo + " (" + waypoints.size() + " waypoints)"), true);
        }
    }

    private static void onDisable(Minecraft mc) {
        stopAllKeys(mc);
        currentState = State.NONE;
        stuckTicks = 0;
        turnDelayTicks = 0;
        dropDelayTicks = 0;
        pestTarget = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("\u00A7cFarmHelper disabled"), true);
        }
    }

    /**
     * Compute the forward (W) and right (D) direction vectors from the fixed yaw.
     * These are computed once when farming starts, matching the Python behavior.
     */
    private static void computeDirectionVectors() {
        double rad = Math.toRadians(yaw);
        fx = -Math.sin(rad);
        fz = Math.cos(rad);

        double radR = Math.toRadians(yaw + 90);
        rx = -Math.sin(radR);
        rz = Math.cos(radR);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Waypoint Navigation — Vector Projection Movement
    // ══════════════════════════════════════════════════════════════════════

    private static void tickNavigating(Minecraft mc) {
        // ── Enforce fixed camera orientation ────────────────────────────
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        // ── Check if we need to wrap around ────────────────────────────
        if (currentWaypointIndex >= waypoints.size()) {
            // Infinite loop: wrap back to index 1 (skip start waypoint)
            currentWaypointIndex = 1;
        }

        Vec3 target = waypoints.get(currentWaypointIndex);
        double dx = target.x - px;
        double dz = target.z - pz;
        double dist2D = Math.sqrt(dx * dx + dz * dz);

        // ── Stuck detection ────────────────────────────────────────────
        if (Math.abs(px - prevX) < 0.005 && Math.abs(pz - prevZ) < 0.005) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        prevX = px;
        prevZ = pz;

        // ── Waypoint completion criteria ───────────────────────────────
        // Reached if: very close (0.6) OR reasonably close (2.0) and stuck for 2+ ticks
        if (dist2D <= 0.6 || (dist2D <= 2.0 && stuckTicks >= 2)) {
            // Waypoint reached — enter turn delay
            stopMovementKeys(mc);
            stuckTicks = 0;
            turnDelayTicks = 0;
            currentState = State.TURN_DELAY;
            return;
        }

        // ── Vector projection: compute which keys to press ─────────────
        double fwdVal = dx * fx + dz * fz;   // dot product onto forward axis
        double rightVal = dx * rx + dz * rz; // dot product onto right axis

        // Dead zone of 0.1 to prevent jitter
        mc.options.keyUp.setDown(fwdVal > 0.1);
        mc.options.keyDown.setDown(fwdVal < -0.1);
        mc.options.keyRight.setDown(rightVal > 0.1);
        mc.options.keyLeft.setDown(rightVal < -0.1);

        // Keep attacking throughout
        mc.options.keyAttack.setDown(true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Turn Delay — Kill momentum between waypoints (4 ticks = 0.2s)
    // ══════════════════════════════════════════════════════════════════════

    private static void tickTurnDelay(Minecraft mc) {
        // Enforce fixed camera even during delay
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        // All movement keys are already released; just count ticks
        turnDelayTicks++;

        if (turnDelayTicks >= TURN_DELAY_DURATION) {
            turnDelayTicks = 0;

            // Check if next waypoint requires a drop-down delay
            int prevIndex = currentWaypointIndex;
            currentWaypointIndex++;

            // Wrap around for the check
            int nextIdx = currentWaypointIndex;
            if (nextIdx >= waypoints.size()) {
                nextIdx = 1; // will wrap on next tick
            }

            // Check drop-down: if next waypoint Y is lower by > 0.5 blocks
            Vec3 prevWp = waypoints.get(prevIndex);
            if (nextIdx < waypoints.size()) {
                Vec3 nextWp = waypoints.get(nextIdx);
                if (nextWp.y < prevWp.y - DROP_Y_THRESHOLD) {
                    dropDelayTicks = 0;
                    currentState = State.DROP_DELAY;
                    return;
                }
            }

            // No drop needed — resume navigating
            currentState = State.NAVIGATING;
            // Re-enable attack
            mc.options.keyAttack.setDown(true);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Drop-Down Delay — Extra pause when dropping to a lower waypoint
    // ══════════════════════════════════════════════════════════════════════

    private static void tickDropDelay(Minecraft mc) {
        // Enforce fixed camera
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        dropDelayTicks++;
        if (dropDelayTicks >= DROP_DELAY_DURATION) {
            dropDelayTicks = 0;
            currentState = State.NAVIGATING;
            mc.options.keyAttack.setDown(true);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pest Killing (preserved from original)
    // ══════════════════════════════════════════════════════════════════════

    private static void tickPestKilling(Minecraft mc) {
        if (pestTarget == null || !pestTarget.isAlive() || pestTarget.isRemoved()) {
            // Pest is dead or gone, return to farming
            pestTarget = null;
            currentState = (stateBeforePest != State.PEST_KILLING && stateBeforePest != State.NONE)
                    ? stateBeforePest : State.NAVIGATING;
            stuckTicks = 0;
            // Re-select farming tool for the active crop
            if (activeCropName != null) {
                AutoTool.selectToolForCrop(mc, activeCropName);
            }
            // Re-enable attack for farming
            mc.options.keyAttack.setDown(true);
            return;
        }

        // Check distance
        double dist = mc.player.distanceToSqr(pestTarget);
        if (dist > 20 * 20) {
            // Too far, give up
            pestTarget = null;
            currentState = (stateBeforePest != State.PEST_KILLING && stateBeforePest != State.NONE)
                    ? stateBeforePest : State.NAVIGATING;
            stuckTicks = 0;
            // Re-select farming tool for the active crop
            if (activeCropName != null) {
                AutoTool.selectToolForCrop(mc, activeCropName);
            }
            mc.options.keyAttack.setDown(true);
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
            currentState = (stateBeforePest != State.PEST_KILLING && stateBeforePest != State.NONE)
                    ? stateBeforePest : State.NAVIGATING;
            stuckTicks = 0;
            // Re-select farming tool for the active crop
            if (activeCropName != null) {
                AutoTool.selectToolForCrop(mc, activeCropName);
            }
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
    // Utility
    // ══════════════════════════════════════════════════════════════════════

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
