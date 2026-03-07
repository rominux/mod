package moi.fusion_mod.economy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fetches and caches SkyBlock item price data from hysky.de APIs.
 *
 * API endpoints (from Skyblocker's TooltipInfoType):
 *   - Bazaar:     https://hysky.de/api/bazaar
 *   - Lowest BIN: https://hysky.de/api/auctions/lowestbins
 *   - NPC prices: https://hysky.de/api/npcprice
 *
 * Data is refreshed every 5 minutes on a background thread.
 */
public class PriceDataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("FusionMod/PriceDataManager");

    private static final String BAZAAR_URL = "https://hysky.de/api/bazaar";
    private static final String LOWEST_BINS_URL = "https://hysky.de/api/auctions/lowestbins";
    private static final String NPC_PRICE_URL = "https://hysky.de/api/npcprice";

    private static final int REFRESH_INTERVAL_MINUTES = 5;
    private static final int HTTP_TIMEOUT_SECONDS = 15;

    // ── Cached price data ───────────────────────────────────────────────────
    // Bazaar: skyblockApiId -> BazaarProduct(buyPrice, sellPrice)
    private static final ConcurrentHashMap<String, BazaarProduct> bazaarData = new ConcurrentHashMap<>();
    // Lowest BIN: skyblockApiId -> price
    private static final ConcurrentHashMap<String, Double> lowestBinData = new ConcurrentHashMap<>();
    // NPC sell price: skyblockId -> price
    private static final ConcurrentHashMap<String, Double> npcPriceData = new ConcurrentHashMap<>();

    private static volatile boolean dataLoaded = false;
    private static ScheduledExecutorService scheduler;

    /**
     * Bazaar product with optional buy/sell prices.
     * Mirrors Skyblocker's BazaarProduct record structure.
     */
    public record BazaarProduct(String id, String name, OptionalDouble buyPrice, OptionalDouble sellPrice) {
    }

    // ── Initialization ──────────────────────────────────────────────────────

    public static void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FusionMod-PriceData");
            t.setDaemon(true);
            return t;
        });

        // Initial fetch after 2 seconds, then every 5 minutes
        scheduler.scheduleAtFixedRate(PriceDataManager::refreshAll,
                2, REFRESH_INTERVAL_MINUTES * 60L, TimeUnit.SECONDS);

        LOGGER.info("[FusionMod] PriceDataManager initialized, will fetch prices every {} minutes", REFRESH_INTERVAL_MINUTES);
    }

    // ── Data access ─────────────────────────────────────────────────────────

    public static boolean isDataLoaded() {
        return dataLoaded;
    }

    /**
     * Get bazaar product data by SkyBlock API ID.
     * @return the BazaarProduct, or null if not found / not loaded
     */
    public static BazaarProduct getBazaarProduct(String skyblockApiId) {
        if (skyblockApiId == null || skyblockApiId.isEmpty()) return null;
        return bazaarData.get(skyblockApiId);
    }

    /**
     * Get lowest BIN price by SkyBlock API ID.
     * @return the price, or -1 if not found / not loaded
     */
    public static double getLowestBinPrice(String skyblockApiId) {
        if (skyblockApiId == null || skyblockApiId.isEmpty()) return -1;
        return lowestBinData.getOrDefault(skyblockApiId, -1.0);
    }

    /**
     * Get NPC sell price by SkyBlock item ID (internal ID).
     * @return the price, or -1 if not found / not loaded
     */
    public static double getNpcPrice(String skyblockId) {
        if (skyblockId == null || skyblockId.isEmpty()) return -1;
        return npcPriceData.getOrDefault(skyblockId, -1.0);
    }

    // ── Data fetching ───────────────────────────────────────────────────────

    private static void refreshAll() {
        try {
            fetchBazaar();
        } catch (Exception e) {
            LOGGER.warn("[FusionMod] Failed to fetch bazaar data", e);
        }
        try {
            fetchLowestBins();
        } catch (Exception e) {
            LOGGER.warn("[FusionMod] Failed to fetch lowest BIN data", e);
        }
        try {
            fetchNpcPrices();
        } catch (Exception e) {
            LOGGER.warn("[FusionMod] Failed to fetch NPC price data", e);
        }
        dataLoaded = true;
    }

    /**
     * Fetches bazaar data from hysky.de.
     * Response format: { "ITEM_ID": { "id": "...", "name": "...", "buyPrice": 123.4, "sellPrice": 100.0, ... }, ... }
     */
    private static void fetchBazaar() throws Exception {
        String json = httpGet(BAZAAR_URL);
        if (json == null) return;

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ConcurrentHashMap<String, BazaarProduct> newData = new ConcurrentHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String id = getStringOrDefault(obj, "id", entry.getKey());
                String name = getStringOrDefault(obj, "name", id);
                OptionalDouble buyPrice = getOptionalDouble(obj, "buyPrice");
                OptionalDouble sellPrice = getOptionalDouble(obj, "sellPrice");
                newData.put(entry.getKey(), new BazaarProduct(id, name, buyPrice, sellPrice));
            } catch (Exception e) {
                // Skip malformed entries
            }
        }

        if (!newData.isEmpty()) {
            bazaarData.clear();
            bazaarData.putAll(newData);
            LOGGER.debug("[FusionMod] Loaded {} bazaar products", newData.size());
        }
    }

    /**
     * Fetches lowest BIN data from hysky.de.
     * Response format: { "ITEM_ID": 123.45, ... }
     */
    private static void fetchLowestBins() throws Exception {
        String json = httpGet(LOWEST_BINS_URL);
        if (json == null) return;

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ConcurrentHashMap<String, Double> newData = new ConcurrentHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                newData.put(entry.getKey(), entry.getValue().getAsDouble());
            } catch (Exception e) {
                // Skip malformed entries
            }
        }

        if (!newData.isEmpty()) {
            lowestBinData.clear();
            lowestBinData.putAll(newData);
            LOGGER.debug("[FusionMod] Loaded {} lowest BIN prices", newData.size());
        }
    }

    /**
     * Fetches NPC sell prices from hysky.de.
     * Response format: { "ITEM_ID": 10.0, ... }
     */
    private static void fetchNpcPrices() throws Exception {
        String json = httpGet(NPC_PRICE_URL);
        if (json == null) return;

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ConcurrentHashMap<String, Double> newData = new ConcurrentHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                newData.put(entry.getKey(), entry.getValue().getAsDouble());
            } catch (Exception e) {
                // Skip malformed entries
            }
        }

        if (!newData.isEmpty()) {
            npcPriceData.clear();
            npcPriceData.putAll(newData);
            LOGGER.debug("[FusionMod] Loaded {} NPC prices", newData.size());
        }
    }

    // ── HTTP utility ────────────────────────────────────────────────────────

    private static String httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("User-Agent", "FusionMod/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.warn("[FusionMod] HTTP {} from {}", response.statusCode(), url);
            return null;
        }

        return response.body();
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private static OptionalDouble getOptionalDouble(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return OptionalDouble.of(obj.get(key).getAsDouble());
            } catch (Exception e) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }
}
