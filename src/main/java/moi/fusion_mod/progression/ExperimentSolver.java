package moi.fusion_mod.progression;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

public class ExperimentSolver {

    // Extracted EXACTLY from de.hysky.skyblocker.skyblock.experiment.ChronomatronSolver
    public static final Object2ObjectMap<Item, Item> TERRACOTTA_TO_GLASS = Object2ObjectMap.ofEntries(
            Object2ObjectMap.entry(Items.RED_TERRACOTTA, Items.RED_STAINED_GLASS),
            Object2ObjectMap.entry(Items.ORANGE_TERRACOTTA, Items.ORANGE_STAINED_GLASS),
            Object2ObjectMap.entry(Items.YELLOW_TERRACOTTA, Items.YELLOW_STAINED_GLASS),
            Object2ObjectMap.entry(Items.LIME_TERRACOTTA, Items.LIME_STAINED_GLASS),
            Object2ObjectMap.entry(Items.GREEN_TERRACOTTA, Items.GREEN_STAINED_GLASS),
            Object2ObjectMap.entry(Items.CYAN_TERRACOTTA, Items.CYAN_STAINED_GLASS),
            Object2ObjectMap.entry(Items.LIGHT_BLUE_TERRACOTTA, Items.LIGHT_BLUE_STAINED_GLASS),
            Object2ObjectMap.entry(Items.BLUE_TERRACOTTA, Items.BLUE_STAINED_GLASS),
            Object2ObjectMap.entry(Items.PURPLE_TERRACOTTA, Items.PURPLE_STAINED_GLASS),
            Object2ObjectMap.entry(Items.PINK_TERRACOTTA, Items.PINK_STAINED_GLASS)
    );

    // Adapted generic state representation from de.hysky.skyblocker.skyblock.experiment.ExperimentSolver
    public enum State {
        REMEMBER, WAIT, SHOW, END
    }

    private static State state = State.REMEMBER;
    private static final List<Item> chronomatronSlots = new ArrayList<>();
    private static int chronomatronCurrentOrdinal = 0;

    public static void onScreenRender(AbstractContainerScreen<?> screen, GuiGraphics graphics) {
        String title = screen.getTitle().getString();
        
        if (title.contains("Chronomatron")) {
            // Rendering logic extracted and adapted from ChronomatronSolver
            if (state == State.SHOW && chronomatronSlots.size() > chronomatronCurrentOrdinal) {
                Item nextItem = chronomatronSlots.get(chronomatronCurrentOrdinal);
                
                for (Slot slot : screen.getMenu().slots) {
                    ItemStack stack = slot.getItem();
                    if (stack.is(nextItem) || TERRACOTTA_TO_GLASS.get(stack.getItem()) == nextItem) {
                        // In skyblocker they use custom ColorHighlight. Here we draw directly.
                        int guiLeft = (screen.width - screen.getImageWidth()) / 2;
                        int guiTop = (screen.height - screen.getImageHeight()) / 2;
                        int x = guiLeft + slot.x;
                        int y = guiTop + slot.y;
                        
                        // Render green highlight over the correct slot
                        graphics.fill(x, y, x + 16, y + 16, 0x8000FF00);
                    }
                }
            }
        } else if (title.contains("Ultrasequencer")) {
            // Outline for Ultrasequencer logic
        }
    }
}
