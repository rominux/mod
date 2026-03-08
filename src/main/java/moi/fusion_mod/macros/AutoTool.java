package moi.fusion_mod.macros;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Automatic tool selection for farming. Scans the player's hotbar (slots 0-8)
 * to find the correct hoe/dicer/knife for a given crop, or a vacuum for pest killing.
 *
 * Tool names and vacuum ranges extracted from:
 *   - FarmHelper/PlayerUtils.java (internal SkyBlock IDs mapped to tool display names)
 *   - FarmHelper/PestsDestroyer.java (vacuum names and suction ranges)
 *   - SkyHanni/CropType.kt (crop-to-tool name prefixes)
 *
 * No manual slot configuration needed — the hotbar is scanned by item display name.
 */
public class AutoTool {

    // ══════════════════════════════════════════════════════════════════════
    // Crop → Tool name keywords (matched via item display name .contains())
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Maps a crop name (as used in FarmConfig) to an array of possible tool
     * display name substrings. Ordered from best (T3) to worst (T1/generic).
     * The first match found in the hotbar wins.
     */
    private static final Map<String, String[]> CROP_TOOL_KEYWORDS = new LinkedHashMap<>();
    static {
        // Standard hoes (Euclid's, Gauss, etc. — display names contain these substrings)
        CROP_TOOL_KEYWORDS.put("Wheat",          new String[]{"Wheat Hoe", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Carrot",         new String[]{"Carrot Hoe", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Potato",         new String[]{"Potato Hoe", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Nether_wart",    new String[]{"Nether Warts Hoe", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Sugar_cane",     new String[]{"Sugar Cane Hoe", "Gardening Hoe"});

        // Dicers
        CROP_TOOL_KEYWORDS.put("Pumpkin",        new String[]{"Pumpkin Dicer", "Gardening Axe"});
        CROP_TOOL_KEYWORDS.put("Melon",          new String[]{"Melon Dicer", "Gardening Axe"});

        // Specialty tools
        CROP_TOOL_KEYWORDS.put("Cactus",         new String[]{"Cactus Knife", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Cocoa",          new String[]{"Coco Chopper", "Gardening Axe"});
        CROP_TOOL_KEYWORDS.put("Mushroom_red",   new String[]{"Fungi Cutter", "Daedalus Axe", "Gardening Axe"});
        CROP_TOOL_KEYWORDS.put("Mushroom_brown", new String[]{"Fungi Cutter", "Daedalus Axe", "Gardening Axe"});

        // Flower tools
        CROP_TOOL_KEYWORDS.put("Sunflower",      new String[]{"Sunflower Hoe", "Gardening Hoe"});
        CROP_TOOL_KEYWORDS.put("Wild_rose",      new String[]{"Wild Rose Hoe", "Gardening Hoe"});

        // PestFarming — uses whatever hoe is available; any farming tool works
        CROP_TOOL_KEYWORDS.put("PestFarming",    new String[]{"Hoe", "Dicer", "Cutter", "Chopper", "Knife"});
    }

    // ══════════════════════════════════════════════════════════════════════
    // Vacuum names → suction ranges (from PestsDestroyer.java)
    // ══════════════════════════════════════════════════════════════════════

    /** Vacuum display name substring → suction range in blocks. */
    private static final LinkedHashMap<String, Float> VACUUM_RANGES = new LinkedHashMap<>();
    static {
        // Ordered from best to worst so the first .contains() match is the best vacuum
        VACUUM_RANGES.put("Hooverius",      15.0f);  // InfiniVacuum™ Hooverius
        VACUUM_RANGES.put("InfiniVacuum",   12.5f);  // InfiniVacuum (base)
        VACUUM_RANGES.put("Hyper Vacuum",   10.0f);
        VACUUM_RANGES.put("Turbo Vacuum",    7.5f);
        VACUUM_RANGES.put("Skymart Vacuum",  5.0f);
    }

    /** Default range if vacuum type cannot be determined. */
    private static final float DEFAULT_VACUUM_RANGE = 12.5f;

    // ── Cached state ────────────────────────────────────────────────────
    private static float currentVacuumRange = DEFAULT_VACUUM_RANGE;

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Scan the hotbar and select the best farming tool for the given crop.
     * Returns true if a matching tool was found and selected.
     */
    public static boolean selectToolForCrop(Minecraft mc, String cropName) {
        if (mc.player == null) return false;

        String[] keywords = CROP_TOOL_KEYWORDS.get(cropName);
        if (keywords == null) {
            // Unknown crop — try generic farming tool detection
            keywords = new String[]{"Hoe", "Dicer", "Cutter", "Chopper", "Knife"};
        }

        // For each keyword (best to worst), scan hotbar
        for (String keyword : keywords) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                String name = stack.getHoverName().getString();
                if (name.contains(keyword)) {
                    mc.player.getInventory().setSelectedSlot(slot);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Scan the hotbar and select the best vacuum for pest killing.
     * Also updates {@link #currentVacuumRange} with the detected vacuum's range.
     * Returns true if a vacuum was found and selected.
     */
    public static boolean selectVacuum(Minecraft mc) {
        if (mc.player == null) return false;

        // Find best vacuum in hotbar (scan all slots, pick highest range)
        int bestSlot = -1;
        float bestRange = 0f;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            if (!name.contains("Vacuum")) continue;

            float range = getVacuumRange(name);
            if (range > bestRange) {
                bestRange = range;
                bestSlot = slot;
            }
        }

        if (bestSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(bestSlot);
            currentVacuumRange = bestRange;
            return true;
        }

        return false;
    }

    /**
     * Get the suction range of the currently equipped vacuum.
     * Updated whenever {@link #selectVacuum} is called.
     */
    public static float getCurrentVacuumRange() {
        return currentVacuumRange;
    }

    /**
     * Determine the suction range for a vacuum by its display name.
     */
    public static float getVacuumRange(String displayName) {
        for (Map.Entry<String, Float> entry : VACUUM_RANGES.entrySet()) {
            if (displayName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_VACUUM_RANGE;
    }

    /**
     * Check if a given hotbar slot contains a farming tool for any crop.
     */
    public static boolean isFarmingTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = stack.getHoverName().getString();
        return name.contains("Hoe") || name.contains("Dicer") || name.contains("Cutter")
                || name.contains("Chopper") || name.contains("Knife") || name.contains("Daedalus Axe");
    }

    /**
     * Check if a given item is a vacuum.
     */
    public static boolean isVacuum(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getHoverName().getString().contains("Vacuum");
    }

    /**
     * Scan the hotbar and select a Fishing Rod.
     * Looks for items with "Rod" in the display name.
     * Returns the slot index if found and selected, or -1 if not found.
     */
    public static int selectFishingRod(Minecraft mc) {
        if (mc.player == null) return -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            if (name.contains("Rod")) {
                mc.player.getInventory().setSelectedSlot(slot);
                return slot;
            }
        }

        return -1;
    }
}
