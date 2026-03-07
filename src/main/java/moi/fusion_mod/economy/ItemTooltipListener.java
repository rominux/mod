package moi.fusion_mod.economy;

import moi.fusion_mod.config.FusionConfig;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * Adds SkyBlock price information (Bazaar, Lowest BIN, NPC) to item tooltips.
 *
 * Logic adapted from Skyblocker's tooltip adders:
 *   - BazaarPriceTooltip
 *   - LBinTooltip
 *   - NpcPriceTooltip
 *
 * Item ID extraction adapted from Skyblocker's ItemUtils.getItemId / getSkyblockApiId.
 * The SkyBlock item ID is stored in the item's custom data component under the "id" key
 * (within the ExtraAttributes NBT that Hypixel stores in CUSTOM_DATA).
 */
public class ItemTooltipListener implements ItemTooltipCallback {

    // Format a coin amount with count multiplier
    private static Component getCoinsMessage(double coins, int count) {
        return Component.literal(String.format("%,.1f coins", coins * count))
                .withStyle(ChatFormatting.DARK_AQUA);
    }

    @Override
    public void getTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> lines) {
        if (!FusionConfig.isItemTooltipsEnabled()) return;
        if (stack.isEmpty()) return;

        // ── Extract SkyBlock item ID from custom data ───────────────────
        String skyblockId = getSkyblockId(stack);
        if (skyblockId.isEmpty()) return;

        // For API lookups, use the base ID (same as skyblockId for most items)
        // Skyblocker has complex API ID transformation (pets, potions, enchanted books, etc.)
        // We use the base ID for now, which works for the vast majority of items
        String skyblockApiId = skyblockId;

        int count = stack.getCount();

        // ── 1. Bazaar prices ────────────────────────────────────────────
        // Logic from Skyblocker BazaarPriceTooltip: show buy/sell if item is in bazaar
        PriceDataManager.BazaarProduct product = PriceDataManager.getBazaarProduct(skyblockApiId);
        if (product != null) {
            lines.add(Component.literal(String.format("%-18s", "Bazaar Buy Price:"))
                    .withStyle(ChatFormatting.GOLD)
                    .append(product.buyPrice().isEmpty()
                            ? Component.literal("No data").withStyle(ChatFormatting.RED)
                            : getCoinsMessage(product.buyPrice().getAsDouble(), count)));

            lines.add(Component.literal(String.format("%-19s", "Bazaar Sell Price:"))
                    .withStyle(ChatFormatting.GOLD)
                    .append(product.sellPrice().isEmpty()
                            ? Component.literal("No data").withStyle(ChatFormatting.RED)
                            : getCoinsMessage(product.sellPrice().getAsDouble(), count)));
        }

        // ── 2. Lowest BIN price ─────────────────────────────────────────
        // Logic from Skyblocker LBinTooltip: show only if item is NOT in bazaar
        // (bazaar items have their own prices, LBIN would be redundant)
        if (product == null) {
            double lbinPrice = PriceDataManager.getLowestBinPrice(skyblockApiId);
            if (lbinPrice >= 0) {
                lines.add(Component.literal(String.format("%-19s", "Lowest BIN Price:"))
                        .withStyle(ChatFormatting.GOLD)
                        .append(getCoinsMessage(lbinPrice, count)));
            }
        }

        // ── 3. NPC sell price ───────────────────────────────────────────
        // Logic from Skyblocker NpcPriceTooltip: uses base skyblockId (not API ID)
        double npcPrice = PriceDataManager.getNpcPrice(skyblockId);
        if (npcPrice >= 0) {
            lines.add(Component.literal(String.format("%-21s", "NPC Sell Price:"))
                    .withStyle(ChatFormatting.YELLOW)
                    .append(getCoinsMessage(npcPrice, count)));
        }
    }

    /**
     * Extracts the SkyBlock item ID from an ItemStack's custom data.
     *
     * Hypixel stores item metadata in the CUSTOM_DATA component as a CompoundTag.
     * The SkyBlock item ID is at the "id" key (e.g. "DARK_CLAYMORE", "TITANIUM_DRILL_4").
     *
     * Adapted from Skyblocker's ItemUtils.getItemId():
     *   CompoundTag customData = getCustomData(stack);
     *   return customData.getStringOr("id", "");
     *
     * @param stack the item stack
     * @return the SkyBlock item ID, or empty string if not a SkyBlock item
     */
    private static String getSkyblockId(ItemStack stack) {
        try {
            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (customData.isEmpty()) return "";

            // CustomData.copyTag() returns a CompoundTag with the NBT data
            CompoundTag tag = customData.copyTag();
            return tag.getStringOr("id", "");
        } catch (Exception e) {
            return "";
        }
    }
}
