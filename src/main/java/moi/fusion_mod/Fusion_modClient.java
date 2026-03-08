package moi.fusion_mod;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.economy.ItemTooltipListener;
import moi.fusion_mod.economy.PriceDataManager;
import moi.fusion_mod.hollows.ChestEspRenderer;
import moi.fusion_mod.hollows.CrystalHollowsMapHud;
import moi.fusion_mod.macros.AutoMiner;
import moi.fusion_mod.macros.FarmConfig;
import moi.fusion_mod.macros.FarmHelper;
import moi.fusion_mod.macros.FarmSetupScreen;
import moi.fusion_mod.ui.hud.ItemPickupLogHud;
import moi.fusion_mod.ui.hud.ZoneInfoHud;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import moi.fusion_mod.waypoints.WaypointRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Fusion_modClient implements ClientModInitializer {
    public static KeyMapping configKeybind;
    public static KeyMapping autoMinerKeybind;
    public static KeyMapping farmHelperKeybind;

    // Farm setup recording keybindings (F8-F11)
    public static KeyMapping setupMarkStart;
    public static KeyMapping setupMarkTurn;
    public static KeyMapping setupMarkEnd;
    public static KeyMapping setupUndo;

    // Tick counter for periodic tasks (Crystal Hollows map discovery runs every 40 ticks)
    private int tickCounter = 0;

    // Greenhouse lore pattern: "Next Stage: 1h 40m 20s" (SkyHanni GrowthCycle.kt)
    private static final Pattern GREENHOUSE_NEXT_STAGE_PATTERN =
            Pattern.compile("Next Stage: (?<time>(?:\\d\\d?[hms] ?)+)");

    @Override
    public void onInitializeClient() {
        FusionConfig.init();
        FarmConfig.load();

        configKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.config",
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.register(ResourceLocation.parse("fusion_mod:general"))
        ));

        // Register the macros category once, then reuse it for both keybinds
        KeyMapping.Category macrosCategory = KeyMapping.Category.register(ResourceLocation.parse("fusion_mod:macros"));

        autoMinerKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.auto_miner",
            GLFW.GLFW_KEY_KP_1,
            macrosCategory
        ));

        farmHelperKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.farm_helper",
            GLFW.GLFW_KEY_KP_2,
            macrosCategory
        ));

        // Farm setup recording keybindings (F8-F11) — only active during recording mode
        KeyMapping.Category setupCategory = KeyMapping.Category.register(ResourceLocation.parse("fusion_mod:farm_setup"));

        setupMarkStart = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.setup_start",
            GLFW.GLFW_KEY_F8,
            setupCategory
        ));

        setupMarkTurn = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.setup_turn",
            GLFW.GLFW_KEY_F9,
            setupCategory
        ));

        setupMarkEnd = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.setup_end",
            GLFW.GLFW_KEY_F10,
            setupCategory
        ));

        setupUndo = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.setup_undo",
            GLFW.GLFW_KEY_F11,
            setupCategory
        ));

        JarvisGuiManager.initializeHuds();

        // Register chest ESP block update listener
        ChestEspRenderer.init();

        // Start background price data fetching (Bazaar, LBIN, NPC prices)
        PriceDataManager.init();

        // Start periodic mayor/minister fetch from Hypixel API
        ZoneInfoHud.scheduleMayorFetch();

        registerEvents();
    }

    private void registerEvents() {
        // ── World/server change — reset item pickup log and skymall ─────
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ItemPickupLogHud pickupLog = ItemPickupLogHud.getInstance();
            if (pickupLog != null) {
                pickupLog.resetOnWorldChange();
            }
            ZoneInfoHud.resetSkymall();
            ChestEspRenderer.clear();
        });

        // ── Chat message listener — Skymall perk detection ─────────────
        // Uses ALLOW_GAME so we receive all game messages (not player chat).
        // Signature: (Component message, boolean overlay) -> boolean
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true; // Ignore action bar messages

            // Pass the formatted message string to ZoneInfoHud for Skymall detection
            String formatted = message.getString();
            ZoneInfoHud.onSkymallChatFormatted(formatted);

            return true; // Never cancel the message
        });

        // ── Screen open — Crop Diagnostics greenhouse timer parsing ────
        // When the player opens a chest inventory titled "Crop Diagnostics",
        // read slot 20 lore for "Next Stage: <time>" pattern.
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
            if (!(containerScreen.getMenu() instanceof ChestMenu chestMenu)) return;

            // We need to check the title after the screen is initialized
            // Use ScreenEvents.afterRender to check once when inventory contents load
            ScreenEvents.afterTick(screen).register(afterTickScreen -> {
                String title = containerScreen.getTitle().getString();
                if (!title.contains("Crop Diagnostics")) return;

                // Parse slot 20 (0-indexed) for greenhouse timer lore
                try {
                    int slotIndex = 20;
                    if (slotIndex >= chestMenu.getContainer().getContainerSize()) return;

                    ItemStack item = chestMenu.getContainer().getItem(slotIndex);
                    if (item.isEmpty()) return;

                    ItemLore loreComponent = item.get(DataComponents.LORE);
                    if (loreComponent == null) return;

                    List<Component> loreLines = loreComponent.lines();
                    for (Component loreLine : loreLines) {
                        String stripped = loreLine.getString().replaceAll("\u00A7.", "").trim();
                        Matcher m = GREENHOUSE_NEXT_STAGE_PATTERN.matcher(stripped);
                        if (m.find()) {
                            String timeStr = m.group("time").trim();
                            long targetMillis = parseTimeToMillis(timeStr);
                            if (targetMillis > 0) {
                                ZoneInfoHud.setGreenhouseTarget(
                                        System.currentTimeMillis() + targetMillis);
                            }
                            return; // Found it, stop checking
                        }
                    }
                } catch (Exception ignored) {
                    // Silently ignore parsing errors
                }
            });
        });

        // ── Config keybind (G key) ──────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (configKeybind.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(FusionConfig.createDisplayScreen(client.screen));
                }
            }

            // ── Macro keybinds ─────────────────────────────────────────
            while (autoMinerKeybind.consumeClick()) {
                AutoMiner.toggle();
            }
            while (farmHelperKeybind.consumeClick()) {
                FarmHelper.toggle();
            }

            // ── Farm setup recording keybinds (only active during recording) ──
            while (setupMarkStart.consumeClick()) {
                if (FarmSetupScreen.isRecording()) {
                    FarmSetupScreen.markStart();
                }
            }
            while (setupMarkTurn.consumeClick()) {
                if (FarmSetupScreen.isRecording()) {
                    FarmSetupScreen.markTurn();
                }
            }
            while (setupMarkEnd.consumeClick()) {
                if (FarmSetupScreen.isRecording()) {
                    FarmSetupScreen.markEnd();
                }
            }
            while (setupUndo.consumeClick()) {
                if (FarmSetupScreen.isRecording()) {
                    FarmSetupScreen.undoLast();
                }
            }

            // ── Macro tick handlers ────────────────────────────────────
            if (FusionConfig.isAutoMinerEnabled() && AutoMiner.isEnabled()) {
                AutoMiner.tick(client);
            }
            if (FusionConfig.isFarmHelperEnabled() && FarmHelper.isEnabled()) {
                FarmHelper.tick(client);
            }

            // ── Periodic tick tasks ─────────────────────────────────────
            tickCounter++;

            // Crystal Hollows map zone discovery (every 40 ticks = 2 seconds)
            if (tickCounter % 40 == 0) {
                CrystalHollowsMapHud.tick();
            }

            // Item Pickup Log — detect inventory changes every tick
            ItemPickupLogHud pickupLog = ItemPickupLogHud.getInstance();
            if (pickupLog != null) {
                pickupLog.tick();
            }
        });

        // ── Item tooltip overlay ────────────────────────────────────────
        ItemTooltipCallback.EVENT.register(new ItemTooltipListener());

        // ── World rendering (3D overlays) ───────────────────────────────
        // Using END_MAIN same as pasunhack (WorldRenderEvents.END_MAIN)
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            // Chest ESP
            if (FusionConfig.isChestEspEnabled()) {
                ChestEspRenderer.render(context);
            }

            // 3D Waypoints + Pickobulus preview
            WaypointRenderer.render(context);
        });
    }

    /**
     * Parse a SkyBlock duration string like "1h 40m 20s" into milliseconds.
     * Supports h (hours), m (minutes), s (seconds) units.
     */
    private static long parseTimeToMillis(String timeStr) {
        long totalMs = 0;
        Pattern unitPattern = Pattern.compile("(\\d+)([hms])");
        Matcher matcher = unitPattern.matcher(timeStr);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "h": totalMs += value * 3600_000L; break;
                case "m": totalMs += value * 60_000L; break;
                case "s": totalMs += value * 1000L; break;
            }
        }
        return totalMs;
    }
}
