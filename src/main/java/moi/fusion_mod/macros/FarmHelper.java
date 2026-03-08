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
import java.util.HashMap;
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
 * State Machine v2:
 *   - PET_SWAPPING: Tick-based fishing rod cast for pet swap (replaces FISHING_ROD_SWAP).
 *   - PEST_HUNT_INIT/FLY/SEEK/RETURN: Multi-step pest hunting using ArmorStand skull
 *     scanning + vacuum (replaces old particle-based PEST_HUNTING).
 *   - Navigation resume: enforces camera + handles waypoint overshoot.
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
        /** Proximity-based pest kill (ArmorStand skull scanning). */
        PEST_KILLING,
        /** Pest hunt: stop movement, /sethome, equip vacuum, wait. */
        PEST_HUNT_INIT,
        /** Pest hunt: fly up to Y=79 with double-jump, hold attack (vacuum). */
        PEST_HUNT_FLY,
        /** Pest hunt: find nearest pest ArmorStand, aim + fly toward it, vacuum. */
        PEST_HUNT_SEEK,
        /** Pest hunt: /warp garden, wait, restore camera, resume farming. */
        PEST_HUNT_RETURN,
        /** Waiting for /warp garden teleport after linear farm end. */
        WARPING,
        /** Casting fishing rod for pet swap (pest cooldown ready). */
        PET_SWAPPING
    }

    private static boolean enabled = false;
    private static State currentState = State.NONE;

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

    // ── Pest killing (skull-based) ──────────────────────────────────────
    private static Entity pestTarget = null;
    private static int pestKillTicks = 0;
    private static int pestScanCounter = 0;
    private static final int PEST_SCAN_INTERVAL = 40; // every 2 seconds

    // ── Unified action tick counter (used by PET_SWAPPING, PEST_HUNT_*, WARPING) ──
    private static int actionTicks = 0;

    // ── Pet swapping (fishing rod) ──────────────────────────────────────
    /** The hotbar slot the player was using before the rod swap. */
    private static int slotBeforeRodSwap = 0;
    /** Minimum time between rod swaps (5 minutes). */
    private static long lastRodSwapTime = 0;
    private static final long ROD_SWAP_COOLDOWN_MS = 5 * 60 * 1000L;
    /**
     * Where to go after PET_SWAPPING completes.
     * NAVIGATING = resume farming (cooldown-triggered swap).
     * PEST_HUNT_INIT = enter pest hunt (pest-spawn-triggered swap).
     */
    private static State stateAfterSwap = State.NAVIGATING;

    // ── Pest hunting ────────────────────────────────────────────────────
    /** Maximum ticks for the entire hunt before giving up (15 seconds). */
    private static final int HUNT_TIMEOUT_TICKS = 300;
    /** The pest entity currently being targeted during SEEK phase. */
    private static Entity huntTarget = null;
    /** Target plot center coordinates for the FLY phase (from PLOT_COORDS). */
    private static Vec3 targetPlotCenter = null;
    /** Ticks spent in vacuum ability mode during SEEK (40-tick cooldown for particle capture). */
    private static int vacuumAbilityTicks = 0;
    /** Whether the vacuum ability has been activated this seek cycle. */
    private static boolean vacuumAbilityUsed = false;

    // ── Vacuum particle capture (reactivated for SEEK fallback) ──
    private static final List<Vec3> particleLocations = new ArrayList<>();
    private static Vec3 firstParticlePos = null;
    private static Vec3 lastParticlePos = null;
    private static Vec3 calculatedPestPos = null;
    private static boolean collectingParticles = false;
    private static int particleCollectStartTick = 0;

    // ── Hardcoded Hypixel Garden plot center coordinates ────────────────
    // Source: FarmAuto.py PLOT_COORDS (confirmed at line 119)
    private static final Map<String, Vec3> PLOT_COORDS = new HashMap<>();
    static {
        PLOT_COORDS.put("21", new Vec3(-192, 80, -192));
        PLOT_COORDS.put("13", new Vec3(-96, 80, -192));
        PLOT_COORDS.put("9",  new Vec3(0, 80, -192));
        PLOT_COORDS.put("14", new Vec3(96, 80, -192));
        PLOT_COORDS.put("22", new Vec3(192, 80, -192));
        PLOT_COORDS.put("15", new Vec3(-192, 80, -96));
        PLOT_COORDS.put("5",  new Vec3(-96, 80, -96));
        PLOT_COORDS.put("1",  new Vec3(0, 80, -96));
        PLOT_COORDS.put("6",  new Vec3(96, 80, -96));
        PLOT_COORDS.put("16", new Vec3(192, 80, -96));
        PLOT_COORDS.put("10", new Vec3(-192, 80, 0));
        PLOT_COORDS.put("2",  new Vec3(-96, 80, 0));
        PLOT_COORDS.put("Barn", new Vec3(0, 80, 0));
        PLOT_COORDS.put("The Barn", new Vec3(0, 80, 0));
        PLOT_COORDS.put("3",  new Vec3(96, 80, 0));
        PLOT_COORDS.put("11", new Vec3(192, 80, 0));
        PLOT_COORDS.put("17", new Vec3(-192, 80, 96));
        PLOT_COORDS.put("7",  new Vec3(-96, 80, 96));
        PLOT_COORDS.put("4",  new Vec3(0, 80, 96));
        PLOT_COORDS.put("8",  new Vec3(96, 80, 96));
        PLOT_COORDS.put("18", new Vec3(192, 80, 96));
        PLOT_COORDS.put("23", new Vec3(-192, 80, 192));
        PLOT_COORDS.put("19", new Vec3(-96, 80, 192));
        PLOT_COORDS.put("12", new Vec3(0, 80, 192));
        PLOT_COORDS.put("20", new Vec3(96, 80, 192));
        PLOT_COORDS.put("24", new Vec3(192, 80, 192));
    }

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
        pestScanCounter++;
        if (FusionConfig.isFarmHelperAllowPestKilling() && pestScanCounter >= PEST_SCAN_INTERVAL) {
            pestScanCounter = 0;
            if (currentState == State.NAVIGATING || currentState == State.TURN_DELAY
                    || currentState == State.DROP_DELAY) {
                // Check PestTracker first (tablist/scoreboard data)
                if (PestTracker.hasPests()) {
                    Entity pest = findNearestPest(mc);
                    if (pest != null) {
                        // Pest is visible — use direct-kill method
                        currentState = State.PEST_KILLING;
                        pestTarget = pest;
                        pestKillTicks = 0;
                        stopAllKeys(mc);
                        AutoTool.selectVacuum(mc);
                    }
                    // If no pest visible, don't auto-enter hunt — wait for forcePestHunt or end-of-farm
                } else {
                    // Fallback: scan for pest ArmorStands directly
                    Entity pest = findNearestPest(mc);
                    if (pest != null) {
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
            case PEST_HUNT_INIT:
                tickPestHuntInit(mc);
                break;
            case PEST_HUNT_FLY:
                tickPestHuntFly(mc);
                break;
            case PEST_HUNT_SEEK:
                tickPestHuntSeek(mc);
                break;
            case PEST_HUNT_RETURN:
                tickPestHuntReturn(mc);
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
            case PET_SWAPPING:
                tickPetSwapping(mc);
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
        stuckTicks = 0;
        turnDelayTicks = 0;
        dropDelayTicks = 0;
        pestTarget = null;
        pestScanCounter = 0;
        actionTicks = 0;
        huntTarget = null;
        targetPlotCenter = null;
        stateAfterSwap = State.NAVIGATING;
        vacuumAbilityTicks = 0;
        vacuumAbilityUsed = false;
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

        // Force /sethome on enable if configured
        if (FusionConfig.isFarmHelperSethomeOnStart()
                && mc.player != null && mc.player.connection != null) {
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
        actionTicks = 0;
        huntTarget = null;
        targetPlotCenter = null;
        stateAfterSwap = State.NAVIGATING;
        vacuumAbilityTicks = 0;
        vacuumAbilityUsed = false;
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
    // TASK 2: Resume Navigation Helper — Enforce camera + handle overshoot
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Safely resume NAVIGATING state from any other state.
     * Enforces camera angles, re-selects farming tool, re-enables attack,
     * and checks for waypoint overshoot (advances index if player passed the waypoint).
     */
    private static void resumeNavigating(Minecraft mc) {
        // Enforce camera
        if (mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            prevX = mc.player.getX();
            prevZ = mc.player.getZ();
        }

        // Re-select farming tool
        if (activeCropName != null) {
            AutoTool.selectToolForCrop(mc, activeCropName);
        }

        // Re-enable attack for farming
        mc.options.keyAttack.setDown(true);

        // Handle waypoint overshoot: if the player is already past the current
        // waypoint (moved during a pause/swap/hunt), advance to the next one.
        if (mc.player != null && currentWaypointIndex < waypoints.size()) {
            Vec3 target = waypoints.get(currentWaypointIndex);
            Vec3 playerPos = mc.player.position();

            // Compute the vector from player to waypoint
            double dx = target.x - playerPos.x;
            double dz = target.z - playerPos.z;

            // Project onto the forward direction vector — if negative, we passed it
            double fwdDot = dx * fx + dz * fz;

            // Also check if we're close enough that it doesn't matter
            double dist2D = Math.sqrt(dx * dx + dz * dz);

            // If the waypoint is behind us (negative forward projection) AND we're
            // reasonably close (within 5 blocks), skip to the next waypoint
            if (fwdDot < -0.5 && dist2D < 5.0) {
                currentWaypointIndex++;
                // Don't go past the end — handleEndOfFarm will deal with it
            }
        }

        stuckTicks = 0;
        currentState = State.NAVIGATING;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Waypoint Navigation — Vector Projection Movement
    // ══════════════════════════════════════════════════════════════════════

    private static void tickNavigating(Minecraft mc) {
        // ── Check pet swapping (pest cooldown ready) ────────────────────
        if (FusionConfig.isFarmHelperUseFishingRodSwap()
                && PestTracker.isPestCooldownReady()
                && System.currentTimeMillis() - lastRodSwapTime > ROD_SWAP_COOLDOWN_MS) {
            enterPetSwapping(mc);
            return;
        }

        // ── Check forcePestHunt flag from chat interception ─────────────
        if (FusionConfig.isFarmHelperAllowPestKilling() && PestTracker.forcePestHunt) {
            PestTracker.forcePestHunt = false;
            enterForcedPestHunt(mc);
            return;
        }

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
    // End-of-Farm Logic — Loop vs Linear
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called when currentWaypointIndex >= waypoints.size().
     * Determines if the farm is looping (start ~ end) or linear (start != end).
     *
     * Looping: check pests -> if pests, enter PEST_HUNT_INIT; else loop back to index 1.
     * Linear: /warp garden -> wait -> check pests -> restart from index 1.
     */
    private static void handleEndOfFarm(Minecraft mc) {
        Vec3 startPos = waypoints.get(0);
        Vec3 endPos = waypoints.get(waypoints.size() - 1);
        double startEndDist = startPos.distanceTo(endPos);

        if (startEndDist <= 2.0) {
            // ── Looping farm ───────────────────────────────────────────
            if (PestTracker.hasPests()) {
                resolveTargetPlotCenter();
                enterPestHuntInit(mc);
            } else {
                // No pests — loop back to index 1 (skip start waypoint)
                currentWaypointIndex = 1;
            }
        } else {
            // ── Linear farm ────────────────────────────────────────────
            stopAllKeys(mc);
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("warp garden");
            }
            actionTicks = 0;
            currentState = State.WARPING;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Warping State — Wait after /warp garden (linear farm end)
    // ══════════════════════════════════════════════════════════════════════

    private static void tickWarping(Minecraft mc) {
        actionTicks++;

        if (actionTicks >= 60) { // 3 seconds
            actionTicks = 0;

            // After warping, check for pests
            if (PestTracker.hasPests()) {
                resolveTargetPlotCenter();
                enterPestHuntInit(mc);
            } else {
                // Restart farming from index 1
                currentWaypointIndex = 1;
                resumeNavigating(mc);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TASK 1: Pet Swapping — Fishing Rod Cast with strict tick-based delays
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Enter pet swapping state: pause farming, cast rod, wait, return to tool.
     * Based on FarmAuto.py use_fishing_rod() logic with proper tick timing.
     *
     * @param afterSwap The state to transition to after the swap completes.
     *                  Use NAVIGATING for cooldown-triggered swaps (resume farming),
     *                  or PEST_HUNT_INIT for pest-spawn-triggered swaps (start hunting).
     */
    private static void enterPetSwapping(Minecraft mc, State afterSwap) {
        currentState = State.PET_SWAPPING;
        stateAfterSwap = afterSwap;
        actionTicks = 0;
        slotBeforeRodSwap = mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0;
        stopAllKeys(mc);

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7bFarmHelper: Casting fishing rod for pet swap..."), true);
        }
    }

    /**
     * Convenience overload: enter pet swapping with default resume to NAVIGATING.
     */
    private static void enterPetSwapping(Minecraft mc) {
        enterPetSwapping(mc, State.NAVIGATING);
    }

    /**
     * Tick the pet swapping sequence with strict tick-based actions:
     *   Tick 1:  Release W/A/S/D and Attack. Switch Inventory.selected to the Fishing Rod.
     *   Tick 5:  keyUse.setDown(true) — right-click cast.
     *   Tick 6:  Release right-click.
     *   Tick 10: Switch Inventory.selected back to the Farming tool (Hoe).
     *   Tick 15: Route to stateAfterSwap (NAVIGATING or PEST_HUNT_INIT).
     */
    private static void tickPetSwapping(Minecraft mc) {
        actionTicks++;

        if (actionTicks == 1) {
            // Release all movement + attack keys
            stopAllKeys(mc);

            // Select fishing rod
            int rodSlot = AutoTool.selectFishingRod(mc);
            if (rodSlot < 0) {
                // No rod found — skip and route to intended destination
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7eNo fishing rod found in hotbar, skipping swap."), true);
                }
                lastRodSwapTime = System.currentTimeMillis();
                actionTicks = 0;
                routeAfterSwap(mc);
                return;
            }
        } else if (actionTicks == 5) {
            // Right-click to cast (server has had 4 ticks to process slot change)
            mc.options.keyUse.setDown(true);
        } else if (actionTicks == 6) {
            // Release right-click
            mc.options.keyUse.setDown(false);
        } else if (actionTicks == 10) {
            // Switch back to farming tool
            if (activeCropName != null) {
                AutoTool.selectToolForCrop(mc, activeCropName);
            } else {
                if (mc.player != null) {
                    mc.player.getInventory().setSelectedSlot(slotBeforeRodSwap);
                }
            }
        } else if (actionTicks >= 15) {
            // Done — record swap time and route to intended state
            lastRodSwapTime = System.currentTimeMillis();
            actionTicks = 0;
            routeAfterSwap(mc);
        }
    }

    /**
     * Route to the correct state after pet swap completes.
     * If stateAfterSwap is PEST_HUNT_INIT, enter pest hunt initialization.
     * Otherwise, resume navigating (farming).
     */
    private static void routeAfterSwap(Minecraft mc) {
        State target = stateAfterSwap;
        stateAfterSwap = State.NAVIGATING; // reset for next time
        if (target == State.PEST_HUNT_INIT) {
            enterPestHuntInit(mc);
        } else {
            resumeNavigating(mc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Forced Pest Hunt — Triggered by chat "Pests have spawned"
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Enter forced pest hunt: /sethome, then pet swap (if enabled), then hunt.
     * Called when PestTracker.forcePestHunt is set by chat interception.
     */
    private static void enterForcedPestHunt(Minecraft mc) {
        stopAllKeys(mc);

        // /sethome before leaving to hunt so we can warp back
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("sethome");
        }

        // Resolve target plot center from PestTracker data
        resolveTargetPlotCenter();

        if (mc.player != null) {
            String plotInfo = targetPlotCenter != null
                    ? " (Plot " + PestTracker.getLastPestSpawnPlotId() + ")"
                    : "";
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c\u00A7lPests spawned! Starting forced hunt" + plotInfo + "..."), true);
        }

        // If fishing rod swap is enabled and cooldown allows, do pet swap first
        if (FusionConfig.isFarmHelperUseFishingRodSwap()
                && System.currentTimeMillis() - lastRodSwapTime > ROD_SWAP_COOLDOWN_MS) {
            enterPetSwapping(mc, State.PEST_HUNT_INIT);
        } else {
            enterPestHuntInit(mc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Turn Delay
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
    // Pest Killing (skull-based proximity kill)
    // ══════════════════════════════════════════════════════════════════════

    private static void tickPestKilling(Minecraft mc) {
        if (pestTarget == null || !pestTarget.isAlive() || pestTarget.isRemoved()) {
            // Pest is dead or gone, return to farming
            exitPestKillState(mc);
            return;
        }

        // Check distance
        double dist = mc.player.distanceToSqr(pestTarget);
        if (dist > 20 * 20) {
            // Too far, give up
            exitPestKillState(mc);
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
            exitPestKillState(mc);
        }
    }

    /**
     * Exit pest killing and return to farming with proper resume.
     */
    private static void exitPestKillState(Minecraft mc) {
        pestTarget = null;
        stopAllKeys(mc);
        resumeNavigating(mc);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TASK 3: Advanced Pest Hunting — INIT / FLY / SEEK / RETURN
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolve the target plot center from PestTracker data.
     * Uses: lastPestSpawnPlotId → first infested plot → null (hunt nearby).
     */
    private static void resolveTargetPlotCenter() {
        targetPlotCenter = null;

        // Try the specific plot ID from chat
        String plotId = PestTracker.getLastPestSpawnPlotId();
        if (!plotId.isEmpty()) {
            targetPlotCenter = getPlotCenter(plotId);
            if (targetPlotCenter != null) return;
        }

        // Fallback: first infested plot from tab list
        String firstInfested = PestTracker.getFirstInfestedPlot();
        if (!firstInfested.isEmpty()) {
            targetPlotCenter = getPlotCenter(firstInfested);
            if (targetPlotCenter != null) return;
        }

        // Try all infested plots
        for (String plot : PestTracker.getInfestedPlots()) {
            targetPlotCenter = getPlotCenter(plot);
            if (targetPlotCenter != null) return;
        }
        // If still null, we'll hunt wherever we are
    }

    /**
     * Get the center coordinates of a garden plot by its ID.
     * @param plotName The plot identifier (e.g. "5", "Barn", "The Barn")
     * @return The center Vec3 at Y=80, or null if the plot is not recognized.
     */
    public static Vec3 getPlotCenter(String plotName) {
        if (plotName == null || plotName.isEmpty()) return null;
        return PLOT_COORDS.get(plotName.trim());
    }

    /**
     * Enter pest hunt initialization phase.
     * Stops movement, sends /sethome, switches to vacuum, waits 1 second.
     */
    private static void enterPestHuntInit(Minecraft mc) {
        currentState = State.PEST_HUNT_INIT;
        actionTicks = 0;
        huntTarget = null;
        vacuumAbilityTicks = 0;
        vacuumAbilityUsed = false;
        resetParticleCapture();
        stopAllKeys(mc);

        // /sethome so we can warp back after hunting
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("sethome");
        }

        // Resolve target if not already set (e.g. from handleEndOfFarm)
        if (targetPlotCenter == null) {
            resolveTargetPlotCenter();
        }

        // Equip vacuum
        if (!AutoTool.selectVacuum(mc)) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7cPestHunter: No vacuum found in hotbar!"), true);
            }
            // Can't hunt without vacuum — just resume farming
            resumeNavigating(mc);
            return;
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7ePestHunter: Pests detected! Preparing to hunt..."), true);
        }
    }

    /**
     * PEST_HUNT_INIT: Wait 20 ticks (1 second) after /sethome + vacuum equip,
     * then double-jump to enable flight and transition to FLY.
     */
    private static void tickPestHuntInit(Minecraft mc) {
        actionTicks++;

        if (actionTicks >= 20) {
            // Transition to FLY phase
            actionTicks = 0;
            currentState = State.PEST_HUNT_FLY;
        }
    }

    /**
     * PEST_HUNT_FLY: Double-tap jump to engage flight, then fly toward
     * targetPlotCenter if set, or straight up if no target.
     *
     * Phases:
     *   Ticks 1-2:  Jump (first press)
     *   Tick 3:     Release jump
     *   Tick 5:     Jump again (double-tap to enable flight)
     *   Tick 7:     Release jump
     *   Tick 8+:    Hold jump to ascend + navigate toward target plot
     *
     * Transition to SEEK when:
     *   - Within 10 blocks horizontally of targetPlotCenter, OR
     *   - Y >= 79 (if no target), OR
     *   - Timeout (60 ticks = 3 seconds)
     */
    private static void tickPestHuntFly(Minecraft mc) {
        if (mc.player == null) {
            actionTicks = 0;
            currentState = State.PEST_HUNT_RETURN;
            return;
        }

        actionTicks++;

        // Hold attack (vacuum left-click) for passive pest suction
        mc.options.keyAttack.setDown(true);

        double playerY = mc.player.getY();

        // ── Double-tap jump to engage flight ──────────────────────────
        if (actionTicks <= 2) {
            mc.options.keyJump.setDown(true);
        } else if (actionTicks == 3) {
            mc.options.keyJump.setDown(false);
        } else if (actionTicks == 5) {
            mc.options.keyJump.setDown(true);
        } else if (actionTicks == 7) {
            mc.options.keyJump.setDown(false);
        } else {
            // Hold jump to ascend
            mc.options.keyJump.setDown(true);

            // ── Navigate toward target plot if we have one ────────────
            if (targetPlotCenter != null) {
                double dx = targetPlotCenter.x - mc.player.getX();
                double dz = targetPlotCenter.z - mc.player.getZ();
                double horizDist = Math.sqrt(dx * dx + dz * dz);

                // Aim toward the target
                float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setYRot(Mth.rotLerp(0.3f, mc.player.getYRot(), targetYaw));
                mc.player.setXRot(-10f); // Slightly upward while flying

                // Press W to fly forward
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(horizDist > 20);

                // Check if we're close enough to the target
                if (horizDist <= 10.0 && playerY >= 75.0) {
                    mc.options.keyJump.setDown(false);
                    mc.options.keySprint.setDown(false);
                    stopMovementKeys(mc);
                    actionTicks = 0;
                    currentState = State.PEST_HUNT_SEEK;

                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.literal("\u00A7ePestHunter: Arrived at plot, seeking pests..."), true);
                    }
                    return;
                }
            } else {
                // No target — just fly up and look down
                mc.player.setXRot(90f); // Look straight down for pest visibility

                // Check altitude
                if (playerY >= 79.0) {
                    mc.options.keyJump.setDown(false);
                    actionTicks = 0;
                    currentState = State.PEST_HUNT_SEEK;

                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.literal("\u00A7ePestHunter: Altitude reached, seeking pests..."), true);
                    }
                    return;
                }
            }
        }

        // Timeout — proceed to SEEK regardless
        if (actionTicks > 60) {
            mc.options.keyJump.setDown(false);
            stopMovementKeys(mc);
            actionTicks = 0;
            currentState = State.PEST_HUNT_SEEK;
        }
    }

    /**
     * PEST_HUNT_SEEK: Find nearest pest ArmorStand with skull texture.
     *
     * Strategy:
     *   1. Scan for pest ArmorStands every tick.
     *   2. If found: aim at it, fly toward it holding right-click (vacuum).
     *   3. If NOT found but pests > 0:
     *      a. Equip vacuum, left-click (vacuum ability) once.
     *      b. Wait 40 ticks (2s cooldown) while collecting ANGRY_VILLAGER particles.
     *      c. If particle chain collected: calculate pest position from extrapolation.
     *      d. Fly toward the calculated position.
     *   4. If all pests dead or timeout: transition to RETURN.
     */
    private static void tickPestHuntSeek(Minecraft mc) {
        if (mc.player == null) {
            actionTicks = 0;
            currentState = State.PEST_HUNT_RETURN;
            return;
        }

        actionTicks++;

        // Overall seek timeout (15 seconds = 300 ticks)
        if (actionTicks > HUNT_TIMEOUT_TICKS) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7cPestHunter: Hunt timeout, returning to farm."), true);
            }
            stopAllKeys(mc);
            actionTicks = 0;
            currentState = State.PEST_HUNT_RETURN;
            return;
        }

        // Check if all pests are gone (refresh every second)
        if (actionTicks % 20 == 0) {
            PestTracker.tick();
        }
        if (!PestTracker.hasPests()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7aPestHunter: All pests cleared!"), true);
            }
            stopAllKeys(mc);
            actionTicks = 0;
            currentState = State.PEST_HUNT_RETURN;
            return;
        }

        // ── Try to find a pest entity directly ────────────────────────
        Entity pest = findNearestPest(mc);

        if (pest != null) {
            huntTarget = pest;
        }

        if (huntTarget != null && (!huntTarget.isAlive() || huntTarget.isRemoved())) {
            huntTarget = null; // Reset dead/removed target
        }

        if (huntTarget != null) {
            // ── Found a pest — aim and fly toward it ──────────────────
            Vec3 pestPos = new Vec3(huntTarget.getX(),
                    huntTarget.getY() + huntTarget.getEyeHeight(),
                    huntTarget.getZ());
            Vec2 angle = AutoMiner.getYawPitch(mc.player.getEyePosition(), pestPos);

            float currentYaw = mc.player.getYRot();
            float currentPitch = mc.player.getXRot();
            double angleDist = AutoMiner.getAngleDistance(currentYaw, currentPitch, angle.x, angle.y);

            if (angleDist >= 1.0) {
                float newYaw = Mth.rotLerp(0.4f, currentYaw, angle.x);
                float newPitch = Mth.rotLerp(0.4f, currentPitch, angle.y);
                mc.player.setYRot(newYaw);
                mc.player.setXRot(newPitch);
            } else {
                mc.player.setYRot(angle.x);
                mc.player.setXRot(angle.y);
            }

            // Move toward the pest using vector projection on current camera
            double dx = huntTarget.getX() - mc.player.getX();
            double dz = huntTarget.getZ() - mc.player.getZ();
            double dist2D = Math.sqrt(dx * dx + dz * dz);

            double moveRad = Math.toRadians(mc.player.getYRot());
            double moveFx = -Math.sin(moveRad);
            double moveFz = Math.cos(moveRad);
            double moveRx = -Math.sin(moveRad + Math.PI / 2);
            double moveRz = Math.cos(moveRad + Math.PI / 2);

            double fwdDot = dx * moveFx + dz * moveFz;
            double rightDot = dx * moveRx + dz * moveRz;

            if (dist2D > 4.0) {
                mc.options.keyUp.setDown(fwdDot > 0.3);
                mc.options.keyDown.setDown(fwdDot < -0.3);
                mc.options.keyRight.setDown(rightDot > 0.3);
                mc.options.keyLeft.setDown(rightDot < -0.3);
                mc.options.keySprint.setDown(dist2D > 10);
            } else {
                stopMovementKeys(mc);
            }

            // Adjust altitude to match pest
            double dy = huntTarget.getY() - mc.player.getY();
            mc.options.keyJump.setDown(dy > 1.5);
            mc.options.keyShift.setDown(dy < -2.0);

            // Hold right-click (vacuum suction)
            mc.options.keyUse.setDown(true);

            // Stop collecting particles — we found the pest directly
            collectingParticles = false;

        } else if (calculatedPestPos != null) {
            // ── Particle-calculated pest position — fly toward it ─────
            Vec3 target = calculatedPestPos;
            double dx = target.x - mc.player.getX();
            double dz = target.z - mc.player.getZ();
            double dist2D = Math.sqrt(dx * dx + dz * dz);

            // Aim toward calculated position
            float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setYRot(Mth.rotLerp(0.3f, mc.player.getYRot(), targetYaw));
            mc.player.setXRot(30f); // Look slightly down

            if (dist2D > 3.0) {
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(dist2D > 15);
            } else {
                stopMovementKeys(mc);
                // We arrived at the calculated position — clear it and
                // let the next cycle try to find the pest entity or re-use vacuum
                calculatedPestPos = null;
                vacuumAbilityUsed = false;
            }

            mc.options.keyUse.setDown(true);

        } else {
            // ── No pest found and no calculated position ──────────────
            // Try vacuum ability for particle capture

            if (!vacuumAbilityUsed) {
                // Activate vacuum ability: ensure vacuum is equipped, then left-click
                AutoTool.selectVacuum(mc);
                mc.options.keyAttack.setDown(true);
                vacuumAbilityUsed = true;
                vacuumAbilityTicks = 0;
                collectingParticles = true;
                resetParticleCapture();
                collectingParticles = true; // re-enable after reset
            }

            vacuumAbilityTicks++;

            if (vacuumAbilityTicks <= 5) {
                // Hold left-click for a few ticks to trigger the ability
                mc.options.keyAttack.setDown(true);
            } else {
                mc.options.keyAttack.setDown(false);
            }

            // Wait 40 ticks (2 seconds) for particles to appear
            if (vacuumAbilityTicks >= 40) {
                collectingParticles = false;

                // Analyze particle chain
                if (particleLocations.size() >= 3 && firstParticlePos != null && lastParticlePos != null) {
                    // Extrapolate from particle chain direction
                    Vec3 direction = lastParticlePos.subtract(firstParticlePos).normalize();
                    double chainLength = firstParticlePos.distanceTo(lastParticlePos);
                    // Extend the chain by 2x its length to estimate pest position
                    double extendDist = Math.max(chainLength * 2.0, 10.0);
                    calculatedPestPos = lastParticlePos.add(
                            direction.x * extendDist,
                            direction.y * extendDist,
                            direction.z * extendDist
                    );

                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.literal("\u00A7ePestHunter: Particle trail detected! Flying to estimated position..."), true);
                    }
                } else {
                    // No useful particles — reset and try again after a brief scan
                    vacuumAbilityUsed = false; // Allow retrying
                    vacuumAbilityTicks = 0;

                    // Slowly spin to scan surroundings
                    float scanYaw = mc.player.getYRot() + 3.0f;
                    mc.player.setYRot(scanYaw);
                    mc.player.setXRot(15f);
                }
            }

            // Hold right-click vacuum while waiting
            mc.options.keyUse.setDown(true);
            stopMovementKeys(mc);
        }
    }

    /**
     * PEST_HUNT_RETURN: /warp garden, wait 3 seconds (60 ticks),
     * reset yaw/pitch, resume NAVIGATING.
     */
    private static void tickPestHuntReturn(Minecraft mc) {
        actionTicks++;

        if (actionTicks == 1) {
            stopAllKeys(mc);
            // Warp back to garden
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("warp garden");
            }
        }

        if (actionTicks >= 60) { // 3 seconds
            actionTicks = 0;
            huntTarget = null;
            targetPlotCenter = null;
            vacuumAbilityTicks = 0;
            vacuumAbilityUsed = false;
            resetParticleCapture();

            // Restart farming from index 1 (we warped to garden home)
            currentWaypointIndex = 1;
            resumeNavigating(mc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Particle Packet Handler (called from MixinClientPacketListener)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called from the mixin when a particle packet is received.
     * Filters for ANGRY_VILLAGER particles and builds the directional chain.
     * (Kept for compatibility — the new hunt system uses direct entity scanning
     * but this data can still be useful for future enhancements.)
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
    // Entity Scanning (skull-based pest detection)
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

    /** Release all keys including attack, use, sprint, jump, sneak. */
    private static void stopAllKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
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
