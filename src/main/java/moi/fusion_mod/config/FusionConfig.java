package moi.fusion_mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central configuration for Fusion Mod.
 *
 * Persistence approach adapted from:
 *   - doc/Skyblocker/.../config/SkyblockerConfigManager.java (GSON JSON file)
 *   - doc/SkyHanni/.../config/ConfigManager.kt (GSON auto-save)
 *
 * All feature toggles are consolidated here so ConfigScreen can bind to them.
 * Config is saved to {@code config/fusion_mod.json} using GSON.
 */
public class FusionConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FusionConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("fusion_mod.json");

    // ── Serializable config data ────────────────────────────────────────────
    private static ConfigData data = new ConfigData();

    /**
     * Holds all config fields. GSON-serialized to/from JSON.
     */
    public static class ConfigData {
        // ── Crystal Hollows ──
        public boolean chestEspEnabled = true;
        public boolean crystalMapEnabled = true;
        public float crystalMapScale = 1.0f;
        public int crystalMapLocationSize = 8;

        // ── 3D Waypoints ──
        public boolean waypointsEnabled = true;
        public boolean commissionWaypointsEnabled = true;

        // ── HUD Widgets ──
        public boolean commissionsHudEnabled = true;
        public boolean drillFuelBarEnabled = true;
        public boolean pickobulusTimerEnabled = true;
        public boolean itemPickupLogEnabled = true;
        public boolean hotmOverlayEnabled = true;

        // ── Garden ──
        public boolean gardenTrackerEnabled = true;

        // ── Social ──
        public boolean chatFilterEnabled = true;
        public boolean partyCommandsEnabled = true;

        // ── Economy ──
        public boolean itemTooltipsEnabled = true;

        // ── Progression ──
        public boolean experimentSolverEnabled = true;
    }

    // ── Initialization ──────────────────────────────────────────────────────

    public static void init() {
        load();
    }

    // ── Screen factory ──────────────────────────────────────────────────────

    public static Screen createDisplayScreen(Screen parent) {
        return new ConfigScreen(parent);
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public static boolean isChestEspEnabled() { return data.chestEspEnabled; }
    public static boolean isCrystalMapEnabled() { return data.crystalMapEnabled; }
    public static float getCrystalMapScale() { return data.crystalMapScale; }
    public static int getCrystalMapLocationSize() { return data.crystalMapLocationSize; }
    public static boolean isWaypointsEnabled() { return data.waypointsEnabled; }
    public static boolean isCommissionWaypointsEnabled() { return data.commissionWaypointsEnabled; }
    public static boolean isCommissionsEnabled() { return data.commissionsHudEnabled; }
    public static boolean isDrillFuelBarEnabled() { return data.drillFuelBarEnabled; }
    public static boolean isPickobulusTimerEnabled() { return data.pickobulusTimerEnabled; }
    public static boolean isItemPickupLogEnabled() { return data.itemPickupLogEnabled; }
    public static boolean isHotmOverlayEnabled() { return data.hotmOverlayEnabled; }
    public static boolean isGardenTrackerEnabled() { return data.gardenTrackerEnabled; }
    public static boolean isChatFilterEnabled() { return data.chatFilterEnabled; }
    public static boolean isPartyCommandsEnabled() { return data.partyCommandsEnabled; }
    public static boolean isItemTooltipsEnabled() { return data.itemTooltipsEnabled; }
    public static boolean isExperimentSolverEnabled() { return data.experimentSolverEnabled; }

    // ── Setters (auto-save) ─────────────────────────────────────────────────

    public static void setChestEspEnabled(boolean v) { data.chestEspEnabled = v; save(); }
    public static void setCrystalMapEnabled(boolean v) { data.crystalMapEnabled = v; save(); }
    public static void setCrystalMapScale(float v) { data.crystalMapScale = v; save(); }
    public static void setCrystalMapLocationSize(int v) { data.crystalMapLocationSize = v; save(); }
    public static void setWaypointsEnabled(boolean v) { data.waypointsEnabled = v; save(); }
    public static void setCommissionWaypointsEnabled(boolean v) { data.commissionWaypointsEnabled = v; save(); }
    public static void setCommissionsHudEnabled(boolean v) { data.commissionsHudEnabled = v; save(); }
    public static void setDrillFuelBarEnabled(boolean v) { data.drillFuelBarEnabled = v; save(); }
    public static void setPickobulusTimerEnabled(boolean v) { data.pickobulusTimerEnabled = v; save(); }
    public static void setItemPickupLogEnabled(boolean v) { data.itemPickupLogEnabled = v; save(); }
    public static void setHotmOverlayEnabled(boolean v) { data.hotmOverlayEnabled = v; save(); }
    public static void setGardenTrackerEnabled(boolean v) { data.gardenTrackerEnabled = v; save(); }
    public static void setChatFilterEnabled(boolean v) { data.chatFilterEnabled = v; save(); }
    public static void setPartyCommandsEnabled(boolean v) { data.partyCommandsEnabled = v; save(); }
    public static void setItemTooltipsEnabled(boolean v) { data.itemTooltipsEnabled = v; save(); }
    public static void setExperimentSolverEnabled(boolean v) { data.experimentSolverEnabled = v; save(); }

    // ── Direct data access (for ConfigScreen binding) ───────────────────────

    public static ConfigData getData() { return data; }

    // ── Persistence ─────────────────────────────────────────────────────────
    // Adapted from Skyblocker's SkyblockerConfigManager and SkyHanni's ConfigManager

    public static void save() {
        try {
            String json = GSON.toJson(data);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            LOGGER.error("[FusionMod] Failed to save config", e);
        }
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                ConfigData loaded = GSON.fromJson(json, ConfigData.class);
                if (loaded != null) {
                    data = loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[FusionMod] Failed to load config, using defaults", e);
            data = new ConfigData();
        }
    }
}
