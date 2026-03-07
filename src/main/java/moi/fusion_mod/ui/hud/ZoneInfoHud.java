package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import moi.fusion_mod.waypoints.WaypointRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal zone-aware HUD that replaces the old CommissionHud.
 *
 * Reads a layout template (List of Strings with {placeholders}) from FusionConfig
 * based on the detected zone (mining, garden, or fallback). Resolves each placeholder
 * by parsing the tab list, scoreboard, and action bar. Lines where all placeholders
 * resolve to empty are dynamically skipped.
 *
 * Preserves the static fields used by WaypointRenderer for commission waypoints
 * and pickobulus availability.
 */
public class ZoneInfoHud implements JarvisGuiManager.JarvisHud {

    private final Vector2i position = new Vector2i(10, 10);

    // ── Shared state read by WaypointRenderer ───────────────────────────
    public static volatile boolean isPickobulusAvailable = false;
    public static volatile boolean isInDwarvenMines = true;
    public static final List<WaypointRenderer.SimpleWaypoint> waypoints = new CopyOnWriteArrayList<>();
    public static final List<String> activeCommissions = new CopyOnWriteArrayList<>();

    // ── Display state ───────────────────────────────────────────────────
    private List<DisplayLine> displayLines = new ArrayList<>();

    // ── Parsed data cache (refreshed each frame from tab/scoreboard) ────
    private String areaName = "";
    private String mithrilPowder = "";
    private String gemstonePowder = "";
    private String glacitePowder = "";
    private String pickobulusStatus = "";
    private List<String> commissionLines = new ArrayList<>();
    private boolean hasCompletedCommission = false;

    // Garden data
    private int pestsAlive = 0;
    private String pestsPlots = "";
    private String sprayCooldown = "";
    private String visitorsInfo = "";
    private String jacobTimer = "";
    private String greenhouseTimer = "";

    // ── Zone detection ──────────────────────────────────────────────────
    private enum Zone { MINING, GARDEN, OTHER }
    private Zone currentZone = Zone.OTHER;

    // ── Patterns for scoreboard pest detection ──────────────────────────
    // SkyHanni: " §7⏣ §[ac]The Garden §4§lൠ§7 x<count>"
    private static final Pattern PEST_SCOREBOARD_PATTERN =
            Pattern.compile(".*The Garden.*ൠ.*x(\\d+)");
    // Plot-level pest pattern (stripped): "Plot - <name> ൠ x<count>"
    private static final Pattern PEST_PLOT_SCOREBOARD_PATTERN =
            Pattern.compile(".*Plot.*ൠ.*x(\\d+)");
    // Tab list infested plots: " Plots: 4, 12, 13, 18, 20"
    private static final Pattern INFESTED_PLOTS_PATTERN =
            Pattern.compile("\\s*Plots:\\s*(.+)");
    // Tab list spray: "Sprays: ..." or action bar spray timer
    private static final Pattern SPRAY_PATTERN =
            Pattern.compile("\\s*Spray:\\s*(.+)");

    /**
     * A single rendered line with color.
     */
    private static class DisplayLine {
        final String text;
        final int color;

        DisplayLine(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    // ── JarvisHud interface ─────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return FusionConfig.isZoneInfoHudEnabled();
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
            parseAllData(mc);
            buildDisplayLines();
        } catch (Exception e) {
            // Silently ignore parsing errors
        }

        if (displayLines.isEmpty())
            return;

        // Calculate dynamic width
        int maxW = 50;
        for (DisplayLine dl : displayLines) {
            int w = mc.font.width(dl.text.replaceAll("\u00A7.", ""));
            if (w > maxW) maxW = w;
        }

        int h = displayLines.size() * 10 + 4;
        graphics.fill(offsetX - 3, offsetY - 3, offsetX + maxW + 8, offsetY + h, 0x90000000);

        int y = offsetY;
        for (DisplayLine dl : displayLines) {
            graphics.drawString(mc.font, Component.literal(dl.text), offsetX, y, dl.color);
            y += 10;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DATA PARSING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parse all data sources: tab list (mining + garden), scoreboard (pests).
     */
    private void parseAllData(Minecraft mc) {
        // Reset all parsed fields
        areaName = "";
        mithrilPowder = "";
        gemstonePowder = "";
        glacitePowder = "";
        pickobulusStatus = "";
        commissionLines = new ArrayList<>();
        hasCompletedCommission = false;
        pestsAlive = 0;
        pestsPlots = "";
        sprayCooldown = "";
        visitorsInfo = "";
        jacobTimer = "";
        greenhouseTimer = "";

        parseTabList(mc);
        parseScoreboard(mc);
        detectZone();
        generateWaypoints();
    }

    /**
     * Parse tab list for all data: area, powders, pickobulus, commissions,
     * garden visitors, jacob timer, infested plots, spray.
     */
    private void parseTabList(Minecraft mc) {
        boolean foundCommissions = false;
        boolean foundVisitors = false;
        boolean foundJacob = false;

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

        List<String> visitorNames = new ArrayList<>();
        List<String> jacobLines = new ArrayList<>();

        for (String string : rawLines) {
            // Strip formatting codes for matching
            String stripped = string.replaceAll("\u00A7.", "").trim();

            // ── Area ────────────────────────────────────────────────
            if (stripped.startsWith("Area: ")) {
                areaName = stripped.substring(6).trim();
            }
            // ── Mithril Powder ──────────────────────────────────────
            else if (stripped.startsWith("Mithril: ")) {
                mithrilPowder = stripped.substring(9).trim();
            }
            // ── Gemstone Powder ─────────────────────────────────────
            else if (stripped.startsWith("Gemstone: ")) {
                gemstonePowder = stripped.substring(10).trim();
            }
            // ── Glacite Powder ──────────────────────────────────────
            else if (stripped.startsWith("Glacite: ") || stripped.startsWith("Glacite Powder: ")) {
                glacitePowder = stripped.contains(":") ?
                        stripped.substring(stripped.indexOf(':') + 1).trim() : "";
            }
            // ── Pickobulus ──────────────────────────────────────────
            else if (stripped.startsWith("Pickobulus: ") || stripped.startsWith("Pickaxe Ability: ")) {
                if (stripped.startsWith("Pickobulus: ")) {
                    pickobulusStatus = stripped.substring(12).trim();
                } else if (stripped.contains("Pickobulus")) {
                    pickobulusStatus = stripped.substring(stripped.indexOf(':') + 1).trim();
                }
            }

            // ── Infested Plots (Garden) ─────────────────────────────
            Matcher plotsMatcher = INFESTED_PLOTS_PATTERN.matcher(stripped);
            if (plotsMatcher.matches()) {
                pestsPlots = "Plots: " + plotsMatcher.group(1).trim();
            }

            // ── Spray cooldown ──────────────────────────────────────
            Matcher sprayMatcher = SPRAY_PATTERN.matcher(stripped);
            if (sprayMatcher.matches()) {
                sprayCooldown = sprayMatcher.group(1).trim();
            }

            // ── Commissions section ─────────────────────────────────
            if (stripped.equals("Commissions:") || stripped.equals("Commissions")) {
                foundCommissions = true;
                foundVisitors = false;
                foundJacob = false;
                continue;
            }

            // ── Visitors section (Garden) ───────────────────────────
            if (stripped.startsWith("Visitors")) {
                foundVisitors = true;
                foundCommissions = false;
                foundJacob = false;
                // Extract count from "Visitors (3):" pattern
                if (stripped.contains("(") && stripped.contains(")")) {
                    // Already have the header, visitors will be listed below
                }
                continue;
            }

            // ── Jacob's Contest section (Garden) ────────────────────
            if (stripped.contains("Jacob") && stripped.contains("Contest")) {
                foundJacob = true;
                foundCommissions = false;
                foundVisitors = false;
                continue;
            }

            // ── Parse commission lines ──────────────────────────────
            if (foundCommissions) {
                if (stripped.isEmpty() || stripped.endsWith(":")
                        || stripped.startsWith("Skills") || stripped.startsWith("Events")
                        || stripped.startsWith("Dungeons") || stripped.startsWith("Powders")
                        || stripped.startsWith("Pickaxe Ability")
                        || stripped.startsWith("Visitors") || stripped.startsWith("Jacob")) {
                    foundCommissions = false;
                    // Check if this line starts a new section
                    if (stripped.startsWith("Visitors")) {
                        foundVisitors = true;
                        continue;
                    }
                    if (stripped.contains("Jacob") && stripped.contains("Contest")) {
                        foundJacob = true;
                        continue;
                    }
                    continue;
                }

                if (stripped.contains(":")) {
                    String[] parts = stripped.split(":", 2);
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

            // ── Parse visitor lines ─────────────────────────────────
            if (foundVisitors) {
                if (stripped.isEmpty() || stripped.endsWith(":")
                        || stripped.startsWith("Skills") || stripped.startsWith("Events")
                        || stripped.startsWith("Commissions") || stripped.startsWith("Jacob")) {
                    foundVisitors = false;
                    if (stripped.equals("Commissions:") || stripped.equals("Commissions")) {
                        foundCommissions = true;
                    }
                    if (stripped.contains("Jacob") && stripped.contains("Contest")) {
                        foundJacob = true;
                    }
                    continue;
                }
                if (!stripped.isEmpty()) {
                    visitorNames.add(stripped);
                }
            }

            // ── Parse Jacob's Contest lines ─────────────────────────
            if (foundJacob) {
                if (stripped.isEmpty() || stripped.endsWith(":")
                        || stripped.startsWith("Skills") || stripped.startsWith("Events")
                        || stripped.startsWith("Commissions") || stripped.startsWith("Visitors")) {
                    foundJacob = false;
                    continue;
                }
                // Lines like "Starts in: 12m 30s" or "Wheat: 12m left"
                if (stripped.contains("left") || stripped.contains("Starts") || stripped.contains("Active")) {
                    jacobLines.add(stripped);
                }
            }
        }

        // Build visitor info string
        if (!visitorNames.isEmpty()) {
            visitorsInfo = "Visitors (" + visitorNames.size() + "): " + String.join(", ", visitorNames);
        }

        // Build jacob timer string
        if (!jacobLines.isEmpty()) {
            jacobTimer = String.join(" | ", jacobLines);
        }
    }

    /**
     * Parse scoreboard for pest count (Garden).
     * SkyHanni pattern: scoreboard line containing "The Garden" + "ൠ" + "x<count>"
     */
    private void parseScoreboard(Minecraft mc) {
        if (mc.level == null) return;

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        for (PlayerScoreEntry entry : scores) {
            // Get the display text for this scoreboard line
            String line = "";
            if (entry.display() != null) {
                line = entry.display().getString();
            } else {
                line = entry.owner();
            }
            String stripped = line.replaceAll("\u00A7.", "").trim();

            // Check for pest patterns
            Matcher pestMatcher = PEST_SCOREBOARD_PATTERN.matcher(stripped);
            if (pestMatcher.matches()) {
                try {
                    pestsAlive = Integer.parseInt(pestMatcher.group(1));
                } catch (NumberFormatException e) {
                    pestsAlive = 0;
                }
            }
            Matcher plotPestMatcher = PEST_PLOT_SCOREBOARD_PATTERN.matcher(stripped);
            if (plotPestMatcher.matches()) {
                try {
                    pestsAlive += Integer.parseInt(plotPestMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /**
     * Detect current zone from area name.
     */
    private void detectZone() {
        if (areaName.isEmpty()) {
            currentZone = Zone.OTHER;
            return;
        }

        String lower = areaName.toLowerCase();
        if (lower.contains("dwarven") || lower.contains("crystal")
                || lower.contains("glacite") || lower.contains("mines")
                || lower.contains("precursor") || lower.contains("magma")
                || lower.contains("mithril") || lower.contains("goblin")
                || lower.contains("forge")) {
            currentZone = Zone.MINING;
        } else if (lower.contains("garden") || lower.contains("plot")
                || lower.contains("barn")) {
            currentZone = Zone.GARDEN;
        } else {
            currentZone = Zone.OTHER;
        }

        // Update static zone flag for WaypointRenderer
        if (lower.contains("crystal")) {
            isInDwarvenMines = false;
        } else if (lower.contains("dwarven") || lower.contains("glacite")
                || lower.contains("mines")) {
            isInDwarvenMines = true;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PLACEHOLDER RESOLUTION + DISPLAY LINE BUILDING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build the display lines from the zone-appropriate layout template.
     * Each line's placeholders are resolved. Lines where all placeholder values
     * are empty/zero are skipped.
     */
    private void buildDisplayLines() {
        List<String> layout;
        switch (currentZone) {
            case MINING:
                layout = FusionConfig.getMiningHudLayout();
                break;
            case GARDEN:
                layout = FusionConfig.getGardenHudLayout();
                break;
            default:
                layout = FusionConfig.getDefaultHudLayout();
                break;
        }

        // If in an area not matching any zone, and area is not a SkyBlock area at all,
        // show nothing (prevents HUD from showing in lobbies etc.)
        if (currentZone == Zone.OTHER && areaName.isEmpty()) {
            displayLines = new ArrayList<>();
            waypoints.clear();
            activeCommissions.clear();
            isPickobulusAvailable = false;
            return;
        }

        List<DisplayLine> newLines = new ArrayList<>();

        for (String templateLine : layout) {
            // Empty template line = blank separator
            if (templateLine.isEmpty()) {
                // Only add separator if we already have content
                if (!newLines.isEmpty()) {
                    newLines.add(new DisplayLine("", 0xFFFFFFFF));
                }
                continue;
            }

            // Special multi-line placeholder: {commissions}
            if (templateLine.trim().equals("{commissions}")) {
                if (!commissionLines.isEmpty()) {
                    for (String c : commissionLines) {
                        newLines.add(colorizeCommission(c));
                    }
                }
                continue;
            }

            // Resolve placeholders in this line
            String resolved = resolvePlaceholders(templateLine);

            // Check if any placeholder was present and all resolved to empty
            if (templateLine.contains("{") && isEffectivelyEmpty(templateLine, resolved)) {
                continue; // Skip this line
            }

            // Determine line color
            int color = determineLineColor(templateLine, resolved);
            newLines.add(new DisplayLine(resolved, color));
        }

        // Remove trailing empty lines
        while (!newLines.isEmpty() && newLines.get(newLines.size() - 1).text.isEmpty()) {
            newLines.remove(newLines.size() - 1);
        }

        displayLines = newLines;

        // Update pickobulus availability
        if (!pickobulusStatus.isEmpty()) {
            isPickobulusAvailable = pickobulusStatus.contains("Available")
                    || pickobulusStatus.contains("READY")
                    || pickobulusStatus.contains("Pr\u00EAt");
        } else {
            isPickobulusAvailable = false;
        }

        // Update shared commissions list
        activeCommissions.clear();
        activeCommissions.addAll(commissionLines);
    }

    /**
     * Resolve all {placeholder} tokens in a template line.
     */
    private String resolvePlaceholders(String template) {
        String result = template;

        result = result.replace("{location}", areaName.isEmpty() ? "" : "\u00A7l" + areaName);
        result = result.replace("{mithril_powder}", mithrilPowder);
        result = result.replace("{gemstone_powder}", gemstonePowder.isEmpty() ? "" : "\u00A7d" + gemstonePowder);
        result = result.replace("{glacite_powder}", glacitePowder);
        result = result.replace("{pickobulus}", resolvePickobulus());
        result = result.replace("{pests_alive}", pestsAlive > 0 ? String.valueOf(pestsAlive) : "");
        result = result.replace("{pests_plots}", pestsPlots);
        result = result.replace("{pest_cooldown}", sprayCooldown);
        result = result.replace("{visitors}", visitorsInfo);
        result = result.replace("{jacob_timer}", jacobTimer);
        result = result.replace("{greenhouse_timer}", greenhouseTimer);

        return result;
    }

    /**
     * Resolve pickobulus placeholder with color coding.
     */
    private String resolvePickobulus() {
        if (pickobulusStatus.isEmpty()) return "";
        if (pickobulusStatus.contains("Available") || pickobulusStatus.contains("READY")
                || pickobulusStatus.contains("Pr\u00EAt")) {
            return "\u00A7d\u00A7l" + pickobulusStatus;
        }
        return pickobulusStatus;
    }

    /**
     * Check if a line with placeholders resolved to effectively empty content.
     * A line like "Mithril: {mithril_powder}" with empty powder should be skipped.
     * A line like "Commissions:" (static label with no placeholder) should NOT be skipped.
     */
    private boolean isEffectivelyEmpty(String template, String resolved) {
        // Get the static part of the template (everything not in {})
        String staticPart = template.replaceAll("\\{[^}]+}", "").trim();
        // Get the resolved content stripped of formatting codes
        String resolvedClean = resolved.replaceAll("\u00A7.", "").trim();

        // If the resolved text is just the static label text (all placeholders were empty)
        return resolvedClean.equals(staticPart) || resolvedClean.isEmpty();
    }

    /**
     * Determine color for a resolved display line based on its content.
     */
    private int determineLineColor(String template, String resolved) {
        // Location line
        if (template.contains("{location}")) return 0xFF55FF55;
        // Pickobulus with availability
        if (template.contains("{pickobulus}") && isPickobulusAvailable) return 0xFFFFFFFF;
        // Gemstone powder
        if (template.contains("{gemstone_powder}")) return 0xFFFFFFFF;
        // Static "Commissions:" label
        if (resolved.contains("Commissions:")) return 0xFFFFAA00;
        // Pests alive (red for danger)
        if (template.contains("{pests_alive}") && pestsAlive > 0) return 0xFFFF5555;
        // Jacob timer
        if (template.contains("{jacob_timer}")) return 0xFFFFAA00;
        // Visitors
        if (template.contains("{visitors}")) return 0xFF55FFFF;

        return 0xFFFFFFFF;
    }

    /**
     * Colorize a commission line based on its content (same logic as pasunhack).
     */
    private DisplayLine colorizeCommission(String line) {
        String lower = line.toLowerCase();

        if (line.endsWith("DONE")) {
            return new DisplayLine("\u00A7a\u00A7l" + line, 0xFFFFFFFF);
        }
        if (lower.contains("star sentry puncher")
                || lower.contains("glacite walker")
                || (lower.contains("goblin slayer") && !lower.contains("golden")
                && !lower.contains("raid"))) {
            return new DisplayLine("\u00A7c" + line, 0xFFFFFFFF);
        }
        if (lower.contains("golden goblin slayer")
                || lower.contains("goblin raid slayer")
                || lower.contains("2x mithril powder collector")) {
            return new DisplayLine("\u00A7e" + line, 0xFFFFFFFF);
        }
        if (lower.contains("mithril")) {
            return new DisplayLine("\u00A7b" + line, 0xFFFFFFFF);
        }
        if (!lower.contains("titanium") && !lower.contains("aquamarine")
                && !lower.contains("onyx") && !lower.contains("citrine")
                && !lower.contains("peridot") && !lower.contains("slayer")
                && !lower.contains("ice walker") && !lower.contains("goblin")) {
            return new DisplayLine("\u00A7e" + line, 0xFFFFFFFF);
        }
        return new DisplayLine(line, 0xFFFFFFFF);
    }

    // ══════════════════════════════════════════════════════════════════════
    // WAYPOINT GENERATION (preserved from CommissionHud)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate 3D waypoints for active commissions.
     * Same logic as the original CommissionHud / pasunhack.
     */
    private void generateWaypoints() {
        List<WaypointRenderer.SimpleWaypoint> newWaypoints = new ArrayList<>();

        if (!FusionConfig.isCommissionWaypointsEnabled()) {
            waypoints.clear();
            return;
        }

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

        waypoints.clear();
        waypoints.addAll(newWaypoints);
    }
}
