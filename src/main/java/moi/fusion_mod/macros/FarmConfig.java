package moi.fusion_mod.macros;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads and saves farmauto_config.json from the Minecraft instance root.
 * Path: FabricLoader.getInstance().getGameDir().resolve("farmauto_config.json")
 *
 * JSON structure (matching Python setup.py format):
 * {
 *   "farms": {
 *     "Wheat": {
 *       "waypoints": [
 *         {"type": "start", "x": 238.7, "y": 75, "z": 49.7},
 *         {"type": "turn", "x": -238.7, "y": 75, "z": 49.7},
 *         ...
 *         {"type": "end", "x": -238.7, "y": 67, "z": 49.7}
 *       ],
 *       "yaw": 0.0,
 *       "pitch": 5.0
 *     }
 *   }
 * }
 */
public class FarmConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FarmConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getGameDir().resolve("farmauto_config.json");

    // ── Parsed farm data ────────────────────────────────────────────────
    private static final Map<String, FarmData> farms = new LinkedHashMap<>();

    /** All crop names supported by setup (matches Python's CROPS list). */
    public static final String[] CROP_NAMES = {
            "PestFarming", "Wheat", "Carrot", "Potato", "Pumpkin", "Sugar_cane",
            "Melon", "Cactus", "Cocoa", "Mushroom_red", "Mushroom_brown",
            "Nether_wart", "Sunflower", "Wild_rose"
    };

    // ══════════════════════════════════════════════════════════════════════
    // Data classes
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A single recorded waypoint with type and coordinates.
     */
    public static class Waypoint {
        public final String type; // "start", "turn", or "end"
        public final double x, y, z;

        public Waypoint(String type, double x, double y, double z) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vec3 toVec3() {
            return new Vec3(x, y, z);
        }
    }

    /**
     * Data for a single farm: waypoints + locked yaw/pitch.
     */
    public static class FarmData {
        public final List<Waypoint> waypoints;
        public final float yaw;
        public final float pitch;

        public FarmData(List<Waypoint> waypoints, float yaw, float pitch) {
            this.waypoints = Collections.unmodifiableList(waypoints);
            this.yaw = yaw;
            this.pitch = pitch;
        }

        /**
         * Get the start waypoint (type="start"), or null if none exists.
         */
        public Waypoint getStartWaypoint() {
            for (Waypoint wp : waypoints) {
                if ("start".equals(wp.type)) return wp;
            }
            return waypoints.isEmpty() ? null : waypoints.get(0);
        }

        /**
         * Convert all waypoints to a Vec3 list for FarmHelper.setWaypoints().
         */
        public List<Vec3> toVec3List() {
            List<Vec3> list = new ArrayList<>(waypoints.size());
            for (Waypoint wp : waypoints) {
                list.add(wp.toVec3());
            }
            return list;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Get all loaded farm data. */
    public static Map<String, FarmData> getFarms() {
        return Collections.unmodifiableMap(farms);
    }

    /** Get farm data for a specific crop, or null if not configured. */
    public static FarmData getFarm(String cropName) {
        return farms.get(cropName);
    }

    /** Check if a crop is configured. */
    public static boolean hasFarm(String cropName) {
        return farms.containsKey(cropName);
    }

    /** Get all configured crop names. */
    public static Set<String> getConfiguredCrops() {
        return farms.keySet();
    }

    /**
     * Store farm data for a crop and save to disk immediately.
     */
    public static void setFarm(String cropName, List<Waypoint> waypoints, float yaw, float pitch) {
        farms.put(cropName, new FarmData(new ArrayList<>(waypoints), yaw, pitch));
        save();
    }

    /** Remove a farm configuration and save. */
    public static void removeFarm(String cropName) {
        farms.remove(cropName);
        save();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════

    /** Load config from disk. Called once at mod init. */
    public static void load() {
        farms.clear();
        try {
            if (!Files.exists(CONFIG_PATH)) {
                LOGGER.info("[FarmConfig] No config file found at {}, starting fresh", CONFIG_PATH);
                return;
            }

            String json = Files.readString(CONFIG_PATH);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("farms")) return;
            JsonObject farmsObj = root.getAsJsonObject("farms");

            for (Map.Entry<String, JsonElement> entry : farmsObj.entrySet()) {
                String cropName = entry.getKey();
                JsonObject farmObj = entry.getValue().getAsJsonObject();

                float yaw = farmObj.has("yaw") ? farmObj.get("yaw").getAsFloat() : 0f;
                float pitch = farmObj.has("pitch") ? farmObj.get("pitch").getAsFloat() : 0f;

                List<Waypoint> waypoints = new ArrayList<>();
                if (farmObj.has("waypoints")) {
                    JsonArray wpArray = farmObj.getAsJsonArray("waypoints");
                    for (JsonElement wpElem : wpArray) {
                        JsonObject wpObj = wpElem.getAsJsonObject();
                        String type = wpObj.has("type") ? wpObj.get("type").getAsString() : "turn";
                        double x = wpObj.get("x").getAsDouble();
                        double y = wpObj.get("y").getAsDouble();
                        double z = wpObj.get("z").getAsDouble();
                        waypoints.add(new Waypoint(type, x, y, z));
                    }
                }

                farms.put(cropName, new FarmData(waypoints, yaw, pitch));
            }

            LOGGER.info("[FarmConfig] Loaded {} farm(s) from {}", farms.size(), CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.error("[FarmConfig] Failed to load config", e);
        }
    }

    /** Save current config to disk. */
    public static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject farmsObj = new JsonObject();

            for (Map.Entry<String, FarmData> entry : farms.entrySet()) {
                JsonObject farmObj = new JsonObject();
                FarmData data = entry.getValue();

                JsonArray wpArray = new JsonArray();
                for (Waypoint wp : data.waypoints) {
                    JsonObject wpObj = new JsonObject();
                    wpObj.addProperty("type", wp.type);
                    wpObj.addProperty("x", Math.round(wp.x * 1000.0) / 1000.0);
                    wpObj.addProperty("y", Math.round(wp.y * 1000.0) / 1000.0);
                    wpObj.addProperty("z", Math.round(wp.z * 1000.0) / 1000.0);
                    wpArray.add(wpObj);
                }

                farmObj.add("waypoints", wpArray);
                farmObj.addProperty("yaw", Math.round(data.yaw * 10.0f) / 10.0f);
                farmObj.addProperty("pitch", Math.round(data.pitch * 10.0f) / 10.0f);

                farmsObj.add(entry.getKey(), farmObj);
            }

            root.add("farms", farmsObj);

            String json = GSON.toJson(root);
            Files.writeString(CONFIG_PATH, json);
            LOGGER.info("[FarmConfig] Saved {} farm(s) to {}", farms.size(), CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("[FarmConfig] Failed to save config", e);
        }
    }

    /** Get the config file path (for display purposes). */
    public static Path getConfigPath() {
        return CONFIG_PATH;
    }
}
