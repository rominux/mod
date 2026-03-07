package moi.fusion_mod.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.ui.hud.ZoneInfoHud;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 3D Waypoint and Pickobulus preview renderer.
 *
 * Ported directly from pasunhack's PickobulusRender.java (Yarn -> Mojang):
 *   - Commission waypoints with beacon beams, text labels, distance display
 *   - Pickobulus 5x5x5 preview box (green wireframe)
 *   - Billboarded text rendering with SEE_THROUGH
 *   - Priority sorting (mithril > titanium > other)
 *
 * Yarn -> Mojang mapping:
 *   MatrixStack = PoseStack
 *   VertexConsumerProvider = MultiBufferSource
 *   RenderLayer.getLines() = RenderType.lines()
 *   Box = AABB
 *   Vec3d = Vec3
 *   BlockHitResult, HitResult same names
 *   RotationAxis.POSITIVE_Y = Axis.YP
 *   TextRenderer = Font
 *   TextLayerType.SEE_THROUGH = Font.DisplayMode.SEE_THROUGH
 */
public class WaypointRenderer {

    /**
     * Simple waypoint data class used by ZoneInfoHud.
     */
    public static class SimpleWaypoint {
        public final String name;
        public final int x, y, z;

        public SimpleWaypoint(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Main render entry point. Called from WorldRenderEvents.AFTER_ENTITIES.
     */
    public static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean showWaypoints = FusionConfig.isWaypointsEnabled() && FusionConfig.isCommissionWaypointsEnabled();
        boolean showPickobulus = FusionConfig.isPickobulusPreviewEnabled();

        if (!showWaypoints && !showPickobulus) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        MultiBufferSource consumers = context.consumers() != null
                ? context.consumers()
                : mc.renderBuffers().bufferSource();

        // ── Pickobulus 5x5x5 preview box ────────────────────────────────
        if (showPickobulus && ZoneInfoHud.isPickobulusAvailable) {
            String itemName = mc.player.getMainHandItem().isEmpty()
                    ? ""
                    : mc.player.getMainHandItem().getHoverName().getString().toLowerCase();

            boolean holdingTool = itemName.contains("pickaxe") || itemName.contains("drill")
                    || itemName.contains("gauntlet")
                    || mc.player.getMainHandItem().getItem().toString().contains("prismarine_shard");

            if (holdingTool) {
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
                    if (pos.closerToCenterThan(camPos, 20.0)) {
                        // 5x5x5 box centered on the target block (radius 2)
                        AABB box = new AABB(
                                pos.getX() - 2, pos.getY() - 2, pos.getZ() - 2,
                                pos.getX() + 3, pos.getY() + 3, pos.getZ() + 3
                        ).move(-camPos.x, -camPos.y, -camPos.z);

                        VertexConsumer lineConsumer = consumers.getBuffer(RenderType.lines());
                        drawBox(context.matrices(), lineConsumer, box, 0.0f, 1.0f, 0.0f, 1.0f);
                    }
                }
            }
        }

        // ── Commission waypoints ────────────────────────────────────────
        if (showWaypoints && ZoneInfoHud.isInDwarvenMines) {
            Font font = mc.font;
            MultiBufferSource.BufferSource immediate = mc.renderBuffers().bufferSource();

            // Sort waypoints by priority (mithril=1 > titanium=2 > other=3)
            List<SimpleWaypoint> sorted = new ArrayList<>(ZoneInfoHud.waypoints);
            sorted.sort((w1, w2) -> {
                String n1 = w1.name.toLowerCase();
                String n2 = w2.name.toLowerCase();
                int p1 = n1.contains("mithril") ? 1 : (n1.contains("titanium") ? 2 : 3);
                int p2 = n2.contains("mithril") ? 1 : (n2.contains("titanium") ? 2 : 3);
                return Integer.compare(p1, p2);
            });

            // Track stacked waypoints at same position
            Map<BlockPos, Integer> drawnCounts = new HashMap<>();

            for (SimpleWaypoint wp : sorted) {
                BlockPos bPos = new BlockPos(wp.x, wp.y, wp.z);
                int count = drawnCounts.getOrDefault(bPos, 0);
                drawnCounts.put(bPos, count + 1);

                // Draw beacon beam (thin pillar)
                AABB beamBox = new AABB(
                        wp.x - 0.1, wp.y, wp.z - 0.1,
                        wp.x + 0.1, wp.y + 100, wp.z + 0.1
                ).move(-camPos.x, -camPos.y, -camPos.z);

                VertexConsumer lineConsumer = immediate.getBuffer(RenderType.lines());
                drawBox(context.matrices(), lineConsumer, beamBox, 1.0f, 0.5f, 0.0f, 0.8f);

                // Calculate distance and build label
                Vec3 waypointVec = new Vec3(wp.x, wp.y, wp.z);
                double distance = camPos.distanceTo(waypointVec);
                String label = "\u00A7l" + wp.name + "\u00A7r \u00A7a(" + (int) distance + "m)";

                // Color coding (same as pasunhack)
                int textColor = 0xFFFFFFFF;
                String lowerName = wp.name.toLowerCase();
                if (lowerName.contains("goblin") || lowerName.contains("glacite")) {
                    textColor = 0xFFFF5555; // Red
                } else if (lowerName.contains("mithril")) {
                    textColor = 0xFF5555FF; // Blue
                } else if (lowerName.contains("emissary")) {
                    textColor = 0xFFFFAA00; // Orange
                }

                // Render billboarded text
                context.matrices().pushPose();

                double yOffset = 1.0 - (count * 0.4);
                context.matrices().translate(
                        waypointVec.x - camPos.x,
                        waypointVec.y + yOffset - camPos.y,
                        waypointVec.z - camPos.z);

                // Billboard rotation (face camera)
                context.matrices().mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
                context.matrices().mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

                // Scale text with distance
                float scale = 0.025f * (float) Math.max(2.0, distance / 10.0);
                context.matrices().scale(-scale, -scale, scale);

                // Render text (SEE_THROUGH so it shows through walls)
                float xOffset = -font.width(label) / 2.0f;
                Matrix4f positionMatrix = context.matrices().last().pose();

                font.drawInBatch(
                        label,
                        xOffset,
                        0f,
                        textColor,
                        false,
                        positionMatrix,
                        immediate,
                        Font.DisplayMode.SEE_THROUGH,
                        0xFF000000,  // Opaque black background
                        0xF000F0);   // Full brightness

                immediate.endBatch();
                context.matrices().popPose();
            }
        }
    }

    /**
     * Draws a wireframe box using line segments.
     * Ported directly from pasunhack's drawBox method.
     */
    private static void drawBox(PoseStack matrices, VertexConsumer vertexConsumer,
                                 AABB box, float r, float g, float b, float a) {
        Matrix4f matrix = matrices.last().pose();
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        // 12 edges of the box, each as a line segment with proper normals
        vertexConsumer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a).setNormal(1, 0, 0);
        vertexConsumer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a).setNormal(1, 0, 0);
        vertexConsumer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a).setNormal(0, 1, 0);
        vertexConsumer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a).setNormal(0, 1, 0);
        vertexConsumer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a).setNormal(0, 0, 1);
        vertexConsumer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a).setNormal(0, 0, 1);

        vertexConsumer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a).setNormal(0, -1, 0);
        vertexConsumer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a).setNormal(0, -1, 0);
        vertexConsumer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a).setNormal(-1, 0, 0);
        vertexConsumer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a).setNormal(-1, 0, 0);
        vertexConsumer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a).setNormal(0, 0, 1);
        vertexConsumer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a).setNormal(0, 0, 1);

        vertexConsumer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a).setNormal(1, 0, 0);
        vertexConsumer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a).setNormal(1, 0, 0);
        vertexConsumer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a).setNormal(0, -1, 0);
        vertexConsumer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a).setNormal(0, -1, 0);
        vertexConsumer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a).setNormal(0, 0, -1);
        vertexConsumer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a).setNormal(0, 0, -1);

        vertexConsumer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a).setNormal(-1, 0, 0);
        vertexConsumer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a).setNormal(-1, 0, 0);
        vertexConsumer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a).setNormal(0, 1, 0);
        vertexConsumer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a).setNormal(0, 1, 0);
        vertexConsumer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a).setNormal(0, 0, -1);
        vertexConsumer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a).setNormal(0, 0, -1);
    }
}
