package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import moi.fusion_mod.waypoints.WaypointRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Commission tracker HUD with zone detection and 3D waypoint generation.
 *
 * Logic ported from pasunhack CommissionsOverlay.java (Yarn -> Mojang mappings):
 *   - Tab list parsing with alphabetical sort
 *   - Area/Mithril/Gemstone/Pickobulus extraction
 *   - Commission color coding (red for bad, yellow for special, blue for mithril)
 *   - Emissary waypoints when DONE
 *   - Commission-to-location waypoint mapping
 */
public class CommissionHud implements JarvisGuiManager.JarvisHud {

    private final Vector2i position = new Vector2i(10, 10);

    // Shared state read by PickobulusTimerHud and WaypointRenderer
    public static volatile boolean isPickobulusAvailable = false;
    public static volatile boolean isInDwarvenMines = true;
    public static final List<WaypointRenderer.SimpleWaypoint> waypoints = new CopyOnWriteArrayList<>();
    public static final List<String> activeCommissions = new CopyOnWriteArrayList<>();
    // The display lines built each frame
    private List<String> displayLines = new ArrayList<>();

    public static class CommissionWaypoint {
        public final String name;
        public final int x, y, z;

        public CommissionWaypoint(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    @Override
    public boolean isEnabled() {
        return FusionConfig.isCommissionsEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 150;
    }

    @Override
    public int getUnscaledHeight() {
        return 10 + (displayLines.size() * 10);
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null || mc.player.connection == null)
            return;

        try {
            updateFromTabList(mc);
        } catch (Exception e) {
            // Silently ignore tab list parsing errors
        }

        if (displayLines.isEmpty())
            return;

        // Calculate dynamic width
        int maxW = 50;
        for (String s : displayLines) {
            int w = mc.font.width(s.replaceAll("\u00A7.", ""));
            if (w > maxW) maxW = w;
        }

        int h = displayLines.size() * 10 + 4;
        graphics.fill(offsetX - 3, offsetY - 3, offsetX + maxW + 8, offsetY + h, 0x90000000);

        int y = offsetY;
        for (String line : displayLines) {
            int color = 0xFFFFFFFF;
            // Area name line gets green color
            if (line.startsWith("\u00A7l"))
                color = 0xFF55FF55;
            else if (line.equals("\u00A7lCommissions"))
                color = 0xFFFFAA00;

            graphics.drawString(mc.font, Component.literal(line), offsetX, y, color);
            y += 10;
        }
    }

    /**
     * Parses the tab list exactly like pasunhack's CommissionsOverlay.
     * Extracts: Area, Mithril, Gemstone, Pickobulus, Commissions.
     * Generates waypoints for active commissions.
     */
    private void updateFromTabList(Minecraft mc) {
        boolean foundCommissions = false;
        List<String> commissionLines = new ArrayList<>();
        List<WaypointRenderer.SimpleWaypoint> newWaypoints = new ArrayList<>();

        // Sort tab list entries alphabetically (same as pasunhack)
        List<PlayerInfo> sortedEntries = new ArrayList<>(mc.player.connection.getListedOnlinePlayers());
        sortedEntries.sort((a, b) -> a.getProfile().name().compareToIgnoreCase(b.getProfile().name()));

        List<String> rawLines = new ArrayList<>();
        for (PlayerInfo entry : sortedEntries) {
            if (entry.getTabListDisplayName() != null) {
                rawLines.add(entry.getTabListDisplayName().getString());
            } else {
                rawLines.add(entry.getProfile().name());
            }
        }

        String areaName = "";
        String mithrilPowder = "";
        String gemstonePowder = "";
        String pickobulusStatus = "";
        boolean hasCompletedCommission = false;

        for (String string : rawLines) {
            // Strip formatting codes
            string = string.replaceAll("\u00A7.", "").trim();

            if (string.startsWith("Area: ")) {
                areaName = string.substring(6).trim();
            } else if (string.startsWith("Mithril: ")) {
                mithrilPowder = string.substring(9).trim();
            } else if (string.startsWith("Gemstone: ")) {
                gemstonePowder = string.substring(10).trim();
            } else if (string.startsWith("Pickobulus: ")) {
                pickobulusStatus = string.substring(12).trim();
            }

            if (string.equals("Commissions:") || string.equals("Commissions")) {
                foundCommissions = true;
                continue;
            }

            if (foundCommissions) {
                if (string.isEmpty() || string.endsWith(":")
                        || string.startsWith("Skills") || string.startsWith("Events")
                        || string.startsWith("Dungeons") || string.startsWith("Powders")
                        || string.startsWith("Pickaxe Ability")) {
                    foundCommissions = false;
                    continue;
                }

                if (string.contains(":")) {
                    String[] parts = string.split(":", 2);
                    String name = parts[0].trim();
                    String progress = parts[1].trim();

                    if (progress.equalsIgnoreCase("DONE")) {
                        hasCompletedCommission = true;
                        commissionLines.add(name + ": DONE");
                    } else if (!progress.isEmpty()) {
                        commissionLines.add(name + ": " + progress);
                    }
                }
            }
        }

        // Zone detection
        if (areaName.contains("Crystal Hollows")) {
            isInDwarvenMines = false;
        } else if (areaName.contains("Dwarven Mines") || areaName.contains("Glacite Tunnels")) {
            isInDwarvenMines = true;
        }

        // If not in a mining area, don't show the HUD
        if (!areaName.contains("Dwarven") && !areaName.contains("Crystal")
                && !areaName.contains("Glacite") && !areaName.contains("Mines")) {
            displayLines = new ArrayList<>();
            waypoints.clear();
            activeCommissions.clear();
            isPickobulusAvailable = false;
            return;
        }

        // Build display lines (same format as pasunhack)
        List<String> newDisplay = new ArrayList<>();
        if (!areaName.isEmpty())
            newDisplay.add("\u00A7l" + areaName);
        if (!mithrilPowder.isEmpty())
            newDisplay.add("Mithril: " + mithrilPowder);
        if (!gemstonePowder.isEmpty())
            newDisplay.add("\u00A7dGemstone: " + gemstonePowder);

        if (!pickobulusStatus.isEmpty()) {
            if (pickobulusStatus.contains("Available") || pickobulusStatus.contains("READY")
                    || pickobulusStatus.contains("Pr\u00EAt")) {
                isPickobulusAvailable = true;
                newDisplay.add("\u00A7d\u00A7lPickobulus: " + pickobulusStatus);
            } else {
                isPickobulusAvailable = false;
                newDisplay.add("Pickobulus: " + pickobulusStatus);
            }
        } else {
            isPickobulusAvailable = false;
        }

        if (!commissionLines.isEmpty()) {
            newDisplay.add(""); // separator
            newDisplay.add("\u00A7lCommissions");
            for (String c : commissionLines) {
                if (c.endsWith(" DONE")) {
                    newDisplay.add("\u00A7a\u00A7l" + c);
                } else {
                    String lower = c.toLowerCase();
                    if (lower.contains("star sentry puncher")
                            || lower.contains("glacite walker")
                            || (lower.contains("goblin slayer") && !lower.contains("golden")
                                    && !lower.contains("raid"))) {
                        newDisplay.add("\u00A7c" + c);
                    } else if (lower.contains("golden goblin slayer")
                            || lower.contains("goblin raid slayer")
                            || lower.contains("2x mithril powder collector")) {
                        newDisplay.add("\u00A7e" + c);
                    } else if (lower.contains("mithril")) {
                        newDisplay.add("\u00A7b" + c);
                    } else if (!lower.contains("titanium") && !lower.contains("aquamarine")
                            && !lower.contains("onyx") && !lower.contains("citrine")
                            && !lower.contains("peridot") && !lower.contains("slayer")
                            && !lower.contains("ice walker") && !lower.contains("goblin")) {
                        newDisplay.add("\u00A7e" + c);
                    } else {
                        newDisplay.add(c);
                    }
                }
            }
        }

        displayLines = newDisplay;

        // Update shared commissions list
        activeCommissions.clear();
        activeCommissions.addAll(commissionLines);

        // Generate waypoints (same logic as pasunhack)
        if (FusionConfig.isCommissionWaypointsEnabled()) {
            if (hasCompletedCommission) {
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", 58, 198, -8));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", 42, 134, 22));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", -72, 153, -10));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", -132, 174, -50));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", 171, 150, 31));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", -37, 200, -131));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", 89, 198, -92));
                newWaypoints.add(new WaypointRenderer.SimpleWaypoint("Emissary", -7, 126, 229));
            }

            for (String line : commissionLines) {
                String lowerLine = line.toLowerCase();
                String wpName = line.contains(":") ? line.split(":")[0].trim() : line;

                if (lowerLine.contains("upper mines")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -130, 174, -50));
                } else if (lowerLine.contains("royal mines")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 130, 154, 30));
                } else if (lowerLine.contains("lava springs") || lowerLine.contains("lava spring")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 60, 197, -15));
                } else if (lowerLine.contains("rampart's quarry") || lowerLine.contains("ramparts quarry")
                        || lowerLine.contains("rampart")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -100, 150, -20));
                } else if (lowerLine.contains("cliffside veins")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 40, 128, 40));
                } else if (lowerLine.contains("glacite walker")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 0, 128, 150));
                } else if (lowerLine.contains("aquamarine")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 20, 136, 370));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -14, 132, 386));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 6, 137, 411));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 50, 117, 302));
                } else if (lowerLine.contains("onyx")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 4, 127, 307));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -3, 139, 434));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 77, 118, 411));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -68, 130, 404));
                } else if (lowerLine.contains("peridot")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 66, 144, 284));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 94, 154, 284));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -62, 147, 303));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -77, 119, 283));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 87, 122, 394));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -73, 122, 456));
                } else if (lowerLine.contains("citrine")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -86, 143, 261));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 74, 150, 327));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 63, 137, 343));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 38, 119, 386));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, 55, 150, 400));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -45, 127, 415));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -60, 144, 424));
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -54, 132, 410));
                } else if ((lowerLine.contains("goblin") || lowerLine.contains("goblin burrows"))
                        && !lowerLine.contains("golden") && !lowerLine.contains("raid")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -40, 140, 140));
                } else if (lowerLine.contains("base camp") || lowerLine.contains("campfire")) {
                    newWaypoints.add(new WaypointRenderer.SimpleWaypoint(wpName, -7, 126, 229));
                }
            }
        }

        waypoints.clear();
        waypoints.addAll(newWaypoints);
    }
}
