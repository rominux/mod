package moi.fusion_mod.macros;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PestTracker — Tracks pest spawns, counts, and infested plot locations
 * by parsing the tab list, scoreboard, and chat messages.
 *
 * Designed for independent use by FarmHelper's pest hunting system.
 * ZoneInfoHud has similar parsing but only for HUD display; this class
 * exposes structured data for the macro state machine.
 */
public class PestTracker {

    // ══════════════════════════════════════════════════════════════════════
    // Patterns
    // ══════════════════════════════════════════════════════════════════════

    /** Scoreboard: "The Garden ൠ x3" or "Plot - [Melon] ൠ x2" */
    private static final Pattern PEST_SCOREBOARD_PATTERN =
            Pattern.compile(".*(?:The Garden|Plot).*ൠ.*x(\\d+)");

    /** Tab list: "Pests Alive: §r§c4" or similar formatted pest count */
    private static final Pattern PESTS_ALIVE_TAB_PATTERN =
            Pattern.compile("Pests? Alive:\\s*(\\d+)");

    /** Tab list: "Plots: ..." line listing infested plots */
    private static final Pattern INFESTED_PLOTS_TAB_PATTERN =
            Pattern.compile("\\s*Plots:\\s*(.+)");

    /** Tab list: pest bonus/cooldown timer, e.g. "Next Pest: 5m 2s" or "Pest Bonus: 1m 30s" */
    private static final Pattern PEST_COOLDOWN_TAB_PATTERN =
            Pattern.compile("(?:Next Pest|Pest Bonus|Pest Cooldown):\\s*(.+)");

    /** Chat message: "§6§lWARNING! §r§cA Pest has appeared in Plot - §r§e[Melon]§r§c!" */
    private static final Pattern PEST_SPAWN_CHAT_PATTERN =
            Pattern.compile(".*(?:Pest|Pests).*(?:appeared|spawned).*Plot.*\\[(.+?)].*");

    /** Chat message: "ൠ Pests have spawned in Plots 1, 3, 5!" */
    private static final Pattern PEST_SPAWN_PLOTS_CHAT_PATTERN =
            Pattern.compile(".*(?:Pest|Pests).*spawned.*Plot[s]?\\s+(.+?)!");

    // ══════════════════════════════════════════════════════════════════════
    // State
    // ══════════════════════════════════════════════════════════════════════

    /** Total pest count across all plots (from scoreboard/tablist). */
    private static int totalPestsAlive = 0;

    /** Pest count in the current plot (from scoreboard). */
    private static int currentPlotPests = 0;

    /** Raw infested plots string from tab list (e.g. "1, 3, 5"). */
    private static String infestedPlotsRaw = "";

    /** Parsed list of infested plot identifiers. */
    private static final List<String> infestedPlots = new ArrayList<>();

    /** Timestamp of last pest spawn chat message. */
    private static long lastPestSpawnTime = 0;

    /** Last plot name from pest spawn chat. */
    private static String lastPestSpawnPlot = "";

    /** Pest cooldown/bonus timer string from tab list (e.g. "5m 2s" or "READY"). */
    private static String pestCooldownTimer = "";

    /** Whether the pest cooldown has reached 0 (ready for fishing rod swap). */
    private static boolean pestCooldownReady = false;

    /**
     * Flag set by chat interception when "Pests have spawned" is detected.
     * Read and cleared by FarmHelper to trigger immediate pest hunting.
     */
    public static volatile boolean forcePestHunt = false;

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Get total pests alive across all garden plots. */
    public static int getTotalPestsAlive() {
        return totalPestsAlive;
    }

    /** Get pest count in the player's current plot (from scoreboard). */
    public static int getCurrentPlotPests() {
        return currentPlotPests;
    }

    /** Check if there are any pests alive in the garden. */
    public static boolean hasPests() {
        return totalPestsAlive > 0;
    }

    /** Get the list of infested plot identifiers. */
    public static List<String> getInfestedPlots() {
        return Collections.unmodifiableList(infestedPlots);
    }

    /** Get raw infested plots string. */
    public static String getInfestedPlotsRaw() {
        return infestedPlotsRaw;
    }

    /** Get the last plot where a pest was reported via chat. */
    public static String getLastPestSpawnPlot() {
        return lastPestSpawnPlot;
    }

    /** Get timestamp of last pest spawn chat message. */
    public static long getLastPestSpawnTime() {
        return lastPestSpawnTime;
    }

    /** Get the pest cooldown timer string (e.g. "5m 2s" or "READY" or empty). */
    public static String getPestCooldownTimer() {
        return pestCooldownTimer;
    }

    /** Check if the pest cooldown has reached 0 (ready for next pest spawn / rod swap). */
    public static boolean isPestCooldownReady() {
        return pestCooldownReady;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tick — called from FarmHelper.tick() or Fusion_modClient tick
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Update pest data from scoreboard and tab list.
     * Should be called periodically (every 20-40 ticks is sufficient).
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        parseScoreboard(mc);
        parseTabList(mc);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scoreboard parsing
    // ══════════════════════════════════════════════════════════════════════

    private static void parseScoreboard(Minecraft mc) {
        Scoreboard scoreboard = mc.level.getScoreboard();
        if (scoreboard == null) return;

        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        int pestsFromScoreboard = 0;
        int plotPests = 0;

        for (PlayerScoreEntry entry : scores) {
            String line = "";
            if (entry.display() != null) {
                line = entry.display().getString();
            } else {
                line = entry.owner();
            }
            String stripped = line.replaceAll("\u00A7.", "").trim();

            Matcher m = PEST_SCOREBOARD_PATTERN.matcher(stripped);
            if (m.matches()) {
                try {
                    int count = Integer.parseInt(m.group(1));
                    // Distinguish total garden vs current plot
                    if (stripped.contains("The Garden")) {
                        pestsFromScoreboard = count;
                    } else if (stripped.contains("Plot")) {
                        plotPests = count;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // Update state — scoreboard is authoritative for pest counts
        totalPestsAlive = pestsFromScoreboard;
        currentPlotPests = plotPests;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tab list parsing
    // ══════════════════════════════════════════════════════════════════════

    private static void parseTabList(Minecraft mc) {
        if (mc.player.connection == null) return;

        Collection<PlayerInfo> entries = mc.player.connection.getListedOnlinePlayers();
        int tabPestCount = -1; // -1 means not found in tab
        boolean foundCooldown = false;

        for (PlayerInfo info : entries) {
            Component displayName = info.getTabListDisplayName();
            if (displayName == null) continue;

            String text = displayName.getString().replaceAll("\u00A7.", "").trim();

            // "Pests Alive: 4"
            Matcher pestMatcher = PESTS_ALIVE_TAB_PATTERN.matcher(text);
            if (pestMatcher.find()) {
                try {
                    tabPestCount = Integer.parseInt(pestMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }

            // "Plots: 1, 3, 5"
            Matcher plotsMatcher = INFESTED_PLOTS_TAB_PATTERN.matcher(text);
            if (plotsMatcher.matches()) {
                String plots = plotsMatcher.group(1).trim();
                if (!plots.equals(infestedPlotsRaw)) {
                    infestedPlotsRaw = plots;
                    infestedPlots.clear();
                    // Split by comma and/or spaces
                    for (String part : plots.split("[,\\s]+")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            infestedPlots.add(trimmed);
                        }
                    }
                }
            }

            // "Next Pest: 5m 2s" or "Pest Bonus: 1m 30s"
            Matcher cooldownMatcher = PEST_COOLDOWN_TAB_PATTERN.matcher(text);
            if (cooldownMatcher.find()) {
                String timerStr = cooldownMatcher.group(1).trim();
                pestCooldownTimer = timerStr;
                foundCooldown = true;

                // Determine if cooldown is "ready" (0s, empty, or READY/Done keywords)
                String lower = timerStr.toLowerCase();
                pestCooldownReady = lower.isEmpty()
                        || lower.equals("0s")
                        || lower.contains("ready")
                        || lower.contains("done")
                        || lower.equals("0");
            }
        }

        // If no cooldown line found in tab, clear the timer
        if (!foundCooldown) {
            pestCooldownTimer = "";
            // If there's no cooldown line at all, treat as ready
            // (the line only shows when there IS a cooldown running)
            pestCooldownReady = true;
        }

        // If tab list has a pest count, use it as a fallback/override
        // (scoreboard may not always be visible)
        if (tabPestCount >= 0 && totalPestsAlive == 0) {
            totalPestsAlive = tabPestCount;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Chat message handling
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called from Fusion_modClient's chat listener when a game message is received.
     * Detects pest spawn announcements.
     */
    public static void onChatMessage(String message) {
        String stripped = message.replaceAll("\u00A7.", "").trim();

        // "A Pest has appeared in Plot - [Melon]!"
        Matcher m = PEST_SPAWN_CHAT_PATTERN.matcher(stripped);
        if (m.matches()) {
            lastPestSpawnPlot = m.group(1).trim();
            lastPestSpawnTime = System.currentTimeMillis();
            forcePestHunt = true;
            return;
        }

        // "Pests have spawned in Plots 1, 3, 5!"
        Matcher m2 = PEST_SPAWN_PLOTS_CHAT_PATTERN.matcher(stripped);
        if (m2.matches()) {
            lastPestSpawnPlot = m2.group(1).trim();
            lastPestSpawnTime = System.currentTimeMillis();
            forcePestHunt = true;
        }
    }

    /**
     * Reset all pest tracking state. Called when leaving the garden or disabling macros.
     */
    public static void reset() {
        totalPestsAlive = 0;
        currentPlotPests = 0;
        infestedPlotsRaw = "";
        infestedPlots.clear();
        lastPestSpawnTime = 0;
        lastPestSpawnPlot = "";
        pestCooldownTimer = "";
        pestCooldownReady = false;
        forcePestHunt = false;
    }
}
