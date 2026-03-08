package moi.fusion_mod.macros;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.hud.ZoneInfoHud;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FarmHelper — Waypoint-following crop farming macro + pest hunting system.
 * Ported from FarmAuto.py's Maps_waypoints() function to Fabric 1.21.10 Mojang mappings.
 *
 * Movement uses vector projection onto forward/right axes derived from the fixed yaw,
 * pressing W/A/S/D based on dot products to navigate between waypoints.
 * Restricted to Garden area only.
 *
 * Pest Hunting:
 *   When pests are detected (via PestTracker), the macro enters PEST_HUNTING with
 *   sub-phases: equip vacuum → left-click to trigger particle trail → capture
 *   ANGRY_VILLAGER particles → calculate pest direction → walk toward pest →
 *   hold right-click to vacuum → resume farming when pest dies.
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
        /** Old proximity-based pest kill (ArmorStand skull scanning). */
        PEST_KILLING,
        /** New vacuum-particle-based pest hunting with sub-phases. */
        PEST_HUNTING,
        /** Waiting for /warp garden teleport after linear farm end. */
        WARPING
    }

    /** Sub-phases for the PEST_HUNTING state. */
    private enum HuntPhase {
        /** Equip vacuum and prepare. */
        EQUIP_VACUUM,
        /** Left-click to trigger particle trail from server. */
        TRIGGER_PARTICLES,
        /** Waiting for ANGRY_VILLAGER particle chain from server. */
        WAIT_FOR_PARTICLES,
        /** Aim toward calculated pest position. */
        AIM_AT_PEST,
        /** Walk toward the estimated pest location. */
        WALK_TO_PEST,
        /** Hold right-click to vacuum the pest. */
        VACUUM_PEST
    }

    private static boolean enabled = false;
    private static State currentState = State.NONE;
    /** State to return to after pest killing/hunting finishes. */
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

    // ── Old pest killing (skull-based) ──────────────────────────────────
    private static Entity pestTarget = null;
    private static int pestKillTicks = 0;
    private static int pestScanCounter = 0;
    private static final int PEST_SCAN_INTERVAL = 40; // every 2 seconds

    // ── New pest hunting (vacuum particle-based) ────────────────────────
    private static HuntPhase huntPhase = HuntPhase.EQUIP_VACUUM;
    private static int huntTicks = 0;
    /** Maximum ticks to wait for particle chain (3 seconds). */
    private static final int PARTICLE_WAIT_TIMEOUT = 60;
    /** Maximum ticks for overall hunt attempt (15 seconds). */
    private static final int HUNT_TIMEOUT = 300;
    /** How many hunt attempts before giving up and resuming farming. */
    private static int huntAttempts = 0;
    private static final int MAX_HUNT_ATTEMPTS = 5;

    // ── Vacuum particle capture ─────────────────────────────────────────
    /** Accumulated ANGRY_VILLAGER particle positions from a single click. */
    private static final List<Vec3> particleLocations = new ArrayList<>();
    /** First particle position (must be near player). */
    private static Vec3 firstParticlePos = null;
    /** Last particle position in the chain. */
    private static Vec3 lastParticlePos = null;
    /** Calculated pest target position (projected from particle chain). */
    private static Vec3 calculatedPestPos = null;
    /** Whether we are currently collecting particles (after left-click). */
    private static boolean collectingParticles = false;
    /** Tick when particle collection started. */
    private static int particleCollectStartTick = 0;

    // ── Warping state (linear farm end) ─────────────────────────────────
    private static int warpTicks = 0;
    private static final int WARP_WAIT_DURATION = 60; // 3 seconds to teleport

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

            // Always re-detect the closest farm on every toggle press.
            if (mc.player != null) {
                // Reload config from disk to pick up any external changes
                FarmConfig.load();

                String closestCrop = null;
                double closestDist = Double.MAX_VALUE;

                for (Map.Entry<String, FarmConfig.FarmData> entry : FarmConfig.getFarms().entrySet()) {
                    FarmConfig.Waypoint startWp = entry.getValue().getStartWaypoint();
                    if (startWp == null) continue;
                    if (entry.getValue().waypoints.size() < 2) continue;

                    double dist = mc.player.position().distanceTo(startWp.toVec3());
                    if (dist < closestDist) {
                        closestDist = dist;
                        closestCrop = entry.getKey();
                    }
                }

                if (closestCrop != null && closestDist <= 5.0) {
                    loadFarmFromConfig(closestCrop);
                } else {
                    // Clear any previously loaded waypoints since we're not near any farm
                    waypoints.clear();
                    activeCropName = null;
                }
            }

            if (waypoints.size() < 2) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7cYou must stand near a farm's Start Waypoint to begin farming!"), false);
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7cFarmHelper: No waypoints loaded! Please configure a crop in the Macro Settings GUI and stand on its start point."), false);
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
        // Uses PestTracker for counts + old skull scanning as fallback
        pestScanCounter++;
        if (pestScanCounter >= PEST_SCAN_INTERVAL) {
            pestScanCounter = 0;
            if (currentState != State.PEST_KILLING && currentState != State.PEST_HUNTING
                    && currentState != State.WARPING) {
                // Check PestTracker first (tablist/scoreboard data)
                if (PestTracker.hasPests()) {
                    // Try to find a nearby pest entity for direct targeting
                    Entity pest = findNearestPest(mc);
                    if (pest != null) {
                        // Pest is visible — use old direct-kill method
                        stateBeforePest = currentState;
                        currentState = State.PEST_KILLING;
                        pestTarget = pest;
                        pestKillTicks = 0;
                        stopAllKeys(mc);
                        AutoTool.selectVacuum(mc);
                    } else {
                        // Pests exist but none visible — enter vacuum particle hunting
                        enterPestHunting(mc);
                    }
                } else {
                    // Fallback: scan for pest ArmorStands directly
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
        }

        // ── State machine ──────────────────────────────────────────────
        switch (currentState) {
            case PEST_KILLING:
                tickPestKilling(mc);
                break;
            case PEST_HUNTING:
                tickPestHunting(mc);
                break;
            case TURN_DELAY:
                tickTurnDelay(mc);
                break;
            case DROP_DELAY:
                tickDropDelay(mc);
                break;
            case WARPING:
                tickWarping(mc);
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
        huntAttempts = 0;
        resetParticleCapture();

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

        // TASK 1: Force /sethome on enable so we can warp back
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("sethome");
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
        huntAttempts = 0;
        resetParticleCapture();
        PestTracker.reset();
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

        // ── Check if we need to handle end-of-farm ─────────────────────
        if (currentWaypointIndex >= waypoints.size()) {
            handleEndOfFarm(mc);
            return;
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
    // TASK 1: End-of-Farm Logic — Loop vs Linear
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called when currentWaypointIndex >= waypoints.size().
     * Determines if the farm is looping (start ≈ end) or linear (start ≠ end).
     *
     * Looping: check pests → if pests, enter PEST_HUNTING; else loop back to index 1.
     * Linear: /warp garden → wait → check pests → restart from index 1.
     */
    private static void handleEndOfFarm(Minecraft mc) {
        Vec3 startPos = waypoints.get(0);
        Vec3 endPos = waypoints.get(waypoints.size() - 1);
        double startEndDist = startPos.distanceTo(endPos);

        if (startEndDist <= 2.0) {
            // ── Looping farm ───────────────────────────────────────────
            // Check for pests before looping
            if (PestTracker.hasPests()) {
                enterPestHunting(mc);
            } else {
                // No pests — loop back to index 1 (skip start waypoint)
                currentWaypointIndex = 1;
            }
        } else {
            // ── Linear farm ────────────────────────────────────────────
            // Warp back to garden home
            stopAllKeys(mc);
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("warp garden");
            }
            warpTicks = 0;
            currentState = State.WARPING;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Warping State — Wait after /warp garden
    // ══════════════════════════════════════════════════════════════════════

    private static void tickWarping(Minecraft mc) {
        warpTicks++;

        if (warpTicks >= WARP_WAIT_DURATION) {
            warpTicks = 0;

            // After warping, check for pests
            if (PestTracker.hasPests()) {
                enterPestHunting(mc);
            } else {
                // Restart farming from index 1
                currentWaypointIndex = 1;
                currentState = State.NAVIGATING;
                mc.options.keyAttack.setDown(true);

                // Re-select farming tool
                if (activeCropName != null) {
                    AutoTool.selectToolForCrop(mc, activeCropName);
                }

                // Re-apply camera
                if (mc.player != null) {
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(pitch);
                    prevX = mc.player.getX();
                    prevZ = mc.player.getZ();
                }
            }
        }
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
    // Old Pest Killing (skull-based proximity kill, preserved)
    // ══════════════════════════════════════════════════════════════════════

    private static void tickPestKilling(Minecraft mc) {
        if (pestTarget == null || !pestTarget.isAlive() || pestTarget.isRemoved()) {
            // Pest is dead or gone, return to farming
            exitPestState(mc);
            return;
        }

        // Check distance
        double dist = mc.player.distanceToSqr(pestTarget);
        if (dist > 20 * 20) {
            // Too far, give up
            exitPestState(mc);
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

        // Hold right-click (vacuum suction)
        mc.options.keyUse.setDown(true);

        pestKillTicks++;
        if (pestKillTicks > 200) { // 10 seconds timeout
            pestTarget = null;
            mc.options.keyUse.setDown(false);
            exitPestState(mc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TASK 4: Pest Hunting — Vacuum Particle-Based State Machine
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Enter pest hunting mode. Saves current state and begins the hunt.
     */
    private static void enterPestHunting(Minecraft mc) {
        if (currentState != State.PEST_HUNTING) {
            stateBeforePest = currentState;
        }
        currentState = State.PEST_HUNTING;
        huntPhase = HuntPhase.EQUIP_VACUUM;
        huntTicks = 0;
        resetParticleCapture();
        stopAllKeys(mc);

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7ePestHunter: Pests detected! Starting hunt..."), true);
        }
    }

    private static void tickPestHunting(Minecraft mc) {
        huntTicks++;

        // Overall hunt timeout
        if (huntTicks > HUNT_TIMEOUT) {
            huntAttempts++;
            if (huntAttempts >= MAX_HUNT_ATTEMPTS) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7cPestHunter: Max attempts reached, resuming farming."), true);
                }
                huntAttempts = 0;
                exitPestState(mc);
                return;
            }
            // Retry
            huntPhase = HuntPhase.EQUIP_VACUUM;
            huntTicks = 0;
            resetParticleCapture();
            return;
        }

        // Check if a pest entity appeared nearby (direct targeting opportunity)
        Entity nearbyPest = findNearestPest(mc);
        if (nearbyPest != null) {
            // Switch to direct kill mode
            pestTarget = nearbyPest;
            pestKillTicks = 0;
            currentState = State.PEST_KILLING;
            stopAllKeys(mc);
            AutoTool.selectVacuum(mc);
            return;
        }

        // Check if pests are gone (PestTracker says 0)
        if (!PestTracker.hasPests()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7aPestHunter: All pests cleared!"), true);
            }
            huntAttempts = 0;
            exitPestState(mc);
            return;
        }

        switch (huntPhase) {
            case EQUIP_VACUUM:
                tickHuntEquipVacuum(mc);
                break;
            case TRIGGER_PARTICLES:
                tickHuntTriggerParticles(mc);
                break;
            case WAIT_FOR_PARTICLES:
                tickHuntWaitForParticles(mc);
                break;
            case AIM_AT_PEST:
                tickHuntAimAtPest(mc);
                break;
            case WALK_TO_PEST:
                tickHuntWalkToPest(mc);
                break;
            case VACUUM_PEST:
                tickHuntVacuumPest(mc);
                break;
        }
    }

    /** Phase 1: Equip the vacuum from hotbar. */
    private static void tickHuntEquipVacuum(Minecraft mc) {
        boolean found = AutoTool.selectVacuum(mc);
        if (!found) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7cPestHunter: No vacuum found in hotbar!"), true);
            }
            exitPestState(mc);
            return;
        }
        // Wait 2 ticks for slot switch to register
        if (huntTicks >= 2) {
            huntPhase = HuntPhase.TRIGGER_PARTICLES;
        }
    }

    /** Phase 2: Left-click to trigger the server particle trail. */
    private static void tickHuntTriggerParticles(Minecraft mc) {
        // Single left-click press
        mc.options.keyAttack.setDown(true);

        // After 2 ticks, release and start listening for particles
        if (huntTicks >= 4) {
            mc.options.keyAttack.setDown(false);
            collectingParticles = true;
            particleCollectStartTick = huntTicks;
            huntPhase = HuntPhase.WAIT_FOR_PARTICLES;
        }
    }

    /** Phase 3: Wait for ANGRY_VILLAGER particle chain from the server. */
    private static void tickHuntWaitForParticles(Minecraft mc) {
        // Particles are collected asynchronously via onParticlePacket()
        int elapsed = huntTicks - particleCollectStartTick;

        if (elapsed > PARTICLE_WAIT_TIMEOUT) {
            // Timeout — no particles received
            collectingParticles = false;
            if (particleLocations.size() >= 2) {
                // We got some particles, try to calculate
                calculatePestPosition();
                huntPhase = HuntPhase.AIM_AT_PEST;
            } else {
                // No useful data — retry from trigger
                huntAttempts++;
                if (huntAttempts >= MAX_HUNT_ATTEMPTS) {
                    exitPestState(mc);
                    return;
                }
                resetParticleCapture();
                huntPhase = HuntPhase.TRIGGER_PARTICLES;
            }
            return;
        }

        // Check if we've collected enough particles to calculate
        if (particleLocations.size() >= 3 && elapsed >= 10) {
            collectingParticles = false;
            calculatePestPosition();
            if (calculatedPestPos != null) {
                huntPhase = HuntPhase.AIM_AT_PEST;
            }
        }
    }

    /** Phase 4: Aim the camera toward the calculated pest position. */
    private static void tickHuntAimAtPest(Minecraft mc) {
        if (calculatedPestPos == null || mc.player == null) {
            exitPestState(mc);
            return;
        }

        Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), calculatedPestPos);
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        double angleDist = AutoMiner.getAngleDistance(currentYaw, currentPitch, angle.x, angle.y);

        if (angleDist >= 2.0) {
            float newYaw = Mth.rotLerp(0.4f, currentYaw, angle.x);
            float newPitch = Mth.rotLerp(0.4f, currentPitch, angle.y);
            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);
        } else {
            // Aimed close enough — start walking
            huntPhase = HuntPhase.WALK_TO_PEST;
        }
    }

    /** Phase 5: Walk toward the estimated pest location. */
    private static void tickHuntWalkToPest(Minecraft mc) {
        if (calculatedPestPos == null || mc.player == null) {
            exitPestState(mc);
            return;
        }

        double dx = calculatedPestPos.x - mc.player.getX();
        double dz = calculatedPestPos.z - mc.player.getZ();
        double dist2D = Math.sqrt(dx * dx + dz * dz);

        // If close enough, start vacuuming
        float vacuumRange = AutoTool.getCurrentVacuumRange();
        if (dist2D <= vacuumRange * 0.7) {
            stopMovementKeys(mc);
            huntPhase = HuntPhase.VACUUM_PEST;
            return;
        }

        // Aim and walk toward the calculated position
        Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), calculatedPestPos);
        float newYaw = Mth.rotLerp(0.3f, mc.player.getYRot(), angle.x);
        float newPitch = Mth.rotLerp(0.3f, mc.player.getXRot(), angle.y);
        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);

        // Walk forward (toward where we're looking)
        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(dist2D > 10);
    }

    /** Phase 6: Hold right-click to vacuum the pest. */
    private static void tickHuntVacuumPest(Minecraft mc) {
        // Continuously aim at calculated position
        if (calculatedPestPos != null && mc.player != null) {
            // Check if a pest entity is now visible for precise aiming
            Entity pest = findNearestPest(mc);
            Vec3 aimTarget;
            if (pest != null) {
                aimTarget = new Vec3(pest.getX(), pest.getY() + pest.getEyeHeight(), pest.getZ());
            } else {
                aimTarget = calculatedPestPos;
            }

            Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), aimTarget);
            float newYaw = Mth.rotLerp(0.4f, mc.player.getYRot(), angle.x);
            float newPitch = Mth.rotLerp(0.4f, mc.player.getXRot(), angle.y);
            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);
        }

        // Hold right-click (vacuum suction)
        mc.options.keyUse.setDown(true);

        // Check if pest count dropped (pest died)
        // Give it 5 seconds of vacuuming before retrying
        if (huntTicks % 20 == 0) {
            PestTracker.tick(); // Force refresh
        }

        // Check after 100 ticks (5 seconds) of vacuuming
        if ((huntTicks - particleCollectStartTick) > 100) {
            mc.options.keyUse.setDown(false);
            if (!PestTracker.hasPests()) {
                // All pests cleared
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7aPestHunter: Pest eliminated!"), true);
                }
                huntAttempts = 0;
                exitPestState(mc);
            } else {
                // Still pests — retry with new particle scan
                huntAttempts++;
                if (huntAttempts >= MAX_HUNT_ATTEMPTS) {
                    exitPestState(mc);
                    return;
                }
                resetParticleCapture();
                huntPhase = HuntPhase.EQUIP_VACUUM;
                huntTicks = 0;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Particle Packet Handler (called from MixinClientPacketListener)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called from the mixin when a particle packet is received.
     * Filters for ANGRY_VILLAGER particles and builds the directional chain.
     */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (!enabled || !collectingParticles) return;

        // Only care about ANGRY_VILLAGER particles
        if (packet.getParticle().getType() != ParticleTypes.ANGRY_VILLAGER) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 particlePos = new Vec3(packet.getX(), packet.getY(), packet.getZ());

        if (firstParticlePos == null) {
            // First particle must be near the player (within 5 blocks)
            double distToPlayer = mc.player.position().distanceTo(particlePos);
            if (distToPlayer > 5.0) return;

            firstParticlePos = particlePos;
            lastParticlePos = particlePos;
            particleLocations.add(particlePos);
        } else {
            // Subsequent particles must be within 1.75 blocks of the previous one
            double distToLast = lastParticlePos.distanceTo(particlePos);
            if (distToLast > 1.75) return;

            lastParticlePos = particlePos;
            particleLocations.add(particlePos);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Particle Chain → Pest Position Calculation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Calculate the estimated pest position from the particle chain.
     * Direction = normalize(lastParticle - firstParticle)
     * Pest position = lastParticle + direction * 10 (projected ahead)
     * Y is kept at player level (horizontal projection).
     *
     * Based on PestsDestroyer.java calculateWaypoint() (lines 1504-1510).
     */
    private static void calculatePestPosition() {
        if (firstParticlePos == null || lastParticlePos == null || particleLocations.size() < 2) {
            calculatedPestPos = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            calculatedPestPos = null;
            return;
        }

        Vec3 direction = lastParticlePos.subtract(firstParticlePos).normalize();

        // Project 10 blocks ahead from the last particle, Y stays at player level
        double projX = lastParticlePos.x + direction.x * 10.0;
        double projZ = lastParticlePos.z + direction.z * 10.0;
        double projY = mc.player.getY(); // Keep at player Y level

        calculatedPestPos = new Vec3(projX, projY, projZ);
    }

    /**
     * Reset all particle capture state for a new attempt.
     */
    private static void resetParticleCapture() {
        particleLocations.clear();
        firstParticlePos = null;
        lastParticlePos = null;
        calculatedPestPos = null;
        collectingParticles = false;
        particleCollectStartTick = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Common pest state exit
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Exit pest killing/hunting and return to farming.
     */
    private static void exitPestState(Minecraft mc) {
        pestTarget = null;
        resetParticleCapture();
        stopAllKeys(mc);

        currentState = (stateBeforePest != State.PEST_KILLING
                && stateBeforePest != State.PEST_HUNTING
                && stateBeforePest != State.NONE)
                ? stateBeforePest : State.NAVIGATING;
        stuckTicks = 0;

        // Re-select farming tool for the active crop
        if (activeCropName != null) {
            AutoTool.selectToolForCrop(mc, activeCropName);
        }

        // Restore farming camera
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        }

        // Re-enable attack for farming
        mc.options.keyAttack.setDown(true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Entity Scanning (old skull-based pest detection)
    // ══════════════════════════════════════════════════════════════════════

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

    /** Release all keys including attack, use, sprint, jump. */
    private static void stopAllKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Release only movement keys (up/down/left/right/sprint), keep attack/use untouched. */
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
