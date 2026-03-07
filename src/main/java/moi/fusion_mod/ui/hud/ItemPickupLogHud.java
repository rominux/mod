package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;

/**
 * Tracks item pickups by comparing inventory snapshots each tick.
 * Displays recently gained items as a compact log on the HUD.
 *
 * Architecture: tick() is called from ClientTickEvents in Fusion_modClient.
 * It compares current inventory contents to the previous snapshot and
 * detects increases in item counts (= pickups / gains).
 */
public class ItemPickupLogHud implements JarvisGuiManager.JarvisHud {

    /** Static instance for external tick() calls */
    private static ItemPickupLogHud INSTANCE;

    /**
     * Default position: bottom-left, above chat area.
     * Y is set dynamically in render() based on screen height.
     */
    private final Vector2i position = new Vector2i(4, 0);

    /** Entries currently displayed, newest first. Max 8 entries. */
    private final List<PickupEntry> entries = new ArrayList<>();

    /** Previous tick's inventory snapshot: itemId -> total count */
    private Map<String, Integer> previousInventory = null;

    /** How long entries stay visible (5 seconds = 100 ticks) */
    private static final int ENTRY_LIFETIME_TICKS = 100;

    /** Max entries shown */
    private static final int MAX_ENTRIES = 8;

    /** Cooldown to avoid detecting initial inventory load as pickups */
    private int initCooldown = 40;  // Skip first 40 ticks (2 seconds) after joining

    private static class PickupEntry {
        final String itemName;
        int amount;
        int ticksRemaining;

        PickupEntry(String itemName, int amount) {
            this.itemName = itemName;
            this.amount = amount;
            this.ticksRemaining = ENTRY_LIFETIME_TICKS;
        }
    }

    public ItemPickupLogHud() {
        INSTANCE = this;
    }

    /** Get the singleton instance (created when JarvisGuiManager initializes HUDs). */
    public static ItemPickupLogHud getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean isEnabled() {
        return FusionConfig.isItemPickupLogEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 150;
    }

    @Override
    public int getUnscaledHeight() {
        return 12 + entries.size() * 11;
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    /**
     * Called every client tick from Fusion_modClient to detect inventory changes.
     */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // Tick down existing entries and remove expired ones
        entries.removeIf(e -> --e.ticksRemaining <= 0);

        if (player == null || player.getInventory() == null) {
            previousInventory = null;
            initCooldown = 40;
            return;
        }

        // Build current inventory snapshot
        Map<String, Integer> currentInventory = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String key = getItemKey(stack);
                currentInventory.merge(key, stack.getCount(), Integer::sum);
            }
        }

        // Handle init cooldown (skip first ticks to avoid false positives on join/world change)
        if (initCooldown > 0) {
            initCooldown--;
            previousInventory = currentInventory;
            return;
        }

        // Compare with previous snapshot
        if (previousInventory != null && isEnabled()) {
            for (Map.Entry<String, Integer> entry : currentInventory.entrySet()) {
                String key = entry.getKey();
                int current = entry.getValue();
                int previous = previousInventory.getOrDefault(key, 0);
                int gained = current - previous;

                if (gained > 0) {
                    addGain(key, gained);
                }
            }
        }

        previousInventory = currentInventory;
    }

    /**
     * Resets the tracker (e.g., on world change).
     */
    public void reset() {
        previousInventory = null;
        initCooldown = 40;  // 2 seconds grace period
        entries.clear();    // Clear display to prevent stale entries
    }

    /**
     * Called on server/island change to prevent inventory diff spam.
     * Clears the snapshot and adds a longer grace period.
     */
    public void resetOnWorldChange() {
        previousInventory = null;
        initCooldown = 60;  // 3 seconds grace period on world change
        entries.clear();
    }

    /**
     * New entries are appended at the END of the list (newest last).
     * Rendering draws from top to bottom, so newest item appears at bottom.
     */
    private void addGain(String itemName, int amount) {
        // Try to merge with existing entry for the same item
        for (PickupEntry entry : entries) {
            if (entry.itemName.equals(itemName)) {
                entry.amount += amount;
                entry.ticksRemaining = ENTRY_LIFETIME_TICKS; // Refresh timer
                return;
            }
        }

        // Add new entry at the end (newest at bottom)
        entries.add(new PickupEntry(itemName, amount));
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0); // Remove oldest (top)
        }
    }

    private String getItemKey(ItemStack stack) {
        // Use the display name for SkyBlock items (they have custom names)
        // This handles renamed items, custom SkyBlock items, etc.
        Component name = stack.getHoverName();
        String display = name.getString();
        // Strip color codes for the key used in deduplication
        return display.replaceAll("\u00A7.", "");
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null || entries.isEmpty()) return;

        // Compute the max text width for right-alignment
        int maxWidth = 0;
        for (PickupEntry entry : entries) {
            String text = "\u00A7a+ " + entry.amount + "x \u00A7f" + entry.itemName;
            int w = mc.font.width(text);
            if (w > maxWidth) maxWidth = w;
        }
        maxWidth += 6; // padding

        // Anchor to bottom-right, above chat area (~48px from bottom)
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int totalHeight = entries.size() * 11 + 4;

        // Right edge with 4px margin from screen edge
        int rightX = screenWidth - 4;
        int startX = rightX - maxWidth;
        int startY = screenHeight - 48 - totalHeight;

        // Background box (semi-transparent)
        graphics.fill(startX - 2, startY - 2, rightX, startY + totalHeight, 0x80000000);

        // Draw entries top-to-bottom (oldest at top, newest at bottom)
        int y = startY;
        for (PickupEntry entry : entries) {
            // Fade out in the last 20 ticks
            float alpha = entry.ticksRemaining < 20 ? entry.ticksRemaining / 20.0f : 1.0f;
            int alphaInt = (int) (alpha * 255);
            if (alphaInt < 10) { y += 11; continue; }

            int color = (alphaInt << 24) | 0xFFFFFF;
            String text = "\u00A7a+ " + entry.amount + "x \u00A7f" + entry.itemName;

            // Right-align text
            int textWidth = mc.font.width(text);
            int textX = rightX - textWidth - 2;
            graphics.drawString(mc.font, Component.literal(text), textX, y, color);
            y += 11;
        }
    }
}
