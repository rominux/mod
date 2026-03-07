package moi.fusion_mod.hollows;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.awt.Color;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crystal Hollows 2D Map HUD.
 *
 * Coordinate math, zone colors, player marker rendering, and discovery logic
 * extracted EXACTLY from:
 *   - doc/Skyblocker/.../dwarven/CrystalsHudWidget.java
 *   - doc/Skyblocker/.../dwarven/CrystalsLocationsManager.java
 *   - doc/Skyblocker/.../dwarven/MiningLocationLabel.java
 *
 * Zone bounding boxes cross-referenced with:
 *   - doc/SkyOcean/.../CrystalHollowsBB.kt
 *   - doc/SkyHanni/.../CrystalHollowsWalls.kt
 */
public class CrystalHollowsMapHud implements JarvisGuiManager.JarvisHud {

    // ── Map texture constants (from CrystalsHudWidget) ──────────────────────
    // The mod should include a 62x62 pixel crystals_map.png at this path.
    // If the texture is missing, we fall back to a solid dark background.
    private static final ResourceLocation MAP_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("fusion_mod", "textures/gui/crystals_map.png");
    private static final ResourceLocation MAP_ICON =
            ResourceLocation.withDefaultNamespace("textures/map/decorations/player.png");

    // ── Map geometry (from CrystalsHudWidget + CrystalsHudTest) ─────────────
    // Crystal Hollows world bounds: X ∈ [202, 823], Z ∈ [202, 823], Y ∈ [31, 188]
    // Map pixel size: 62×62 before scaling
    // Transform: pixelPos = (worldPos - 202) / 621 * 62, clamped to [0, 62]
    private static final int MAP_SIZE = 62;
    private static final double WORLD_MIN = 202.0;
    private static final double WORLD_RANGE = 621.0; // 823 - 202

    // ── Small locations get half the marker size (from CrystalsHudWidget) ────
    private static final List<String> SMALL_LOCATIONS = List.of(
            "Fairy Grotto", "King Yolkar", "Corleone", "Odawa",
            "Key Guardian", "Xalx", "Unknown"
    );

    // ── Zone colors (from MiningLocationLabel.CrystalHollowsLocationsCategory) ──
    // Each location has a Color from java.awt.Color or DyeColor.getTextColor().
    // DyeColor.PURPLE.getTextColor()  = 0x8932B8 (137, 50, 184)
    // DyeColor.ORANGE.getTextColor()  = 0xF9801D (249, 128, 29)
    public enum CrystalHollowsLocation {
        UNKNOWN("Unknown", Color.WHITE, null),
        JUNGLE_TEMPLE("Jungle Temple", new Color(0x8932B8), "[NPC] Kalhuiki Door Guardian:"),
        MINES_OF_DIVAN("Mines of Divan", Color.GREEN, "                                Jade Crystal"),
        GOBLIN_QUEENS_DEN("Goblin Queen's Den", new Color(0xF9801D), "                                Amber Crystal"),
        LOST_PRECURSOR_CITY("Lost Precursor City", Color.CYAN, "                                Sapphire Crystal"),
        KHAZAD_DUM("Khazad-d\u00FBm", Color.YELLOW, "                                Topaz Crystal"),
        FAIRY_GROTTO("Fairy Grotto", Color.PINK, null),
        DRAGONS_LAIR("Dragon's Lair", Color.BLACK, "[NPC] Golden Dragon:"),
        CORLEONE("Corleone", Color.WHITE, null),
        KING_YOLKAR("King Yolkar", Color.RED, "[NPC] King Yolkar:"),
        ODAWA("Odawa", Color.MAGENTA, "[NPC] Odawa:"),
        KEY_GUARDIAN("Key Guardian", Color.LIGHT_GRAY, null),
        XALX("Xalx", Color.GREEN, "[NPC] Xalx:");

        public final String name;
        public final Color color;
        public final String linkedMessage; // NPC chat prefix that confirms location

        CrystalHollowsLocation(String name, Color color, String linkedMessage) {
            this.name = name;
            this.color = color;
            this.linkedMessage = linkedMessage;
        }

        public int getColorRgb() {
            return color.getRGB();
        }
    }

    // ── Discovered waypoint storage ─────────────────────────────────────────
    public static class MapWaypoint {
        public final CrystalHollowsLocation location;
        public final double x, y, z;

        public MapWaypoint(CrystalHollowsLocation location, double x, double y, double z) {
            this.location = location;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // Active waypoints: location name -> waypoint
    private static final Map<String, MapWaypoint> activeWaypoints = new LinkedHashMap<>();
    private static final List<String> verifiedWaypoints = new ArrayList<>();

    // Chat coordinate regex (from CrystalsLocationsManager)
    // Matches patterns like "x512, y64, z512" or "512 64 512"
    private static final Pattern COORD_PATTERN =
            Pattern.compile("\\Dx?(\\d{3})(?=[, ]),? ?y?(\\d{2,3})(?=[, ]),? ?z?(\\d{3})\\D?(?!\\d)");

    // Lookup table: name -> enum value
    private static final Map<String, CrystalHollowsLocation> LOCATIONS_BY_NAME = new LinkedHashMap<>();

    static {
        for (CrystalHollowsLocation loc : CrystalHollowsLocation.values()) {
            LOCATIONS_BY_NAME.put(loc.name, loc);
        }
    }

    // ── HUD instance state ──────────────────────────────────────────────────
    private final Vector2i position = new Vector2i(10, 130);
    private int locationSize = 8; // configurable marker size (from MiningConfig.CrystalsHud)
    private float mapScale = 1.0f;

    // ── JarvisHud interface ─────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return FusionConfig.isCrystalMapEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return (int) (MAP_SIZE * mapScale);
    }

    @Override
    public int getUnscaledHeight() {
        return (int) (MAP_SIZE * mapScale);
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) return;

        // ── Scale transform (from CrystalsHudWidget.renderWidget) ───────
        graphics.pose().pushPose();
        graphics.pose().scale(mapScale, mapScale, 1.0f);

        // ── Draw map background ─────────────────────────────────────────
        // Try to blit the texture; if it fails (missing), draw a fallback
        try {
            graphics.blit(MAP_TEXTURE, 0, 0, 0, 0, MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE);
        } catch (Exception e) {
            // Fallback: dark translucent background
            graphics.fill(0, 0, MAP_SIZE, MAP_SIZE, 0xCC1A1A2E);
            // Draw zone quadrant tints (from SkyHanni CrystalHollowsWalls zone layout)
            // NW = Jungle (purple tint), NE = Mithril (green tint)
            // SW = Goblin (orange tint), SE = Precursor (cyan tint)
            int half = MAP_SIZE / 2;
            graphics.fill(0, 0, half, half, 0x308932B8);         // Jungle (NW)
            graphics.fill(half, 0, MAP_SIZE, half, 0x3000FF00);  // Mithril (NE)
            graphics.fill(0, half, half, MAP_SIZE, 0x30F9801D);  // Goblin (SW)
            graphics.fill(half, half, MAP_SIZE, MAP_SIZE, 0x3000FFFF); // Precursor (SE)
            // Nucleus center circle approximation
            int nx1 = (int) ((463 - WORLD_MIN) / WORLD_RANGE * MAP_SIZE);
            int nz1 = (int) ((460 - WORLD_MIN) / WORLD_RANGE * MAP_SIZE);
            int nx2 = (int) ((560 - WORLD_MIN) / WORLD_RANGE * MAP_SIZE);
            int nz2 = (int) ((563 - WORLD_MIN) / WORLD_RANGE * MAP_SIZE);
            graphics.fill(nx1, nz1, nx2, nz2, 0x40FFFFFF);      // Nucleus (center)
        }

        // ── Draw discovered waypoint markers (from CrystalsHudWidget) ───
        for (MapWaypoint wp : activeWaypoints.values()) {
            Vector2ic renderPos = transformLocation(wp.x, wp.z);
            int markerSize = locationSize;

            if (SMALL_LOCATIONS.contains(wp.location.name)) {
                markerSize /= 2;
            }

            // Fill square of markerSize centered on the coordinates
            graphics.fill(
                    renderPos.x() - markerSize / 2,
                    renderPos.y() - markerSize / 2,
                    renderPos.x() + markerSize / 2,
                    renderPos.y() + markerSize / 2,
                    wp.location.getColorRgb()
            );
        }

        // ── Draw player marker (from CrystalsHudWidget) ────────────────
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        float playerYaw = mc.player.getYRot();
        Vector2ic playerPos = transformLocation(playerX, playerZ);

        int renderX = playerPos.x() - 2;
        int renderY = playerPos.y() - 3;

        // Position, scale, and rotate the player marker
        // Exact logic from CrystalsHudWidget: translate -> scale 0.75 -> rotate
        graphics.pose().pushPose();
        graphics.pose().translate(renderX, renderY, 0);
        graphics.pose().scale(0.75f, 0.75f, 1.0f);

        // Rotate around marker center (2.5, 3.5) using yaw2Cardinal
        float cardinalYaw = yaw2Cardinal(playerYaw);
        // Apply rotation via pose matrix around the marker's visual center
        graphics.pose().translate(2.5f, 3.5f, 0);
        graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(cardinalYaw));
        graphics.pose().translate(-2.5f, -3.5f, 0);

        // Draw the vanilla map player icon (5x7 from an 8x8 sheet, offset 2,0)
        graphics.blit(MAP_ICON, 0, 0, 2, 0, 5, 7, 8, 8);
        graphics.pose().popPose();

        graphics.pose().popPose(); // pop scale transform
    }

    // ── Coordinate transform (EXACT from CrystalsHudWidget) ─────────────
    /**
     * Converts world X,Z to map pixel X,Y.
     * Formula: pixelPos = (worldPos - 202) / 621 * 62, clamped to [0, 62]
     */
    public static Vector2ic transformLocation(double x, double z) {
        int transformedX = (int) ((x - 202) / 621 * 62);
        int transformedY = (int) ((z - 202) / 621 * 62);
        transformedX = Math.clamp(transformedX, 0, 62);
        transformedY = Math.clamp(transformedY, 0, 62);
        return new Vector2i(transformedX, transformedY);
    }

    // ── Yaw to cardinal (EXACT from CrystalsHudWidget) ──────────────────
    /**
     * Converts yaw to 16-direction cardinal rotation for map markers.
     * Based on net.minecraft.client.renderer.MapRenderer logic.
     */
    private static float yaw2Cardinal(float yaw) {
        yaw += 180;
        byte clipped = (byte) ((yaw + (yaw < 0.0 ? -8.0 : 8.0)) * 16.0 / 360.0);
        return (clipped * 360f) / 16f;
    }

    // ── Bounds check (EXACT from CrystalsLocationsManager) ──────────────
    /**
     * Checks if a position is inside the Crystal Hollows bounds.
     * X: [202, 823], Z: [202, 823], Y: [31, 188]
     */
    public static boolean isInCrystalHollows(double x, double y, double z) {
        return x >= 202 && x <= 823
                && z >= 202 && z <= 823
                && y >= 31 && y <= 188;
    }

    // ── Waypoint management (from CrystalsLocationsManager) ─────────────

    public static void addWaypoint(CrystalHollowsLocation location, double x, double y, double z) {
        // Remove "Unknown" if a named waypoint is placed nearby (within 50 blocks)
        MapWaypoint unknown = activeWaypoints.get(CrystalHollowsLocation.UNKNOWN.name);
        if (unknown != null && location != CrystalHollowsLocation.UNKNOWN) {
            double dx = unknown.x - x;
            double dy = unknown.y - y;
            double dz = unknown.z - z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 50) {
                activeWaypoints.remove(CrystalHollowsLocation.UNKNOWN.name);
            }
        }
        activeWaypoints.put(location.name, new MapWaypoint(location, x, y, z));
    }

    public static void removeWaypoint(String name) {
        activeWaypoints.remove(name);
        verifiedWaypoints.remove(name);
    }

    public static void clearWaypoints() {
        activeWaypoints.clear();
        verifiedWaypoints.clear();
    }

    public static Map<String, MapWaypoint> getActiveWaypoints() {
        return Collections.unmodifiableMap(activeWaypoints);
    }

    /**
     * Called on chat messages to detect Crystal Hollows locations.
     * Logic extracted from CrystalsLocationsManager.extractLocationFromMessage
     */
    public static void onChatMessage(String text) {
        if (!FusionConfig.isCrystalMapEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 1) Check for NPC linked messages that confirm player is at a location
        for (CrystalHollowsLocation loc : CrystalHollowsLocation.values()) {
            if (loc.linkedMessage != null && text.startsWith(loc.linkedMessage)
                    && !verifiedWaypoints.contains(loc.name)) {
                addWaypoint(loc, mc.player.getX(), mc.player.getY(), mc.player.getZ());
                verifiedWaypoints.add(loc.name);
            }
        }

        // 2) Try to extract coordinates from chat messages
        if (text.contains(":")) {
            String userMessage = text.split(":", 2)[1];
            Matcher matcher = COORD_PATTERN.matcher(userMessage);
            if (matcher.find()) {
                try {
                    int cx = Integer.parseInt(matcher.group(1));
                    int cy = Integer.parseInt(matcher.group(2));
                    int cz = Integer.parseInt(matcher.group(3));

                    if (!isInCrystalHollows(cx, cy, cz)) return;

                    // Try to match a location name from the message text
                    for (Map.Entry<String, CrystalHollowsLocation> entry : LOCATIONS_BY_NAME.entrySet()) {
                        String locName = entry.getKey();
                        String[] words = locName.toLowerCase(Locale.ENGLISH).split(" ");
                        for (String word : words) {
                            if (userMessage.toLowerCase(Locale.ENGLISH).contains(word)) {
                                if (!activeWaypoints.containsKey(locName)) {
                                    addWaypoint(entry.getValue(), cx, cy, cz);
                                }
                                return;
                            }
                        }
                    }

                    // If no name matched, add as "Unknown"
                    if (!activeWaypoints.containsKey(CrystalHollowsLocation.UNKNOWN.name)) {
                        addWaypoint(CrystalHollowsLocation.UNKNOWN, cx, cy, cz);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /**
     * Periodic tick update: auto-discover zones from tab list area name.
     * Called from a client tick scheduler. Logic from CrystalsLocationsManager.update().
     */
    public static void tick() {
        if (!FusionConfig.isCrystalMapEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Only run if player is in Crystal Hollows bounds
        if (!isInCrystalHollows(mc.player.getX(), mc.player.getY(), mc.player.getZ())) return;

        // Try to detect current zone from the tab list's area display
        String areaName = detectAreaFromTabList();
        if (areaName != null && !areaName.equals("Unknown") && LOCATIONS_BY_NAME.containsKey(areaName)) {
            if (!activeWaypoints.containsKey(areaName)) {
                addWaypoint(LOCATIONS_BY_NAME.get(areaName),
                        mc.player.getX(), mc.player.getY(), mc.player.getZ());
            }
        }
    }

    /**
     * Attempts to read the current area name from the tab list.
     * Looks for a tab entry containing "Area:" or the zone name directly.
     */
    private static String detectAreaFromTabList() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;

        for (PlayerInfo entry : mc.getConnection().getListedPlayerInfos()) {
            Component displayName = entry.getTabListDisplayName();
            if (displayName == null) continue;
            String text = displayName.getString().trim();

            // Hypixel shows "Area: <zone name>" or " <zone_name>" in tab
            if (text.startsWith("Area: ") || text.startsWith("  ")) {
                String area = text.replace("Area: ", "").trim();
                // Strip color codes
                area = area.replaceAll("\u00A7.", "");
                if (!area.isEmpty()) {
                    return area;
                }
            }
        }
        return null;
    }
}
