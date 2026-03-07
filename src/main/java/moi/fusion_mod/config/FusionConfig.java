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
import java.util.Arrays;
import java.util.List;

/**
 * Central configuration for Fusion Mod.
 * Config is saved to config/fusion_mod.json using GSON.
 *
 * The Zone Info HUD uses configurable String Lists with placeholders like
 * {location}, {mithril_powder}, {commissions}, etc. Each zone type has its
 * own default layout that the user can customize.
 */
public class FusionConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FusionConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("fusion_mod.json");

    private static ConfigData data = new ConfigData();

    /**
     * Holds all config fields. GSON-serialized to/from JSON.
     */
    public static class ConfigData {
        // ══════════════════════════════════════════════════════════════════
        // General Settings
        // ══════════════════════════════════════════════════════════════════
        public boolean zoneInfoHudEnabled = true;
        public boolean drillFuelBarEnabled = true;
        public boolean itemPickupLogEnabled = true;
        public boolean itemTooltipsEnabled = true;
        public boolean chatFilterEnabled = true;
        public boolean partyCommandsEnabled = true;
        public boolean experimentSolverEnabled = true;

        // ══════════════════════════════════════════════════════════════════
        // Dwarven Mines / Mining
        // ══════════════════════════════════════════════════════════════════
        public boolean commissionWaypointsEnabled = true;
        public boolean waypointsEnabled = true;
        public boolean pickobulusPreviewEnabled = true;  // 3D box in world

        /** HUD layout for Dwarven Mines / Glacite Tunnels / Crystal Hollows */
        public List<String> miningHudLayout = Arrays.asList(
                "{location}",
                "Mithril: {mithril_powder}",
                "Gemstone: {gemstone_powder}",
                "Glacite: {glacite_powder}",
                "Pickobulus: {pickobulus}",
                "Sky Mall: {skymall}",
                "",
                "Commissions:",
                "{commissions}"
        );

        // ══════════════════════════════════════════════════════════════════
        // Crystal Hollows
        // ══════════════════════════════════════════════════════════════════
        public boolean chestEspEnabled = true;
        public boolean crystalMapEnabled = true;
        public float crystalMapScale = 1.0f;
        public int crystalMapLocationSize = 8;

        // ══════════════════════════════════════════════════════════════════
        // Garden
        // ══════════════════════════════════════════════════════════════════
        public boolean gardenTrackerEnabled = true;
        public boolean gardenShowVisitors = true;
        public boolean gardenShowPests = true;
        public boolean gardenShowSpray = true;
        public boolean gardenShowGreenhouse = true;
        public boolean gardenShowJacobContest = true;

        /** HUD layout for Garden zone */
        public List<String> gardenHudLayout = Arrays.asList(
                "{location}",
                "Pests: {pests_alive}",
                "{pests_plots}",
                "Spray: {spray}",
                "Visitors: {visitors}",
                "Jacob: {jacob_timer}",
                "Greenhouse: {greenhouse_timer}"
        );

        // ══════════════════════════════════════════════════════════════════
        // Hub
        // ══════════════════════════════════════════════════════════════════
        public boolean hubShowMayor = true;
        public boolean hubShowBank = true;
        public boolean hubShowSlayers = true;
        public boolean hubShowCookie = true;
        public boolean hubShowGodPot = true;

        /** HUD layout for Hub zone */
        public List<String> hubHudLayout = Arrays.asList(
                "{location}",
                "Mayor: {mayor}",
                "Minister: {minister}",
                "Bank: {bank}",
                "Interest: {interest}",
                "{slayer_quest}",
                "",
                "Cookie Buff: {cookie_buff}",
                "God Potion: {god_potion}"
        );

        // ══════════════════════════════════════════════════════════════════
        // Default / Fallback (any other zone)
        // ══════════════════════════════════════════════════════════════════
        public List<String> defaultHudLayout = Arrays.asList(
                "{location}"
        );

        // Legacy fields kept for backward compatibility with old configs
        public boolean commissionsHudEnabled = true;
        public boolean hotmOverlayEnabled = false;

        // ══════════════════════════════════════════════════════════════════
        // Macros
        // ══════════════════════════════════════════════════════════════════
        public boolean autoMinerEnabled = false;
        public List<String> autoMinerBlocks = Arrays.asList(
                "minecraft:mithril_ore", "minecraft:titanium_ore"
        );
        public boolean autoMinerPrecision = true;

        public boolean farmHelperEnabled = false;
        public int farmHelperMacroType = 0;  // 0 = S-Shape Vertical Crop
        public float farmHelperCustomYaw = 0f;
        public float farmHelperCustomPitch = 3f;
    }

    // ── Initialization ──────────────────────────────────────────────────────

    public static void init() {
        load();
    }

    // ── Screen factory ──────────────────────────────────────────────────────

    public static Screen createDisplayScreen(Screen parent) {
        return new ConfigScreen(parent);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════════════════

    // General
    public static boolean isZoneInfoHudEnabled() { return data.zoneInfoHudEnabled; }
    public static boolean isDrillFuelBarEnabled() { return data.drillFuelBarEnabled; }
    public static boolean isItemPickupLogEnabled() { return data.itemPickupLogEnabled; }
    public static boolean isItemTooltipsEnabled() { return data.itemTooltipsEnabled; }
    public static boolean isChatFilterEnabled() { return data.chatFilterEnabled; }
    public static boolean isPartyCommandsEnabled() { return data.partyCommandsEnabled; }
    public static boolean isExperimentSolverEnabled() { return data.experimentSolverEnabled; }

    // Dwarven Mines
    public static boolean isCommissionWaypointsEnabled() { return data.commissionWaypointsEnabled; }
    public static boolean isWaypointsEnabled() { return data.waypointsEnabled; }
    public static boolean isPickobulusPreviewEnabled() { return data.pickobulusPreviewEnabled; }

    // Crystal Hollows
    public static boolean isChestEspEnabled() { return data.chestEspEnabled; }
    public static boolean isCrystalMapEnabled() { return data.crystalMapEnabled; }
    public static float getCrystalMapScale() { return data.crystalMapScale; }
    public static int getCrystalMapLocationSize() { return data.crystalMapLocationSize; }

    // Garden
    public static boolean isGardenTrackerEnabled() { return data.gardenTrackerEnabled; }
    public static boolean isGardenShowVisitors() { return data.gardenShowVisitors; }
    public static boolean isGardenShowPests() { return data.gardenShowPests; }
    public static boolean isGardenShowSpray() { return data.gardenShowSpray; }
    public static boolean isGardenShowGreenhouse() { return data.gardenShowGreenhouse; }
    public static boolean isGardenShowJacobContest() { return data.gardenShowJacobContest; }

    // Hub
    public static boolean isHubShowMayor() { return data.hubShowMayor; }
    public static boolean isHubShowBank() { return data.hubShowBank; }
    public static boolean isHubShowSlayers() { return data.hubShowSlayers; }
    public static boolean isHubShowCookie() { return data.hubShowCookie; }
    public static boolean isHubShowGodPot() { return data.hubShowGodPot; }

    // Macros
    public static boolean isAutoMinerEnabled() { return data.autoMinerEnabled; }
    public static List<String> getAutoMinerBlocks() { return data.autoMinerBlocks; }
    public static boolean isAutoMinerPrecision() { return data.autoMinerPrecision; }
    public static boolean isFarmHelperEnabled() { return data.farmHelperEnabled; }
    public static int getFarmHelperMacroType() { return data.farmHelperMacroType; }
    public static float getFarmHelperCustomYaw() { return data.farmHelperCustomYaw; }
    public static float getFarmHelperCustomPitch() { return data.farmHelperCustomPitch; }

    // HUD Layouts
    public static List<String> getMiningHudLayout() { return data.miningHudLayout; }
    public static List<String> getGardenHudLayout() { return data.gardenHudLayout; }
    public static List<String> getHubHudLayout() { return data.hubHudLayout; }
    public static List<String> getDefaultHudLayout() { return data.defaultHudLayout; }

    // Legacy compat
    public static boolean isCommissionsEnabled() { return data.zoneInfoHudEnabled; }
    public static boolean isPickobulusTimerEnabled() { return data.pickobulusPreviewEnabled; }
    public static boolean isHotmOverlayEnabled() { return data.hotmOverlayEnabled; }

    // ══════════════════════════════════════════════════════════════════════
    // Setters (auto-save)
    // ══════════════════════════════════════════════════════════════════════

    // General
    public static void setZoneInfoHudEnabled(boolean v) { data.zoneInfoHudEnabled = v; save(); }
    public static void setDrillFuelBarEnabled(boolean v) { data.drillFuelBarEnabled = v; save(); }
    public static void setItemPickupLogEnabled(boolean v) { data.itemPickupLogEnabled = v; save(); }
    public static void setItemTooltipsEnabled(boolean v) { data.itemTooltipsEnabled = v; save(); }
    public static void setChatFilterEnabled(boolean v) { data.chatFilterEnabled = v; save(); }
    public static void setPartyCommandsEnabled(boolean v) { data.partyCommandsEnabled = v; save(); }
    public static void setExperimentSolverEnabled(boolean v) { data.experimentSolverEnabled = v; save(); }

    // Dwarven Mines
    public static void setCommissionWaypointsEnabled(boolean v) { data.commissionWaypointsEnabled = v; save(); }
    public static void setWaypointsEnabled(boolean v) { data.waypointsEnabled = v; save(); }
    public static void setPickobulusPreviewEnabled(boolean v) { data.pickobulusPreviewEnabled = v; save(); }

    // Crystal Hollows
    public static void setChestEspEnabled(boolean v) { data.chestEspEnabled = v; save(); }
    public static void setCrystalMapEnabled(boolean v) { data.crystalMapEnabled = v; save(); }
    public static void setCrystalMapScale(float v) { data.crystalMapScale = v; save(); }
    public static void setCrystalMapLocationSize(int v) { data.crystalMapLocationSize = v; save(); }

    // Garden
    public static void setGardenTrackerEnabled(boolean v) { data.gardenTrackerEnabled = v; save(); }
    public static void setGardenShowVisitors(boolean v) { data.gardenShowVisitors = v; save(); }
    public static void setGardenShowPests(boolean v) { data.gardenShowPests = v; save(); }
    public static void setGardenShowSpray(boolean v) { data.gardenShowSpray = v; save(); }
    public static void setGardenShowGreenhouse(boolean v) { data.gardenShowGreenhouse = v; save(); }
    public static void setGardenShowJacobContest(boolean v) { data.gardenShowJacobContest = v; save(); }

    // Hub
    public static void setHubShowMayor(boolean v) { data.hubShowMayor = v; save(); }
    public static void setHubShowBank(boolean v) { data.hubShowBank = v; save(); }
    public static void setHubShowSlayers(boolean v) { data.hubShowSlayers = v; save(); }
    public static void setHubShowCookie(boolean v) { data.hubShowCookie = v; save(); }
    public static void setHubShowGodPot(boolean v) { data.hubShowGodPot = v; save(); }

    // Macros
    public static void setAutoMinerEnabled(boolean v) { data.autoMinerEnabled = v; save(); }
    public static void setAutoMinerBlocks(List<String> v) { data.autoMinerBlocks = v; save(); }
    public static void setAutoMinerPrecision(boolean v) { data.autoMinerPrecision = v; save(); }
    public static void setFarmHelperEnabled(boolean v) { data.farmHelperEnabled = v; save(); }
    public static void setFarmHelperMacroType(int v) { data.farmHelperMacroType = v; save(); }
    public static void setFarmHelperCustomYaw(float v) { data.farmHelperCustomYaw = v; save(); }
    public static void setFarmHelperCustomPitch(float v) { data.farmHelperCustomPitch = v; save(); }

    // Legacy compat
    public static void setCommissionsHudEnabled(boolean v) { data.zoneInfoHudEnabled = v; save(); }
    public static void setHotmOverlayEnabled(boolean v) { data.hotmOverlayEnabled = v; save(); }

    // ── Direct data access ──────────────────────────────────────────────────

    public static ConfigData getData() { return data; }

    // ── Persistence ─────────────────────────────────────────────────────────

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
                    // Ensure lists are never null (GSON may leave them null if missing from JSON)
                    if (data.miningHudLayout == null) data.miningHudLayout = new ConfigData().miningHudLayout;
                    if (data.gardenHudLayout == null) data.gardenHudLayout = new ConfigData().gardenHudLayout;
                    if (data.hubHudLayout == null) data.hubHudLayout = new ConfigData().hubHudLayout;
                    if (data.defaultHudLayout == null) data.defaultHudLayout = new ConfigData().defaultHudLayout;
                    if (data.autoMinerBlocks == null) data.autoMinerBlocks = new ConfigData().autoMinerBlocks;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[FusionMod] Failed to load config, using defaults", e);
            data = new ConfigData();
        }
    }
}
