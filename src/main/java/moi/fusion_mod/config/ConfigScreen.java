package moi.fusion_mod.config;

import moi.fusion_mod.macros.FarmSetupScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Configuration screen for Fusion Mod.
 * Opens when the user presses the "G" keybind.
 *
 * Follows the exact same pattern as PasunhackGui: all widget creation in init(),
 * using Button.builder().dimensions().build() and addRenderableWidget().
 */
public class ConfigScreen extends Screen {
    private final Screen parent;

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int SIDEBAR_WIDTH = 100;
    private static final int SIDEBAR_PADDING = 4;
    private static final int OPTION_PADDING = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int CATEGORY_BUTTON_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 30;

    // ── Category definitions ────────────────────────────────────────────────
    private final Map<String, List<ToggleOption>> categories = new LinkedHashMap<>();
    private String selectedCategory;

    /**
     * A single toggle option.
     */
    private static class ToggleOption {
        final String label;
        final String description;
        final BooleanSupplier getter;
        final Consumer<Boolean> setter;

        ToggleOption(String label, String description, BooleanSupplier getter, Consumer<Boolean> setter) {
            this.label = label;
            this.description = description;
            this.getter = getter;
            this.setter = setter;
        }
    }

    // ── Auto-Miner priority list UI state ───────────────────────────────────
    private EditBox addBlockField = null;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Fusion Mod Configuration"));
        this.parent = parent;
        buildCategories();
        selectedCategory = categories.keySet().iterator().next();
    }

    /**
     * Builds all categories and their toggle options.
     * Each toggle binds to a FusionConfig getter/setter.
     */
    private void buildCategories() {
        // ── General Settings ──
        List<ToggleOption> general = new ArrayList<>();
        general.add(new ToggleOption("Zone Info HUD", "Dynamic HUD showing zone-relevant info (commissions, pests, etc.)",
                FusionConfig::isZoneInfoHudEnabled, FusionConfig::setZoneInfoHudEnabled));
        general.add(new ToggleOption("Drill Fuel Bar", "Show drill fuel as a bar overlay",
                FusionConfig::isDrillFuelBarEnabled, FusionConfig::setDrillFuelBarEnabled));
        general.add(new ToggleOption("Item Pickup Log", "Show recently picked up items",
                FusionConfig::isItemPickupLogEnabled, FusionConfig::setItemPickupLogEnabled));
        general.add(new ToggleOption("Item Price Tooltips", "Show NPC/Bazaar prices in item tooltips",
                FusionConfig::isItemTooltipsEnabled, FusionConfig::setItemTooltipsEnabled));
        general.add(new ToggleOption("Chat Filter", "Filter spam and unwanted chat messages",
                FusionConfig::isChatFilterEnabled, FusionConfig::setChatFilterEnabled));
        general.add(new ToggleOption("Party Commands", "Enable custom party chat commands",
                FusionConfig::isPartyCommandsEnabled, FusionConfig::setPartyCommandsEnabled));
        general.add(new ToggleOption("Experiment Solver", "Solve Experimentation Table puzzles",
                FusionConfig::isExperimentSolverEnabled, FusionConfig::setExperimentSolverEnabled));
        categories.put("General Settings", general);

        // ── Dwarven Mines ──
        List<ToggleOption> dwarven = new ArrayList<>();
        dwarven.add(new ToggleOption("Commission Waypoints", "Auto-create 3D waypoints for active commissions",
                FusionConfig::isCommissionWaypointsEnabled, FusionConfig::setCommissionWaypointsEnabled));
        dwarven.add(new ToggleOption("3D Waypoints", "Enable 3D waypoint rendering in the world",
                FusionConfig::isWaypointsEnabled, FusionConfig::setWaypointsEnabled));
        dwarven.add(new ToggleOption("Pickobulus Preview", "Show 5x5x5 preview box when Pickobulus is ready",
                FusionConfig::isPickobulusPreviewEnabled, FusionConfig::setPickobulusPreviewEnabled));
        dwarven.add(new ToggleOption("HotM Overlay", "Show Sky Mall perk info",
                FusionConfig::isHotmOverlayEnabled, FusionConfig::setHotmOverlayEnabled));
        categories.put("Dwarven Mines", dwarven);

        // ── Crystal Hollows ──
        List<ToggleOption> hollows = new ArrayList<>();
        hollows.add(new ToggleOption("Chest ESP", "Highlight treasure chests in Crystal Hollows",
                FusionConfig::isChestEspEnabled, FusionConfig::setChestEspEnabled));
        hollows.add(new ToggleOption("Crystal Hollows Map", "Show 2D minimap of Crystal Hollows zones",
                FusionConfig::isCrystalMapEnabled, FusionConfig::setCrystalMapEnabled));
        categories.put("Crystal Hollows", hollows);

        // ── Garden ──
        List<ToggleOption> garden = new ArrayList<>();
        garden.add(new ToggleOption("Garden Tracker", "Master toggle for garden zone HUD features",
                FusionConfig::isGardenTrackerEnabled, FusionConfig::setGardenTrackerEnabled));
        garden.add(new ToggleOption("Show Visitors", "Show visitor count and next visitor timer",
                FusionConfig::isGardenShowVisitors, FusionConfig::setGardenShowVisitors));
        garden.add(new ToggleOption("Show Pests", "Show pest count and infested plots",
                FusionConfig::isGardenShowPests, FusionConfig::setGardenShowPests));
        garden.add(new ToggleOption("Show Spray", "Show current spray info on your plot",
                FusionConfig::isGardenShowSpray, FusionConfig::setGardenShowSpray));
        garden.add(new ToggleOption("Show Greenhouse", "Show greenhouse growth cycle timer",
                FusionConfig::isGardenShowGreenhouse, FusionConfig::setGardenShowGreenhouse));
        garden.add(new ToggleOption("Show Jacob Contest", "Show Jacob's Contest timer and status",
                FusionConfig::isGardenShowJacobContest, FusionConfig::setGardenShowJacobContest));
        categories.put("Garden", garden);

        // ── Hub ──
        List<ToggleOption> hub = new ArrayList<>();
        hub.add(new ToggleOption("Show Mayor", "Display current Mayor and Minister in Hub HUD",
                FusionConfig::isHubShowMayor, FusionConfig::setHubShowMayor));
        hub.add(new ToggleOption("Show Bank", "Display bank balance and interest timer",
                FusionConfig::isHubShowBank, FusionConfig::setHubShowBank));
        hub.add(new ToggleOption("Show Slayers", "Display active slayer quest progress",
                FusionConfig::isHubShowSlayers, FusionConfig::setHubShowSlayers));
        hub.add(new ToggleOption("Show Cookie Buff", "Display Booster Cookie buff status",
                FusionConfig::isHubShowCookie, FusionConfig::setHubShowCookie));
        hub.add(new ToggleOption("Show God Potion", "Display God Potion active timer",
                FusionConfig::isHubShowGodPot, FusionConfig::setHubShowGodPot));
        categories.put("Hub", hub);

        // ── MiningHelper ──
        List<ToggleOption> miningHelper = new ArrayList<>();
        miningHelper.add(new ToggleOption("Auto Miner", "Toggle auto-mining nearby configured blocks",
                FusionConfig::isAutoMinerEnabled, FusionConfig::setAutoMinerEnabled));
        categories.put("MiningHelper", miningHelper);

        // ── FarmHelper ──
        List<ToggleOption> farmHelper = new ArrayList<>();
        farmHelper.add(new ToggleOption("Farm Helper", "Toggle automatic farming macro",
                FusionConfig::isFarmHelperEnabled, FusionConfig::setFarmHelperEnabled));
        farmHelper.add(new ToggleOption("Allow Pest Killing", "Auto-hunt pests when detected during farming",
                FusionConfig::isFarmHelperAllowPestKilling, FusionConfig::setFarmHelperAllowPestKilling));
        farmHelper.add(new ToggleOption("Sethome On Start", "Run /sethome when enabling FarmHelper",
                FusionConfig::isFarmHelperSethomeOnStart, FusionConfig::setFarmHelperSethomeOnStart));
        farmHelper.add(new ToggleOption("Fishing Rod Swap", "Cast fishing rod for pet swap when pest cooldown is ready",
                FusionConfig::isFarmHelperUseFishingRodSwap, FusionConfig::setFarmHelperUseFishingRodSwap));
        categories.put("FarmHelper", farmHelper);

        // ── Solvers ──
        List<ToggleOption> solvers = new ArrayList<>();
        solvers.add(new ToggleOption("Fetchur Solver", "Decode Fetchur's daily riddle and show the answer",
                FusionConfig::isFetchurSolverEnabled, FusionConfig::setFetchurSolverEnabled));
        solvers.add(new ToggleOption("Puzzler Solver", "Solve Puzzler's arrow sequence and mark the block",
                FusionConfig::isPuzzlerSolverEnabled, FusionConfig::setPuzzlerSolverEnabled));
        categories.put("Solvers", solvers);
    }

    // ── Screen lifecycle ────────────────────────────────────────────────────

    @Override
    protected void init() {
        // Clear all existing widgets (important when re-entering or switching categories)
        this.clearWidgets();

        int screenW = this.width;
        int screenH = this.height;

        // ── Category sidebar buttons ────────────────────────────────────
        int catY = HEADER_HEIGHT + SIDEBAR_PADDING;
        for (String categoryName : categories.keySet()) {
            final String cat = categoryName;
            int btnWidth = SIDEBAR_WIDTH - 2 * SIDEBAR_PADDING;

            this.addRenderableWidget(Button.builder(
                    Component.literal(categoryName),
                    button -> {
                        selectedCategory = cat;
                        this.init(); // Rebuild all widgets for new category
                    }
            ).bounds(SIDEBAR_PADDING, catY, btnWidth, CATEGORY_BUTTON_HEIGHT).build());

            catY += CATEGORY_BUTTON_HEIGHT + 2;
        }

        // ── Option toggle buttons for selected category ─────────────────
        List<ToggleOption> options = categories.get(selectedCategory);
        if (options != null) {
            int optionX = SIDEBAR_WIDTH + OPTION_PADDING;
            int optionW = screenW - SIDEBAR_WIDTH - 2 * OPTION_PADDING;
            int toggleW = 40;
            int optionY = HEADER_HEIGHT + OPTION_PADDING;

            for (ToggleOption opt : options) {
                boolean currentValue = opt.getter.getAsBoolean();
                final ToggleOption option = opt;

                this.addRenderableWidget(Button.builder(
                        Component.literal(currentValue ? "\u00A7aON" : "\u00A7cOFF"),
                        button -> {
                            boolean newValue = !option.getter.getAsBoolean();
                            option.setter.accept(newValue);
                            button.setMessage(Component.literal(newValue ? "\u00A7aON" : "\u00A7cOFF"));
                        }
                ).bounds(optionX + optionW - toggleW, optionY, toggleW, BUTTON_HEIGHT).build());

                optionY += BUTTON_HEIGHT + OPTION_PADDING + 12; // extra space for description
            }

            // ── Auto-Miner Priority List (MiningHelper category only) ──────────
            addBlockField = null;
            if ("MiningHelper".equals(selectedCategory)) {
                optionY += 8; // extra spacing before the priority list section

                List<String> blockList = FusionConfig.getAutoMinerBlocks();
                int rowHeight = 24;
                int smallBtnW = 20;
                int removeBtnW = 20;
                int iconSize = 16;
                int btnSpacing = 2;

                // Render a row for each block in the priority list
                for (int i = 0; i < blockList.size(); i++) {
                    final int index = i;
                    int rowY = optionY + i * rowHeight;

                    // "Up" button (disabled for first item)
                    if (i > 0) {
                        this.addRenderableWidget(Button.builder(
                                Component.literal("\u2191"),
                                button -> {
                                    List<String> list = new ArrayList<>(FusionConfig.getAutoMinerBlocks());
                                    if (index > 0 && index < list.size()) {
                                        String item = list.remove(index);
                                        list.add(index - 1, item);
                                        FusionConfig.setAutoMinerBlocks(list);
                                        this.init();
                                    }
                                }
                        ).bounds(optionX + optionW - (smallBtnW * 3 + btnSpacing * 2 + removeBtnW), rowY, smallBtnW, rowHeight - 4).build());
                    }

                    // "Down" button (disabled for last item)
                    if (i < blockList.size() - 1) {
                        this.addRenderableWidget(Button.builder(
                                Component.literal("\u2193"),
                                button -> {
                                    List<String> list = new ArrayList<>(FusionConfig.getAutoMinerBlocks());
                                    if (index >= 0 && index < list.size() - 1) {
                                        String item = list.remove(index);
                                        list.add(index + 1, item);
                                        FusionConfig.setAutoMinerBlocks(list);
                                        this.init();
                                    }
                                }
                        ).bounds(optionX + optionW - (smallBtnW * 2 + btnSpacing + removeBtnW), rowY, smallBtnW, rowHeight - 4).build());
                    }

                    // "-" remove button (red)
                    this.addRenderableWidget(Button.builder(
                            Component.literal("\u00A7c-"),
                            button -> {
                                List<String> list = new ArrayList<>(FusionConfig.getAutoMinerBlocks());
                                if (index >= 0 && index < list.size()) {
                                    list.remove(index);
                                    FusionConfig.setAutoMinerBlocks(list);
                                    this.init();
                                }
                            }
                    ).bounds(optionX + optionW - removeBtnW, rowY, removeBtnW, rowHeight - 4).build());
                }

                // ── Add new block row at the bottom ──────────────────────
                int addRowY = optionY + blockList.size() * rowHeight + 6;
                int addBtnW = 24;
                int fieldW = optionW - addBtnW - 8;

                addBlockField = new EditBox(this.font, optionX, addRowY, fieldW, BUTTON_HEIGHT,
                        Component.literal("Block ID"));
                addBlockField.setMaxLength(128);
                addBlockField.setHint(Component.literal("minecraft:block_id"));
                this.addRenderableWidget(addBlockField);

                this.addRenderableWidget(Button.builder(
                        Component.literal("\u00A7a+"),
                        button -> {
                            if (addBlockField != null) {
                                String value = addBlockField.getValue().trim();
                                if (!value.isEmpty()) {
                                    List<String> list = new ArrayList<>(FusionConfig.getAutoMinerBlocks());
                                    if (!list.contains(value)) {
                                        list.add(value);
                                        FusionConfig.setAutoMinerBlocks(list);
                                    }
                                    this.init();
                                }
                            }
                        }
                ).bounds(optionX + fieldW + 4, addRowY, addBtnW, BUTTON_HEIGHT).build());
            }

            // ── Farm Setup button (FarmHelper category only) ────────────
            if ("FarmHelper".equals(selectedCategory)) {
                int farmSetupY = optionY + 8;
                int farmSetupW = 160;
                this.addRenderableWidget(Button.builder(
                        Component.literal("\u00A7eFarm Setup"),
                        button -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new FarmSetupScreen(this));
                            }
                        }
                ).bounds(optionX, farmSetupY, farmSetupW, BUTTON_HEIGHT).build());
            }
        }

        // ── Done button at bottom ───────────────────────────────────────
        int doneW = 120;
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> onClose()
        ).bounds((screenW - doneW) / 2, screenH - 28, doneW, 20).build());
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // In 1.21.10, super.render() already calls renderBackground() internally.
        // Calling renderBackground() here too causes "Can only blur once per frame" crash.

        int screenW = this.width;

        // ── Header ──
        graphics.drawCenteredString(this.font, "\u00A7b\u00A7lFusion Mod Settings", screenW / 2, 10, 0xFFFFFFFF);

        // ── Sidebar background ──
        graphics.fill(0, HEADER_HEIGHT, SIDEBAR_WIDTH, this.height - 32, 0x80000000);

        // ── Divider line ──
        graphics.fill(SIDEBAR_WIDTH, HEADER_HEIGHT, SIDEBAR_WIDTH + 1, this.height - 32, 0xFF444444);

        // ── Category header in right panel ──
        if (selectedCategory != null) {
            graphics.drawString(this.font, "\u00A7e\u00A7l" + selectedCategory,
                    SIDEBAR_WIDTH + OPTION_PADDING, HEADER_HEIGHT - 12, 0xFFFFFFFF);

            // ── Draw option labels and descriptions ──
            List<ToggleOption> options = categories.get(selectedCategory);
            if (options != null) {
                int optionX = SIDEBAR_WIDTH + OPTION_PADDING;
                int optionW = screenW - SIDEBAR_WIDTH - 2 * OPTION_PADDING;
                int optionY = HEADER_HEIGHT + OPTION_PADDING;
                for (ToggleOption opt : options) {
                    // Option label (left of toggle button)
                    graphics.drawString(this.font, "\u00A7f" + opt.label,
                            SIDEBAR_WIDTH + OPTION_PADDING, optionY + 5, 0xFFFFFFFF);

                    // Description below the label
                    graphics.drawString(this.font, "\u00A77" + opt.description,
                            SIDEBAR_WIDTH + OPTION_PADDING, optionY + BUTTON_HEIGHT + 2, 0xFFAAAAAA);

                    optionY += BUTTON_HEIGHT + OPTION_PADDING + 12;
                }

                // ── Auto-Miner Priority List rendering (MiningHelper category) ──
                if ("MiningHelper".equals(selectedCategory)) {
                    optionY += 8;

                    // Section header
                    graphics.drawString(this.font, "\u00A7e\u00A7lAuto-Miner Block Priority:",
                            optionX, optionY - 14, 0xFFFFFFFF);
                    graphics.drawString(this.font, "\u00A77Higher = mined first. Drag with arrows to reorder.",
                            optionX, optionY - 4, 0xFFAAAAAA);

                    List<String> blockList = FusionConfig.getAutoMinerBlocks();
                    int rowHeight = 24;
                    int iconSize = 16;

                    for (int i = 0; i < blockList.size(); i++) {
                        String blockId = blockList.get(i);
                        int rowY = optionY + i * rowHeight;

                        // Priority number
                        graphics.drawString(this.font, "\u00A76" + (i + 1) + ".",
                                optionX, rowY + 4, 0xFFFFAA00);

                        // Block icon (render item)
                        ItemStack iconStack = getItemStackForBlockId(blockId);
                        if (!iconStack.isEmpty()) {
                            graphics.renderItem(iconStack, optionX + 16, rowY + 1);
                        }

                        // Block ID text
                        graphics.drawString(this.font, "\u00A7f" + blockId,
                                optionX + 36, rowY + 4, 0xFFFFFFFF);
                    }

                    // "Add block" label below the list
                    int addRowY = optionY + blockList.size() * rowHeight + 6;
                    graphics.drawString(this.font, "\u00A77Add block:",
                            optionX, addRowY - 12, 0xFFAAAAAA);
                }

                // ── Farm Setup section label (FarmHelper category) ──
                if ("FarmHelper".equals(selectedCategory)) {
                    int farmSetupLabelY = optionY + 2;
                    graphics.drawString(this.font, "\u00A7e\u00A7lFarm Waypoint Setup:",
                            optionX, farmSetupLabelY, 0xFFFFFFFF);
                }
            }
        }

        // ── Selected category highlight in sidebar ──
        int catY = HEADER_HEIGHT + SIDEBAR_PADDING;
        for (String categoryName : categories.keySet()) {
            if (categoryName.equals(selectedCategory)) {
                graphics.fill(0, catY - 1, SIDEBAR_WIDTH, catY + CATEGORY_BUTTON_HEIGHT + 1, 0x30FFFFFF);
            }
            catY += CATEGORY_BUTTON_HEIGHT + 2;
        }

        // Render vanilla widgets (buttons) on top
        super.render(graphics, mouseX, mouseY, delta);
    }

    /**
     * Parse a block ID string to an ItemStack for icon rendering.
     */
    private static ItemStack getItemStackForBlockId(String blockId) {
        try {
            ResourceLocation resLoc = ResourceLocation.parse(blockId);
            if (BuiltInRegistries.BLOCK.containsKey(resLoc)) {
                return new ItemStack(BuiltInRegistries.BLOCK.getValue(resLoc));
            }
        } catch (Throwable t) {
            // Invalid ID — return empty
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void onClose() {
        FusionConfig.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
