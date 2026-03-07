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

    private final Vector2i position = new Vector2i(4, 60);

    /** Entries currently displayed, newest first. Max 8 entries. */
    private final List<PickupEntry> entries = new ArrayList<>();

    /** Previous tick's inventory snapshot: itemId -> total count */
    private Map<String, Integer> previousInventory = null;

    /** How long entries stay visible (5 seconds = 100 ticks) */
    private static final int ENTRY_LIFETIME_TICKS = 100;

    /** Max entries shown */
    private static final int MAX_ENTRIES = 8;

    /** Cooldown to avoid detecting initial inventory load as pickups */
    private int initCooldown = 20;  // Skip first 20 ticks after joining

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
            initCooldown = 20;
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
        initCooldown = 20;
    }

    private void addGain(String itemName, int amount) {
        // Try to merge with existing entry for the same item
        for (PickupEntry entry : entries) {
            if (entry.itemName.equals(itemName)) {
                entry.amount += amount;
                entry.ticksRemaining = ENTRY_LIFETIME_TICKS; // Refresh timer
                return;
            }
        }

        // Add new entry
        entries.add(0, new PickupEntry(itemName, amount));
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
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

        int y = offsetY;

        // Background box (semi-transparent)
        int height = entries.size() * 11 + 4;
        int maxWidth = 0;
        for (PickupEntry entry : entries) {
            String text = "\u00A7a+ " + entry.amount + "x \u00A7f" + entry.itemName;
            int w = mc.font.width(text);
            if (w > maxWidth) maxWidth = w;
        }
        maxWidth += 6; // padding

        graphics.fill(offsetX - 2, offsetY - 2, offsetX + maxWidth, offsetY + height, 0x80000000);

        for (PickupEntry entry : entries) {
            // Fade out in the last 20 ticks
            float alpha = entry.ticksRemaining < 20 ? entry.ticksRemaining / 20.0f : 1.0f;
            int alphaInt = (int) (alpha * 255);
            if (alphaInt < 10) continue;

            int color = (alphaInt << 24) | 0xFFFFFF;
            String text = "\u00A7a+ " + entry.amount + "x \u00A7f" + entry.itemName;
            graphics.drawString(mc.font, Component.literal(text), offsetX, y, color);
            y += 11;
        }
    }
}
