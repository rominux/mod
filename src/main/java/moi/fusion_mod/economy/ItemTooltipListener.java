package moi.fusion_mod.economy;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ItemTooltipListener implements ItemTooltipCallback {

    // Mocking the Data Structures from
    // de.hysky.skyblocker.skyblock.item.tooltip.info.TooltipInfoType
    public static class BazaarProduct {
        public double buyPrice() {
            return 100.0;
        } // Replace with API later

        public double sellPrice() {
            return 90.0;
        } // Replace with API later
    }

    // Function from de.hysky.skyblocker.skyblock.item.tooltip.ItemTooltip
    private static Component getCoinsMessage(double coins, int count) {
        return Component.literal(String.format("%,.1f coins", coins * count));
    }

    @Override
    public void getTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> lines) {
        // String skyblockApiId = stack.getSkyblockApiId(); (Extracted from Skyblocker)
        String skyblockApiId = "EXAMPLE_ITEM"; // Placeholder
        int count = stack.getCount();

        // 1. Array of logic from
        // de.hysky.skyblocker.skyblock.item.tooltip.adders.BazaarPriceTooltip
        BazaarProduct product = new BazaarProduct();
        lines.add(Component.literal(String.format("%-18s", "Bazaar Buy Price:"))
                .withStyle(ChatFormatting.GOLD)
                .append(getCoinsMessage(product.buyPrice(), count)));

        lines.add(Component.literal(String.format("%-19s", "Bazaar Sell Price:"))
                .withStyle(ChatFormatting.GOLD)
                .append(getCoinsMessage(product.sellPrice(), count)));

        // 2. Logic from de.hysky.skyblocker.skyblock.item.tooltip.adders.LBinTooltip
        double lbinPrice = 150.0; // TooltipInfoType.LOWEST_BINS.getData().getDouble(skyblockApiId)
        lines.add(Component.literal(String.format("%-19s", "Lowest BIN Price:"))
                .withStyle(ChatFormatting.GOLD)
                .append(getCoinsMessage(lbinPrice, count)));

        // 3. Logic from
        // de.hysky.skyblocker.skyblock.item.tooltip.adders.NpcPriceTooltip
        double npcPrice = 10.0; // TooltipInfoType.NPC.getData().getOrDefault(internalID, -1)
        if (npcPrice >= 0) {
            lines.add(Component.literal(String.format("%-21s", "NPC Sell Price:"))
                    .withStyle(ChatFormatting.YELLOW)
                    .append(getCoinsMessage(npcPrice, count)));
        }
    }
}
