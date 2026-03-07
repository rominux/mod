package moi.fusion_mod.ui.hud;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * Pickobulus HUD overlay.
 *
 * Logic ported from pasunhack:
 *   - Pickobulus availability is read from CommissionHud.isPickobulusAvailable
 *     (parsed from tab list "Pickobulus: Available/cooldown")
 *   - Block counter uses raycasting to find the aimed block, then counts
 *     blocks in a 5x5x5 cube (radius 2) around the target
 *   - Shows even without a pickaxe - just won't show the preview box
 */
public class PickobulusTimerHud implements JarvisGuiManager.JarvisHud {

    private final Vector2i position = new Vector2i(10, 80);

    // Block count cache to avoid raycasting every frame
    private int cachedBlockCount = 0;
    private long lastCountTime = 0;
    private BlockPos lastTargetPos = null;

    @Override
    public boolean isEnabled() {
        return FusionConfig.isPickobulusTimerEnabled();
    }

    @Override
    public int getUnscaledWidth() {
        return 140;
    }

    @Override
    public int getUnscaledHeight() {
        return 40;
    }

    @Override
    public Vector2ic getPosition() {
        return position;
    }

    @Override
    public void render(GuiGraphics graphics, float tickDelta, int offsetX, int offsetY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null || mc.player == null || mc.level == null)
            return;

        // Only show in mining areas (CommissionHud sets isInDwarvenMines)
        // But always show if enabled - the status is parsed from tab list

        String statusLine;
        int statusColor;

        if (CommissionHud.isPickobulusAvailable) {
            statusLine = "\u00A7a\u00A7lReady!";
            statusColor = 0xFF55FF55;
        } else {
            // Check tab list for cooldown info
            String cooldown = getPickobulusCooldown(mc);
            if (cooldown != null) {
                statusLine = "\u00A7cCooldown: " + cooldown;
                statusColor = 0xFFFF5555;
            } else {
                statusLine = "\u00A77No data";
                statusColor = 0xFFAAAAAA;
            }
        }

        // Count blocks if pickobulus is available and player is aiming at something
        String blockCountLine = null;
        if (CommissionHud.isPickobulusAvailable) {
            ItemStack held = mc.player.getMainHandItem();
            String itemName = held.isEmpty() ? "" : held.getHoverName().getString().toLowerCase();

            // Pickobulus works with pickaxes and drills (prismarine_shard is drill in SB)
            boolean holdingTool = itemName.contains("pickaxe") || itemName.contains("drill")
                    || itemName.contains("gauntlet");

            if (holdingTool || !held.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - lastCountTime > 200) { // Update every 200ms
                    lastCountTime = now;
                    updateBlockCount(mc);
                }
                if (cachedBlockCount > 0) {
                    blockCountLine = "\u00A7eBlocks: \u00A7f" + cachedBlockCount;
                }
            }
        }

        // Calculate box size
        int lineCount = 2; // title + status
        if (blockCountLine != null) lineCount++;
        int boxHeight = lineCount * 10 + 4;

        graphics.fill(offsetX - 2, offsetY - 2, offsetX + 140, offsetY + boxHeight, 0x90000000);

        int y = offsetY;
        graphics.drawString(mc.font, Component.literal("\u00A79\u00A7lPickobulus"), offsetX, y, 0xFF5555FF);
        y += 10;
        graphics.drawString(mc.font, Component.literal(statusLine), offsetX, y, statusColor);
        y += 10;
        if (blockCountLine != null) {
            graphics.drawString(mc.font, Component.literal(blockCountLine), offsetX, y, 0xFFFFFFFF);
        }
    }

    private String getPickobulusCooldown(Minecraft mc) {
        if (mc.player.connection == null) return null;

        for (var entry : mc.player.connection.getListedOnlinePlayers()) {
            var displayName = entry.getTabListDisplayName();
            if (displayName != null) {
                String text = displayName.getString().replaceAll("\u00A7.", "").trim();
                if (text.startsWith("Pickobulus: ")) {
                    String status = text.substring(12).trim();
                    if (!status.contains("Available") && !status.contains("READY")) {
                        return status;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Raycasts from the player's eye to find the aimed block,
     * then counts non-air blocks in a 5x5x5 cube (radius 2) around it.
     * This is the pickobulus blast radius preview.
     */
    private void updateBlockCount(Minecraft mc) {
        Vec3 start = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = start.add(look.x * 20, look.y * 20, look.z * 20);

        BlockHitResult hit = mc.level.clip(new ClipContext(
                start, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player));

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            lastTargetPos = pos;

            int count = 0;
            // Pickobulus radius = 2 blocks (5x5x5 cube)
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos check = pos.offset(dx, dy, dz);
                        if (!mc.level.getBlockState(check).isAir()) {
                            count++;
                        }
                    }
                }
            }
            cachedBlockCount = count;
        } else {
            cachedBlockCount = 0;
            lastTargetPos = null;
        }
    }

    /**
     * Returns the last aimed-at block position for the 3D preview box renderer.
     */
    public BlockPos getLastTargetPos() {
        return lastTargetPos;
    }
}
