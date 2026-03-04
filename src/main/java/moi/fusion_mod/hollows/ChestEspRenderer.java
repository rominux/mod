package moi.fusion_mod.hollows;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
        if (client.player == null) {
            return;
        }

        Vec3 cameraPos = context.camera().getPosition();
        VertexConsumer vertexConsumer = context.consumers().getBuffer(RenderType.lines());

        // In Skyblocker, they fetch:
        // float[] color =
        // SkyblockerConfigManager.get().mining.crystalHollows.chestHighlightColor.getComponents(new
        // float[]{0, 0, 0, 0});
        // We supply a default orange ESP color here.
        float[] color = new float[] { 1.0f, 0.5f, 0.0f, 1.0f };

        for (BlockPos chest : activeChests) {
            // Extracted EXACT line from Skyblocker's extractRendering method:
            // collector.submitOutlinedBox(AABB.ofSize(chest.getCenter().subtract(0, 0.0625,
            // 0), 0.885, 0.885, 0.885), color, color[3], 3, false);

            // Ported mathematically to standard Fabric rendering logic:
            Vec3 center = chest.getCenter().subtract(0, 0.0625, 0);
            AABB aabb = AABB.ofSize(center, 0.885, 0.885, 0.885).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            LevelRenderer.renderLineBox(context.matrixStack(), vertexConsumer, aabb, color[0], color[1], color[2],
                    color[3]);
        }
    }
}
