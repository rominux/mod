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

    // ── Pest hunting ────────────────────────────────────────────────────
    /** Maximum ticks for the entire hunt before giving up (15 seconds). */
    private static final int HUNT_TIMEOUT_TICKS = 300;
    /** The pest entity currently being targeted during SEEK phase. */
    private static Entity huntTarget = null;

    // ── Vacuum particle capture (kept for onParticlePacket compatibility) ──
    private static final List<Vec3> particleLocations = new ArrayList<>();
    private static Vec3 firstParticlePos = null;
    private static Vec3 lastParticlePos = null;
    private static Vec3 calculatedPestPos = null;
    private static boolean collectingParticles = false;
    private static int particleCollectStartTick = 0;

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
     */
    private static void enterPetSwapping(Minecraft mc) {
        currentState = State.PET_SWAPPING;
        actionTicks = 0;
        slotBeforeRodSwap = mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0;
        stopAllKeys(mc);

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7bFarmHelper: Casting fishing rod for pet swap..."), true);
        }
    }

    /**
     * Tick the pet swapping sequence with strict tick-based actions:
     *   Tick 1:  Release W/A/S/D and Attack. Switch Inventory.selected to the Fishing Rod.
     *   Tick 5:  keyUse.setDown(true) — right-click cast.
     *   Tick 6:  Release right-click.
     *   Tick 10: Switch Inventory.selected back to the Farming tool (Hoe).
     *   Tick 15: Set currentState = NAVIGATING, reset actionTicks.
     */
    private static void tickPetSwapping(Minecraft mc) {
        actionTicks++;

        if (actionTicks == 1) {
            // Release all movement + attack keys
            stopAllKeys(mc);

            // Select fishing rod
            int rodSlot = AutoTool.selectFishingRod(mc);
            if (rodSlot < 0) {
                // No rod found — skip and resume
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7eNo fishing rod found in hotbar, skipping swap."), true);
                }
                lastRodSwapTime = System.currentTimeMillis();
                actionTicks = 0;
                resumeNavigating(mc);
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
            // Done — record swap time and resume navigating
            lastRodSwapTime = System.currentTimeMillis();
            actionTicks = 0;
            resumeNavigating(mc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Forced Pest Hunt — Triggered by chat "Pests have spawned"
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Enter forced pest hunt: /sethome, then transition to pest hunting.
     * Called when PestTracker.forcePestHunt is set by chat interception.
     */
    private static void enterForcedPestHunt(Minecraft mc) {
        stopAllKeys(mc);

        // /sethome before leaving to hunt so we can warp back
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("sethome");
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c\u00A7lPests spawned! Starting forced hunt..."), true);
        }

        enterPestHuntInit(mc);
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
     * Enter pest hunt initialization phase.
     * Stops movement, sends /sethome, switches to vacuum, waits 1 second.
     */
    private static void enterPestHuntInit(Minecraft mc) {
        currentState = State.PEST_HUNT_INIT;
        actionTicks = 0;
        huntTarget = null;
        stopAllKeys(mc);

        // /sethome so we can warp back after hunting
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("sethome");
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
     * PEST_HUNT_INIT: Wait 20 ticks (1 second) after /sethome + vacuum equip, then fly.
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
     * PEST_HUNT_FLY: Force camera straight down (pitch=90), hold jump + attack (vacuum).
     * Double-tap jump to engage flight, then hold jump to ascend.
     * When Y >= 79, release jump and transition to SEEK.
     */
    private static void tickPestHuntFly(Minecraft mc) {
        if (mc.player == null) {
            actionTicks = 0;
            currentState = State.PEST_HUNT_RETURN;
            return;
        }

        actionTicks++;

        // Force camera straight down for pest visibility
        mc.player.setXRot(90f);

        // Hold attack (vacuum left-click)
        mc.options.keyAttack.setDown(true);

        double playerY = mc.player.getY();

        // Check if high enough or timeout (3 seconds = 60 ticks)
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

        if (actionTicks > 60) {
            // Timeout — proceed anyway
            mc.options.keyJump.setDown(false);
            actionTicks = 0;
            currentState = State.PEST_HUNT_SEEK;
            return;
        }

        // Double-tap jump to start flying (SkyBlock garden flight)
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
        }
    }

    /**
     * PEST_HUNT_SEEK: Find nearest pest ArmorStand with skull texture.
     * Aim at its position using smooth look. Project W/A/S/D to fly toward it.
     * Hold keyUse (right-click vacuum suck).
     *
     * If no pest found for 15 seconds or all pests dead, transition to RETURN.
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

        // Find nearest pest entity
        Entity pest = findNearestPest(mc);

        if (pest != null) {
            huntTarget = pest;
        }

        if (huntTarget != null && (!huntTarget.isAlive() || huntTarget.isRemoved())) {
            huntTarget = null; // Reset dead/removed target
        }

        if (huntTarget != null) {
            // ── Aim at the pest ────────────────────────────────────────
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
                // Snap to exact angle when close enough
                mc.player.setYRot(angle.x);
                mc.player.setXRot(angle.y);
            }

            // ── Move toward the pest using vector projection ───────────
            double dx = huntTarget.getX() - mc.player.getX();
            double dz = huntTarget.getZ() - mc.player.getZ();
            double dist2D = Math.sqrt(dx * dx + dz * dz);

            // Compute forward/right relative to current camera yaw for movement
            double moveRad = Math.toRadians(mc.player.getYRot());
            double moveFx = -Math.sin(moveRad);
            double moveFz = Math.cos(moveRad);
            double moveRx = -Math.sin(moveRad + Math.PI / 2);
            double moveRz = Math.cos(moveRad + Math.PI / 2);

            double fwdDot = dx * moveFx + dz * moveFz;
            double rightDot = dx * moveRx + dz * moveRz;

            if (dist2D > 4.0) {
                // Walk toward pest
                mc.options.keyUp.setDown(fwdDot > 0.3);
                mc.options.keyDown.setDown(fwdDot < -0.3);
                mc.options.keyRight.setDown(rightDot > 0.3);
                mc.options.keyLeft.setDown(rightDot < -0.3);
                mc.options.keySprint.setDown(dist2D > 10);
            } else {
                // Close enough — stop movement, just vacuum
                stopMovementKeys(mc);
            }

            // Hold right-click (vacuum suction)
            mc.options.keyUse.setDown(true);

        } else {
            // No pest found — slowly spin to scan surroundings
            float scanYaw = mc.player.getYRot() + 3.0f; // 3 degrees per tick
            mc.player.setYRot(scanYaw);
            mc.player.setXRot(15f); // Slightly downward to see ground pests

            // Keep vacuum clicking
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
