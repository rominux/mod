package moi.fusion_mod.ui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class QuickNavOverlay {

    // Visual identifier mirroring Skyblocker creative tab logic
    private static final ResourceLocation TAB_TEX = ResourceLocation
            .withDefaultNamespace("container/creative_inventory/tab_top_unselected_1");
    private static final ItemStack ICON = new ItemStack(Items.COMPASS);

    // Position cache boundaries
    private static int currX = 0;
    private static int currY = 0;
    private static final int WIDTH = 26;
    private static final int HEIGHT = 32;

    public static void renderButton(GuiGraphics context, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen))
            return;

        try {
            // Reflect into AbstractContainerScreen for bounds mapping compatible up to
            // 1.21.10
            Field leftPosField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            Field topPosField = AbstractContainerScreen.class.getDeclaredField("topPos");
            Field imageWidthField = AbstractContainerScreen.class.getDeclaredField("imageWidth");

            leftPosField.setAccessible(true);
            topPosField.setAccessible(true);
            imageWidthField.setAccessible(true);

            int x = (int) leftPosField.get(screen);
            int y = (int) topPosField.get(screen);
            int w = (int) imageWidthField.get(screen);

            // Replicate skyblocker quick nav index offset math (e.g index % 7 * 25)
            currX = x + (w / 2) - (176 / 2); // Default tab 0 rendering space
            currY = y - 28; // Creative Top Tab y-offset

            context.blitSprite(RenderPipelines.GUI_TEXTURED, TAB_TEX, currX, currY, WIDTH, HEIGHT);
            context.renderItem(ICON, currX + 5, currY + 8);
        } catch (Exception ignored) {
            // Failsafe exit logic
        }
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            // Trigger bounds click logic checking if point is intersected
            if (mouseX >= currX && mouseX <= currX + WIDTH && mouseY >= currY && mouseY <= currY + HEIGHT) {
                if (mc.player != null) {
                    mc.player.connection.sendCommand("warp forge"); // Execute quick warp command
                }
                return true;
            }
        }
        return false;
    }
}
