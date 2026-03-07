package moi.fusion_mod.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import moi.fusion_mod.config.FusionConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D Waypoint Renderer for Fabric 1.21.10.
 *
 * Renders beacon beams, ESP boxes, and floating text labels in the world.
 * Designed for use with {@code WorldRenderEvents.AFTER_ENTITIES}.
 *
 * Rendering logic ported from:
 *   - doc/Skyblocker/.../waypoint/Waypoint.java  (Type enum, filled+outlined box, beacon)
 *   - doc/Skyblocker/.../primitive/FilledBoxRenderer.java  (6-face quad rendering)
 *   - doc/Skyblocker/.../primitive/OutlinedBoxRenderer.java  (12-edge line rendering)
 *   - doc/Firmament/.../render/RenderInWorldContext.kt  (block(), wireframeCube(), withFacingThePlayer(), text())
 *   - doc/SkyHanni/.../render/WorldRenderUtils.kt  (drawWaypointFilled(), drawString(), renderBeaconBeam())
 *   - doc/Odin/.../render/RenderUtils.kt  (drawCustomBeacon(), batched rendering)
 *
 * Math constants:
 *   - Default alpha: 0.5f (from Waypoint.DEFAULT_HIGHLIGHT_ALPHA)
 *   - Default line width: 5f (from Waypoint.DEFAULT_LINE_WIDTH)
 *   - Text scale: max(distance / 10, 1) * 0.025f (from NamedWaypoint + TextPrimitiveRenderer)
 *   - Beacon shown when distSq > 25 (5*5) (from SkyHanni drawWaypointFilled)
 *   - Filled box alpha scales with distance: (0.1f + 0.005f * distSq) clamped [0.2f, 1f] (from SkyHanni)
 */
public class WaypointRenderer {

    // ── Waypoint Data ───────────────────────────────────────────────────────

    /**
     * Waypoint type (from Skyblocker Waypoint.Type enum).
     */
    public enum WaypointType {
        /** Filled box + beacon beam */
        WAYPOINT,
        /** Filled box + beacon beam + outline */
        OUTLINED_WAYPOINT,
        /** Filled box only */
        HIGHLIGHT,
        /** Filled box + outline */
        OUTLINED_HIGHLIGHT,
        /** Outline only */
        OUTLINE
    }

    /**
     * A single waypoint in the world.
     */
    public static class Waypoint {
        public final BlockPos pos;
        public final String label;
        public final float[] color;  // RGBA: [r, g, b, a]
        public final WaypointType type;
        public final boolean throughWalls;
        public final String group; // group id for batch removal

        public Waypoint(BlockPos pos, String label, float[] color, WaypointType type, boolean throughWalls, String group) {
            this.pos = pos;
            this.label = label;
            this.color = color;
            this.type = type;
            this.throughWalls = throughWalls;
            this.group = group;
        }

        public Waypoint(BlockPos pos, String label, int rgb, WaypointType type, String group) {
            this(pos, label,
                    new float[]{
                            ((rgb >> 16) & 0xFF) / 255f,
                            ((rgb >> 8) & 0xFF) / 255f,
                            (rgb & 0xFF) / 255f,
                            0.5f // DEFAULT_HIGHLIGHT_ALPHA from Skyblocker
                    },
                    type, true, group);
        }

        public Waypoint(BlockPos pos, String label, int rgb, String group) {
            this(pos, label, rgb, WaypointType.WAYPOINT, group);
        }
    }

    // ── Active Waypoints ────────────────────────────────────────────────────

    private static final Map<String, Waypoint> waypoints = new ConcurrentHashMap<>();
    private static int nextId = 0;

    /**
     * Adds a waypoint and returns its unique key.
     */
    public static String addWaypoint(Waypoint waypoint) {
        String key = "wp_" + (nextId++);
        waypoints.put(key, waypoint);
        return key;
    }

    /**
     * Removes a waypoint by its key.
     */
    public static void removeWaypoint(String key) {
        waypoints.remove(key);
    }

    /**
     * Removes all waypoints belonging to a group.
     */
    public static void removeGroup(String group) {
        waypoints.entrySet().removeIf(e -> group.equals(e.getValue().group));
    }

    /**
     * Clears all waypoints.
     */
    public static void clearAll() {
        waypoints.clear();
    }

    public static Map<String, Waypoint> getWaypoints() {
        return Collections.unmodifiableMap(waypoints);
    }

    // ── Rendering Entry Point ───────────────────────────────────────────────

    /**
     * Called from {@code WorldRenderEvents.AFTER_ENTITIES}.
     * Renders all active waypoints.
     */
    public static void render(WorldRenderContext context) {
        if (waypoints.isEmpty()) return;
        if (!FusionConfig.isWaypointsEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 cameraPos = context.worldState().cameraRenderState.pos;
        PoseStack poseStack = context.matrices();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        for (Waypoint wp : waypoints.values()) {
            renderWaypoint(wp, poseStack, bufferSource, cameraPos, context);
        }

        bufferSource.endBatch();
    }

    // ── Per-Waypoint Rendering ──────────────────────────────────────────────

    private static void renderWaypoint(Waypoint wp, PoseStack poseStack,
                                        MultiBufferSource.BufferSource bufferSource,
                                        Vec3 cameraPos, WorldRenderContext context) {
        Vec3 wpPos = Vec3.atCenterOf(wp.pos);
        double distSq = cameraPos.distanceToSqr(wpPos);

        // ── Render based on type (from Skyblocker Waypoint.extractRendering) ──
        switch (wp.type) {
            case WAYPOINT:
                renderFilledBox(wp, poseStack, bufferSource, cameraPos, distSq);
                if (distSq > 25) renderBeaconBeam(wp, poseStack, bufferSource, cameraPos);
                break;
            case OUTLINED_WAYPOINT:
                renderFilledBox(wp, poseStack, bufferSource, cameraPos, distSq);
                renderOutlinedBox(wp, poseStack, bufferSource, cameraPos);
                if (distSq > 25) renderBeaconBeam(wp, poseStack, bufferSource, cameraPos);
                break;
            case HIGHLIGHT:
                renderFilledBox(wp, poseStack, bufferSource, cameraPos, distSq);
                break;
            case OUTLINED_HIGHLIGHT:
                renderFilledBox(wp, poseStack, bufferSource, cameraPos, distSq);
                renderOutlinedBox(wp, poseStack, bufferSource, cameraPos);
                break;
            case OUTLINE:
                renderOutlinedBox(wp, poseStack, bufferSource, cameraPos);
                break;
        }

        // ── Always render text label if present ──
        if (wp.label != null && !wp.label.isEmpty()) {
            renderText(wp, poseStack, bufferSource, cameraPos, distSq);
        }
    }

    // ── Filled Box (from FilledBoxRenderer + SkyHanni drawWaypointFilled) ──

    /**
     * Renders a filled translucent box at the waypoint position.
     * Alpha scales with distance: (0.1f + 0.005f * distSq) clamped [0.2f, 1f]
     * (from SkyHanni WorldRenderUtils.drawWaypointFilled)
     */
    private static void renderFilledBox(Waypoint wp, PoseStack poseStack,
                                         MultiBufferSource bufferSource,
                                         Vec3 cameraPos, double distSq) {
        float r = wp.color[0], g = wp.color[1], b = wp.color[2];
        // Dynamic alpha from SkyHanni: scales up with distance
        float alpha = Math.clamp((float) (0.1f + 0.005f * distSq), 0.2f, wp.color[3]);

        AABB box = new AABB(wp.pos);
        // Translate by negative camera position (from FilledBoxRenderer)
        AABB renderBox = box.move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        poseStack.pushPose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.debugFilledBox());
        // Render 6 faces as quads (from FilledBoxRenderer.submitPrimitives)
        renderFilledFaces(poseStack.last().pose(), consumer, renderBox, r, g, b, alpha);
        poseStack.popPose();
    }

    /**
     * Draws 6 quad faces for a filled box.
     * Vertex order extracted exactly from FilledBoxRenderer.submitPrimitives:
     * front, back, left, right, top, bottom faces.
     */
    private static void renderFilledFaces(Matrix4f matrix, VertexConsumer consumer,
                                           AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        // Front face (Z+)
        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);

        // Back face (Z-)
        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);

        // Left face (X-)
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);

        // Right face (X+)
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);

        // Top face (Y+)
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);

        // Bottom face (Y-)
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);
    }

    // ── Outlined Box (from OutlinedBoxRenderer + Firmament wireframeCube) ──

    /**
     * Renders wireframe lines around the waypoint box.
     * Uses LevelRenderer.renderLineBox which draws 12 edges.
     */
    private static void renderOutlinedBox(Waypoint wp, PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           Vec3 cameraPos) {
        float r = wp.color[0], g = wp.color[1], b = wp.color[2], a = wp.color[3];

        AABB box = new AABB(wp.pos);
        AABB renderBox = box.move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        poseStack.pushPose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        ShapeRenderer.renderLineBox(poseStack.last(), consumer, renderBox, r, g, b, a);
        poseStack.popPose();
    }

    // ── Beacon Beam (from SkyHanni renderBeaconBeam + Odin drawCustomBeacon) ──

    /**
     * Renders a simple beacon-like vertical beam.
     * Since we cannot access vanilla's BeaconRenderer.submitBeaconBeam without a mixin,
     * we render a tall thin outlined box as a visual beam approximation.
     *
     * The beam extends from Y=0 to Y=256, colored with the waypoint color.
     * This approach is used by several mods as a fallback.
     */
    private static void renderBeaconBeam(Waypoint wp, PoseStack poseStack,
                                          MultiBufferSource bufferSource,
                                          Vec3 cameraPos) {
        float r = wp.color[0], g = wp.color[1], b = wp.color[2];
        float beamAlpha = 0.3f;

        // Beam geometry: thin column from bedrock to sky
        // Width: 0.2 blocks (from Odin innerScale = 0.2f * scale)
        double beamHalfWidth = 0.1;
        double cx = wp.pos.getX() + 0.5;
        double cz = wp.pos.getZ() + 0.5;

        AABB beamBox = new AABB(
                cx - beamHalfWidth, 0, cz - beamHalfWidth,
                cx + beamHalfWidth, 256, cz + beamHalfWidth
        ).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        poseStack.pushPose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.debugFilledBox());
        renderFilledFaces(poseStack.last().pose(), consumer, beamBox, r, g, b, beamAlpha);
        poseStack.popPose();
    }

    // ── Floating Text Label (from TextPrimitiveRenderer + Firmament withFacingThePlayer) ──

    /**
     * Renders a floating text label above the waypoint.
     * Text scale: max(distance / 10, 1) * 0.025f (from NamedWaypoint)
     * Billboard transform: translate to pos, rotate by camera orientation, scale (s, -s, s)
     * (from TextPrimitiveRenderer and Firmament withFacingThePlayer)
     */
    private static void renderText(Waypoint wp, PoseStack poseStack,
                                    MultiBufferSource bufferSource,
                                    Vec3 cameraPos, double distSq) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (font == null) return;

        double distance = Math.sqrt(distSq);
        // Scale text with distance (from NamedWaypoint: Math.max(distanceToCamera / 10, 1))
        float scaleFactor = (float) Math.max(distance / 10.0, 1.0);
        // Final scale (from TextPrimitiveRenderer: state.scale * 0.025f)
        float scale = scaleFactor * 0.025f;

        // Position above the block
        Vec3 textPos = Vec3.atCenterOf(wp.pos).add(0, 1.5, 0);

        poseStack.pushPose();
        // Translate to waypoint position relative to camera
        poseStack.translate(
                textPos.x - cameraPos.x,
                textPos.y - cameraPos.y,
                textPos.z - cameraPos.z
        );

        // If distance > 10 blocks, move text position toward camera
        // (from Firmament withFacingThePlayer: capped at 10 block distance)
        if (distance > 10) {
            double factor = 10.0 / distance;
            poseStack.translate(
                    (cameraPos.x - textPos.x) * (1 - factor),
                    (cameraPos.y - textPos.y) * (1 - factor),
                    (cameraPos.z - textPos.z) * (1 - factor)
            );
        }

        // Billboard rotation: face the camera
        // (from TextPrimitiveRenderer: rotate by cameraState.orientation)
        Quaternionf cameraRotation = context_getCameraRotation();
        poseStack.mulPose(cameraRotation);

        // Scale with Y negated (from TextPrimitiveRenderer / Firmament: scale, -scale, scale)
        poseStack.scale(-scale, -scale, scale);

        // Draw label text centered
        int textWidth = font.width(wp.label);
        float textX = -textWidth / 2f;

        // Background quad (semi-transparent)
        int bgColor = 0x40000000;
        font.drawInBatch(wp.label, textX, 0, 0xFFFFFFFF, false,
                poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH,
                bgColor, 0xF000F0);

        // Distance display below the label
        String distText = String.format("%.0fm", distance);
        int distWidth = font.width(distText);
        font.drawInBatch(distText, -distWidth / 2f, 10, 0xFFAAAAAA, false,
                poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH,
                bgColor, 0xF000F0);

        poseStack.popPose();
    }

    /**
     * Gets the camera's billboard rotation quaternion.
     * This makes text/sprites face the player.
     */
    private static Quaternionf context_getCameraRotation() {
        Minecraft mc = Minecraft.getInstance();
        float pitch = mc.gameRenderer.getMainCamera().getXRot();
        float yaw = mc.gameRenderer.getMainCamera().getYRot();

        // Build a quaternion that faces the camera (billboard)
        // From Firmament: camera.rotation() is used directly
        Quaternionf rotation = new Quaternionf();
        rotation.rotationYXZ(
                (float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch),
                0f
        );
        return rotation;
    }
}
