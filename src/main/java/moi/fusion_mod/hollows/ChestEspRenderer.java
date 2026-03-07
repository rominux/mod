package moi.fusion_mod.hollows;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

public class ChestEspRenderer {
    // Extracted exact variable state and math from
    // de.hysky.skyblocker.skyblock.dwarven.CrystalsChestHighlighter
    public static int waitingForChest = 0;
    public static final Set<BlockPos> activeChests = new HashSet<>();

    public static void render(WorldRenderContext context) {
        if (activeChests.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();
        VertexConsumer vertexConsumer = context.consumers().getBuffer(RenderType.lines());

        // Default orange ESP color
        float r = 1.0f, g = 0.5f, b = 0.0f, a = 1.0f;

        PoseStack matrices = context.matrices();

        for (BlockPos chest : activeChests) {
            // Verify the chest still exists in the world
            if (!client.level.getBlockState(chest).hasBlockEntity()) {
                continue;
            }

            Vec3 center = chest.getCenter().subtract(0, 0.0625, 0);
            AABB aabb = AABB.ofSize(center, 0.885, 0.885, 0.885).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            drawBox(matrices, vertexConsumer, aabb, r, g, b, a);
        }
    }

    /**
     * Draws a wireframe box using line segments.
     * Same approach as WaypointRenderer.drawBox.
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

        // 12 edges of the box
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
