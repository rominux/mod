package moi.fusion_mod.hollows;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.events.BlockUpdateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest ESP for Crystal Hollows with despawn timer.
 *
 * Detects treasure chests via server block updates (BlockUpdateCallback),
 * tracks a 60-second despawn countdown per chest, and renders:
 *   - Wireframe box colored green-to-red based on time remaining
 *   - Countdown text below each chest (billboarded)
 *
 * Logic adapted from SkyHanni PowderChestTimer.kt.
 * Rendering uses the same drawBox / billboarded text approach as WaypointRenderer.
 *
 * Only active inside Crystal Hollows bounds (X/Z 202-823, Y 31-188).
 */
public class ChestEspRenderer {

    // ── Constants ───────────────────────────────────────────────────────
    /** Chest despawn time in milliseconds (60 seconds). */
    private static final long MAX_DURATION_MS = 60_000L;

    /** Max distance (blocks) to detect a new chest from the player. */
    private static final int MAX_DETECT_DISTANCE = 15;

    // ── Tracked chests: BlockPos -> despawn timestamp (System.currentTimeMillis) ──
    private static final Map<BlockPos, Long> chests = new ConcurrentHashMap<>();

    // ── Initialization ─────────────────────────────────────────────────
    /** Call once from Fusion_modClient to register the block update listener. */
    public static void init() {
        BlockUpdateCallback.EVENT.register(ChestEspRenderer::onBlockUpdate);
    }

    /** Clear all tracked chests (call on world change / disconnect). */
    public static void clear() {
        chests.clear();
    }

    // ── Block update listener ──────────────────────────────────────────
    private static void onBlockUpdate(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
                                       net.minecraft.world.level.block.state.BlockState newState) {
        if (!FusionConfig.isChestEspEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Only detect in Crystal Hollows
        if (!CrystalHollowsMapHud.isInCrystalHollows(
                mc.player.getX(), mc.player.getY(), mc.player.getZ())) return;

        double dist = mc.player.blockPosition().distSqr(pos);
        if (dist > MAX_DETECT_DISTANCE * MAX_DETECT_DISTANCE) return;

        boolean oldIsChest = oldState.is(Blocks.CHEST);
        boolean newIsChest = newState.is(Blocks.CHEST);

        if (!oldIsChest && newIsChest) {
            // Chest appeared — start 60s timer
            chests.put(pos.immutable(), System.currentTimeMillis() + MAX_DURATION_MS);
        } else if (oldIsChest && !newIsChest) {
            // Chest removed (broken / opened / despawned)
            chests.remove(pos);
        }
    }

    // ── Render (called from WorldRenderEvents.AFTER_ENTITIES) ──────────
    public static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Only render in Crystal Hollows
        if (!CrystalHollowsMapHud.isInCrystalHollows(
                mc.player.getX(), mc.player.getY(), mc.player.getZ())) return;

        if (chests.isEmpty()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        long now = System.currentTimeMillis();

        // Remove expired chests and chests that are open
        Iterator<Map.Entry<BlockPos, Long>> it = chests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
                continue;
            }
            // Remove if chest block entity is open
            if (mc.level.getBlockEntity(entry.getKey()) instanceof ChestBlockEntity chest) {
                if (chest.getOpenNess(1f) > 0f) {
                    it.remove();
                }
            }
        }

        if (chests.isEmpty()) return;

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        double playerY = mc.player.getY();

        // ── Draw wireframe boxes ────────────────────────────────────────
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        boolean foundAny = false;

        for (Map.Entry<BlockPos, Long> entry : chests.entrySet()) {
            BlockPos pos = entry.getKey();
            long despawnAt = entry.getValue();
            long remaining = despawnAt - now;

            Color color = getColorForTime(remaining);
            float r = color.getRed() / 255f;
            float g = color.getGreen() / 255f;
            float b = color.getBlue() / 255f;

            AABB box = new AABB(pos).move(-camPos.x, -camPos.y, -camPos.z);
            drawBox(context.matrices(), lineConsumer, box, r, g, b, 1.0f);
            foundAny = true;
        }

        if (foundAny) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            bufferSource.endBatch(RenderType.lines());
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }

        // ── Draw timer text below each chest ────────────────────────────
        for (Map.Entry<BlockPos, Long> entry : chests.entrySet()) {
            BlockPos pos = entry.getKey();
            long despawnAt = entry.getValue();
            long remaining = despawnAt - now;

            String timerText = formatTime(remaining);
            Color color = getColorForTime(remaining);
            String chatColor = toChatColor(color);
            String label = chatColor + timerText;

            // Position text above or below the chest depending on player Y
            double yOffset = (pos.getY() <= playerY) ? 1.25 : -0.25;
            double tx = pos.getX() + 0.5 - camPos.x;
            double ty = pos.getY() + yOffset - camPos.y;
            double tz = pos.getZ() + 0.5 - camPos.z;

            double distance = camPos.distanceTo(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));

            context.matrices().pushPose();
            context.matrices().translate(tx, ty, tz);

            // Billboard: face camera
            context.matrices().mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
            context.matrices().mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

            // Scale with distance
            float scale = 0.025f * (float) Math.max(1.0, distance / 10.0);
            context.matrices().scale(-scale, -scale, scale);

            float xOffset = -font.width(label) / 2.0f;
            Matrix4f positionMatrix = context.matrices().last().pose();

            MultiBufferSource.BufferSource immediate = mc.renderBuffers().bufferSource();
            font.drawInBatch(
                    label, xOffset, 0f,
                    0xFFFFFFFF, false,
                    positionMatrix, immediate,
                    Font.DisplayMode.SEE_THROUGH,
                    0xFF000000, 0xF000F0);
            immediate.endBatch();

            context.matrices().popPose();
        }
    }

    // ── Color computation (green -> red over 60 seconds) ────────────────
    /**
     * Returns a color interpolated from green (full time) to red (no time).
     * Exact formula from SkyHanni PowderChestTimer.
     */
    private static Color getColorForTime(long remainingMs) {
        double ratio = Math.max(0.0, Math.min(1.0, (double) remainingMs / MAX_DURATION_MS));
        int red = (int) (255 * (1.0 - ratio));
        int green = (int) (255 * ratio);
        return new Color(red, green, 0);
    }

    /**
     * Maps a continuous color to a Minecraft chat color code.
     */
    private static String toChatColor(Color c) {
        int r = c.getRed();
        int g = c.getGreen();
        if (r <= 127 && g >= 127) return "\u00A7a"; // green
        if (r <= 212 && g >= 42)  return "\u00A76"; // gold
        if (r <= 230 && g >= 25)  return "\u00A7c"; // red
        return "\u00A74"; // dark red
    }

    /** Formats remaining milliseconds as "Xs" (e.g. "42s"). */
    private static String formatTime(long remainingMs) {
        long seconds = Math.max(0, remainingMs / 1000);
        return seconds + "s";
    }

    // ── Wireframe box drawing (same as WaypointRenderer.drawBox) ────────
    private static void drawBox(PoseStack matrices, VertexConsumer vc,
                                 AABB box, float r, float g, float b, float a) {
        Matrix4f m = matrices.last().pose();
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        // Bottom face
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x1, y0, z0).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x0, y0, z1).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x1, y0, z0).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x1, y0, z1).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x0, y0, z1).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x1, y0, z1).setColor(r, g, b, a).setNormal(1, 0, 0);

        // Top face
        vc.addVertex(m, x0, y1, z0).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x1, y1, z0).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x0, y1, z0).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x0, y1, z1).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x1, y1, z0).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(0, 0, 1);
        vc.addVertex(m, x0, y1, z1).setColor(r, g, b, a).setNormal(1, 0, 0);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(1, 0, 0);

        // Vertical edges
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x0, y1, z0).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y0, z0).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y1, z0).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x0, y0, z1).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x0, y1, z1).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y0, z1).setColor(r, g, b, a).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(0, 1, 0);
    }
}
