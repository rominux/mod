package moi.fusion_mod.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
 * Layout inspired by:
 *   - doc/SkyHanni/.../config/ConfigGuiManager.kt  (MoulConfigEditor with left panel categories + right panel options)
 *   - doc/Skyblocker/.../config/HudConfigScreen.java  (vanilla-based Screen with GridLayout, Button)
 *   - doc/Skyblocker/.../chat/ChatRuleConfigScreen.java  (vanilla-based with CycleButton for toggles)
 *   - doc/jarvis/.../impl/JarvisConfigSearch.java  (vanilla Screen with scrollable option list)
 *
 * This is a fully vanilla-based config screen (no YACL, no Cloth Config, no MoulConfig).
 * Uses a left sidebar for categories and a right panel for toggle options.
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
    private int scrollOffset = 0;

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
        // ── Crystal Hollows ──
        List<ToggleOption> hollows = new ArrayList<>();
        hollows.add(new ToggleOption("Chest ESP", "Highlight treasure chests in Crystal Hollows",
                FusionConfig::isChestEspEnabled, FusionConfig::setChestEspEnabled));
        hollows.add(new ToggleOption("Crystal Hollows Map", "Show 2D minimap of Crystal Hollows zones",
                FusionConfig::isCrystalMapEnabled, FusionConfig::setCrystalMapEnabled));
        categories.put("Crystal Hollows", hollows);

        // ── Waypoints ──
        List<ToggleOption> waypoints = new ArrayList<>();
        waypoints.add(new ToggleOption("3D Waypoints", "Enable 3D waypoint rendering in the world",
                FusionConfig::isWaypointsEnabled, FusionConfig::setWaypointsEnabled));
        waypoints.add(new ToggleOption("Commission Waypoints", "Auto-create waypoints for active commissions",
                FusionConfig::isCommissionWaypointsEnabled, FusionConfig::setCommissionWaypointsEnabled));
        categories.put("Waypoints", waypoints);

        // ── HUD Widgets ──
        List<ToggleOption> hud = new ArrayList<>();
        hud.add(new ToggleOption("Commission Tracker", "Show active commissions on HUD",
                FusionConfig::isCommissionsEnabled, v -> FusionConfig.setCommissionsHudEnabled(v)));
        hud.add(new ToggleOption("Drill Fuel Bar", "Show drill fuel as a bar overlay",
                FusionConfig::isDrillFuelBarEnabled, FusionConfig::setDrillFuelBarEnabled));
        hud.add(new ToggleOption("Pickobulus Timer", "Show Pickobulus cooldown and status",
                FusionConfig::isPickobulusTimerEnabled, FusionConfig::setPickobulusTimerEnabled));
        hud.add(new ToggleOption("Item Pickup Log", "Show recently picked up items",
                FusionConfig::isItemPickupLogEnabled, FusionConfig::setItemPickupLogEnabled));
        hud.add(new ToggleOption("HotM Overlay", "Show Sky Mall perk info",
                FusionConfig::isHotmOverlayEnabled, FusionConfig::setHotmOverlayEnabled));
        categories.put("HUD Widgets", hud);

        // ── Garden ──
        List<ToggleOption> garden = new ArrayList<>();
        garden.add(new ToggleOption("Garden Tracker", "Track garden visitors and stats",
                FusionConfig::isGardenTrackerEnabled, FusionConfig::setGardenTrackerEnabled));
        categories.put("Garden", garden);

        // ── Social ──
        List<ToggleOption> social = new ArrayList<>();
        social.add(new ToggleOption("Chat Filter", "Filter spam and unwanted chat messages",
                FusionConfig::isChatFilterEnabled, FusionConfig::setChatFilterEnabled));
        social.add(new ToggleOption("Party Commands", "Enable custom party chat commands",
                FusionConfig::isPartyCommandsEnabled, FusionConfig::setPartyCommandsEnabled));
        categories.put("Social", social);

        // ── Economy ──
        List<ToggleOption> economy = new ArrayList<>();
        economy.add(new ToggleOption("Item Price Tooltips", "Show NPC/Bazaar prices in item tooltips",
                FusionConfig::isItemTooltipsEnabled, FusionConfig::setItemTooltipsEnabled));
        categories.put("Economy", economy);

        // ── Progression ──
        List<ToggleOption> progression = new ArrayList<>();
        progression.add(new ToggleOption("Experiment Solver", "Solve Experimentation Table puzzles",
                FusionConfig::isExperimentSolverEnabled, FusionConfig::setExperimentSolverEnabled));
        categories.put("Progression", progression);
    }

    // ── Screen lifecycle ────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        // rebuildWidgets() is called by super.init() already
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        scrollOffset = 0;

        int screenW = this.width;
        int screenH = this.height;

        // ── Category sidebar buttons ────────────────────────────────────
        int catY = HEADER_HEIGHT + SIDEBAR_PADDING;
        for (String categoryName : categories.keySet()) {
            final String cat = categoryName;
            int btnWidth = SIDEBAR_WIDTH - 2 * SIDEBAR_PADDING;
            Button catButton = Button.builder(
                    Component.literal(categoryName),
                    button -> {
                        selectedCategory = cat;
                        scrollOffset = 0;
                        rebuildWidgets();
                    }
            ).bounds(SIDEBAR_PADDING, catY, btnWidth, CATEGORY_BUTTON_HEIGHT).build();

            addRenderableWidget(catButton);
            catY += CATEGORY_BUTTON_HEIGHT + 2;
        }

        // ── Option toggle buttons for selected category ─────────────────
        List<ToggleOption> options = categories.get(selectedCategory);
        if (options == null) return;

        int optionX = SIDEBAR_WIDTH + OPTION_PADDING;
        int optionW = screenW - SIDEBAR_WIDTH - 2 * OPTION_PADDING;
        int toggleW = 40;
        int optionY = HEADER_HEIGHT + OPTION_PADDING;

        for (ToggleOption opt : options) {
            boolean currentValue = opt.getter.getAsBoolean();
            final ToggleOption option = opt;

            Button toggleButton = Button.builder(
                    Component.literal(currentValue ? "\u00A7aON" : "\u00A7cOFF"),
                    button -> {
                        boolean newValue = !option.getter.getAsBoolean();
                        option.setter.accept(newValue);
                        button.setMessage(Component.literal(newValue ? "\u00A7aON" : "\u00A7cOFF"));
                    }
            ).bounds(optionX + optionW - toggleW, optionY, toggleW, BUTTON_HEIGHT).build();

            addRenderableWidget(toggleButton);
            optionY += BUTTON_HEIGHT + OPTION_PADDING + 12; // extra space for description
        }

        // ── Done button at bottom ───────────────────────────────────────
        int doneW = 120;
        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> onClose()
        ).bounds((screenW - doneW) / 2, screenH - 28, doneW, 20).build());
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // In 1.21.10, super.render() already calls renderBackground() internally,
        // which applies a blur effect. Calling renderBackground() here too causes
        // "Can only blur once per frame" crash. So we skip the explicit call.

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
