package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import moi.fusion_mod.waypoints.WaypointRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commission tracker HUD with integrated 3D waypoint generation.
 *
 * Tab list parsing extracted from:
 *   - doc/Skyblocker/.../tabhud/widget/CommsWidget.java (COMM_PATTERN)
 *   - doc/Skyblocker/.../dwarven/CommissionLabels.java (tick, update logic)
 *
 * Commission-to-coordinate mapping extracted from:
 *   - doc/Skyblocker/.../dwarven/MiningLocationLabel.java (DwarvenCategory, DwarvenEmissaries, GlaciteCategory)
 *   - doc/Skyblocker/.../dwarven/CommissionLabels.java (commission.contains(key) matching)
 *
 * Coordinates are exact values from MiningLocationLabel enums.
 */
public class CommissionHud implements JarvisGuiManager.JarvisHud {
    // Extracted EXACTLY from
    // doc/skyblocker/src/main/java/de/hysky/skyblocker/skyblock/tabhud/widget/CommsWidget.java
    public static final Pattern COMM_PATTERN = Pattern.compile("(?<name>.*): (?<progress>.*)%?");

    // Hardcoded position for 2D UI for now
    private final Vector2i position = new Vector2i(10, 10);
    private List<String> currentCommissions = new ArrayList<>();

    // ── Commission-to-Location Mapping ──────────────────────────────────────
    // From doc/Skyblocker/.../dwarven/MiningLocationLabel.java (DwarvenCategory)
    // Key = substring to match in commission name, Value = { displayName, x, y, z, color }

    private static final String WAYPOINT_GROUP = "commissions";
    private List<String> lastCommissionNames = List.of();
    private boolean lastHadDone = false;

    /**
     * Dwarven Mines commission locations.
     * Coordinates extracted EXACTLY from MiningLocationLabel.DwarvenCategory enum.
     */
    private static final Map<String, CommissionLocation> DWARVEN_LOCATIONS = new LinkedHashMap<>();

    /**
     * Dwarven Emissary locations (shown when a commission is DONE).
     * Coordinates extracted EXACTLY from MiningLocationLabel.DwarvenEmissaries enum.
     */
    private static final List<CommissionLocation> DWARVEN_EMISSARIES = new ArrayList<>();

    /**
     * Glacite Tunnels commission locations.
     * Coordinates extracted EXACTLY from MiningLocationLabel.GlaciteCategory enum.
     */
    private static final Map<String, CommissionLocation[]> GLACITE_LOCATIONS = new LinkedHashMap<>();

    static {
        // ── Dwarven Mines (from MiningLocationLabel.DwarvenCategory) ──
        // Mithril color: 0x45BDE0, Titanium color: 0xD8D6D8
        DWARVEN_LOCATIONS.put("Lava Springs", new CommissionLocation("Lava Springs", 60, 197, -15, 0x45BDE0));
        DWARVEN_LOCATIONS.put("Cliffside Veins", new CommissionLocation("Cliffside Veins", 40, 128, 40, 0x45BDE0));
        DWARVEN_LOCATIONS.put("Rampart's Quarry", new CommissionLocation("Rampart's Quarry", -100, 150, -20, 0x45BDE0));
        DWARVEN_LOCATIONS.put("Upper Mines", new CommissionLocation("Upper Mines", -130, 174, -50, 0x45BDE0));
        DWARVEN_LOCATIONS.put("Royal Mines", new CommissionLocation("Royal Mines", 130, 154, 30, 0x45BDE0));
        DWARVEN_LOCATIONS.put("Glacite Walker", new CommissionLocation("Glacite Walker", 0, 128, 150, 0x45BDE0));

        // ── Emissary locations (from MiningLocationLabel.DwarvenEmissaries) ──
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", 58, 198, -8, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", 42, 134, 22, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", -72, 153, -10, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", -132, 174, -50, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", 171, 150, 31, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", -37, 200, -131, 0xFFFFFF));
        DWARVEN_EMISSARIES.add(new CommissionLocation("Emissary", 89, 198, -92, 0xFFFFFF));

        // ── Glacite Tunnels (from MiningLocationLabel.GlaciteCategory) ──
        GLACITE_LOCATIONS.put("Aquamarine", new CommissionLocation[]{
                new CommissionLocation("Aquamarine", 20, 136, 370, 0x334CB1),
                new CommissionLocation("Aquamarine", -14, 132, 386, 0x334CB1),
                new CommissionLocation("Aquamarine", 6, 137, 411, 0x334CB1),
                new CommissionLocation("Aquamarine", 50, 117, 302, 0x334CB1)
        });
        GLACITE_LOCATIONS.put("Onyx", new CommissionLocation[]{
                new CommissionLocation("Onyx", 4, 127, 307, 0x191919),
                new CommissionLocation("Onyx", -3, 139, 434, 0x191919),
                new CommissionLocation("Onyx", 77, 118, 411, 0x191919),
                new CommissionLocation("Onyx", -68, 130, 404, 0x191919)
        });
        GLACITE_LOCATIONS.put("Peridot", new CommissionLocation[]{
                new CommissionLocation("Peridot", 66, 144, 284, 0x667F33),
                new CommissionLocation("Peridot", 94, 154, 284, 0x667F33),
                new CommissionLocation("Peridot", -62, 147, 303, 0x667F33),
                new CommissionLocation("Peridot", -77, 119, 283, 0x667F33),
                new CommissionLocation("Peridot", 87, 122, 394, 0x667F33),
                new CommissionLocation("Peridot", -73, 122, 456, 0x667F33)
        });
        GLACITE_LOCATIONS.put("Citrine", new CommissionLocation[]{
                new CommissionLocation("Citrine", -86, 143, 261, 0x664C33),
                new CommissionLocation("Citrine", 74, 150, 327, 0x664C33),
                new CommissionLocation("Citrine", 63, 137, 343, 0x664C33),
                new CommissionLocation("Citrine", 38, 119, 386, 0x664C33),
                new CommissionLocation("Citrine", 55, 150, 400, 0x664C33),
                new CommissionLocation("Citrine", -45, 127, 415, 0x664C33),
                new CommissionLocation("Citrine", -60, 144, 424, 0x664C33),
                new CommissionLocation("Citrine", -54, 132, 410, 0x664C33)
        });
    }

    // ── Helper record ───────────────────────────────────────────────────────

    private static class CommissionLocation {
        final String name;
        final int x, y, z;
        final int color;

        CommissionLocation(String name, int x, int y, int z, int color) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }
    }

    // ── JarvisHud Interface ─────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return FusionConfig.isCommissionsEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 120;
    }

    @Override
    public int getUnscaledHeight() {
        return 10 + (currentCommissions.size() * 10);
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta) {
        updateCommissions();

        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null)
            return;

        // Comm list title header
        graphics.drawString(mc.font, "\u00A73\u00A7lCommissions", 0, 0, 0xFFFFFF);

        // Render commission lines
        int y = 10;
        for (String line : currentCommissions) {
            graphics.drawString(mc.font, line, 0, y, 0xFFFFFF);
            y += 10;
        }
    }

    // ── Commission Parsing & Waypoint Sync ──────────────────────────────────

    private void updateCommissions() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null)
            return;

        boolean foundCommissions = false;
        List<String> newCommissions = new ArrayList<>();
        List<String> newCommissionNames = new ArrayList<>();
        boolean hasDone = false;

        // Extracted EXACTLY from
        // doc/skyblocker/src/main/java/de/hysky/skyblocker/skyblock/dwarven/CommissionLabels.java
        for (PlayerInfo entry : mc.getConnection().getListedOnlinePlayers()) {
            Component displayName = entry.getTabListDisplayName();
            if (displayName == null)
                continue;

            String string = displayName.getString();
            if (foundCommissions) {
                if (!string.startsWith(" "))
                    break; // Comm blocks end when padding stops
                string = string.substring(1);
                Matcher matcher = COMM_PATTERN.matcher(string);
                if (matcher.matches()) {
                    String name = matcher.group("name");
                    String progress = matcher.group("progress");

                    newCommissionNames.add(name);

                    if ("DONE".equals(progress)) {
                        newCommissions.add("\u00A7b" + name + ": \u00A7a" + progress);
                        hasDone = true;
                    } else {
                        newCommissions.add("\u00A7b" + name + ": \u00A7e" + progress + "%");
                    }
                }
            } else if (string.startsWith("Commissions")) {
                foundCommissions = true;
            }
        }

        this.currentCommissions = newCommissions;

        // ── Sync waypoints only when commissions change ─────────────────
        // Logic from CommissionLabels.tick(): only update when list changes
        if (!newCommissionNames.equals(lastCommissionNames) || hasDone != lastHadDone) {
            lastCommissionNames = newCommissionNames;
            lastHadDone = hasDone;
            syncWaypoints(newCommissionNames, hasDone);
        }
    }

    /**
     * Generates 3D waypoints for active commissions.
     * Matching logic from CommissionLabels.update():
     *   - commission.contains(locationKey) maps to coordinate(s)
     *   - Titanium commissions use color 0xD8D6D8
     *   - If a commission is DONE, emissary waypoints are shown
     *
     * Glacite locations produce multiple waypoints per gemstone type.
     */
    private void syncWaypoints(List<String> commissionNames, boolean anyDone) {
        if (!FusionConfig.isCommissionWaypointsEnabled()) return;

        // Clear old commission waypoints
        WaypointRenderer.removeGroup(WAYPOINT_GROUP);

        // ── Match Dwarven Mines commissions ─────────────────────────────
        // Logic from CommissionLabels.update(): check if commission.contains(key)
        for (String commission : commissionNames) {
            // Dwarven locations
            for (Map.Entry<String, CommissionLocation> entry : DWARVEN_LOCATIONS.entrySet()) {
                if (commission.contains(entry.getKey())) {
                    CommissionLocation loc = entry.getValue();
                    // Titanium override: if commission contains "Titanium", use 0xD8D6D8
                    int color = commission.contains("Titanium") ? 0xD8D6D8 : loc.color;
                    WaypointRenderer.addWaypoint(new WaypointRenderer.Waypoint(
                            new BlockPos(loc.x, loc.y, loc.z),
                            loc.name, color, WAYPOINT_GROUP
                    ));
                }
            }

            // Glacite locations (multiple waypoints per gemstone)
            for (Map.Entry<String, CommissionLocation[]> entry : GLACITE_LOCATIONS.entrySet()) {
                if (commission.contains(entry.getKey())) {
                    for (CommissionLocation loc : entry.getValue()) {
                        WaypointRenderer.addWaypoint(new WaypointRenderer.Waypoint(
                                new BlockPos(loc.x, loc.y, loc.z),
                                loc.name, loc.color, WAYPOINT_GROUP
                        ));
                    }
                }
            }
        }

        // ── Show emissaries when a commission is DONE ───────────────────
        // Logic from CommissionLabels: if completed && showEmissary
        if (anyDone && FusionConfig.isCommissionWaypointsEnabled()) {
            for (CommissionLocation emissary : DWARVEN_EMISSARIES) {
                WaypointRenderer.addWaypoint(new WaypointRenderer.Waypoint(
                        new BlockPos(emissary.x, emissary.y, emissary.z),
                        emissary.name, emissary.color, WAYPOINT_GROUP
                ));
            }
        }
    }
}
