package moi.fusion_mod;

import moi.fusion_mod.config.FusionConfig;
import moi.fusion_mod.economy.ItemTooltipListener;
import moi.fusion_mod.economy.PriceDataManager;
import moi.fusion_mod.hollows.ChestEspRenderer;
import moi.fusion_mod.hollows.CrystalHollowsMapHud;
import moi.fusion_mod.ui.layout.JarvisGuiManager;
import moi.fusion_mod.waypoints.WaypointRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public class Fusion_modClient implements ClientModInitializer {
    public static KeyMapping configKeybind;

    // Tick counter for periodic tasks (Crystal Hollows map discovery runs every 40 ticks)
    // Interval extracted from CrystalsLocationsManager: Scheduler.scheduleCyclic(update, 40)
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        FusionConfig.init();

        configKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fusion_mod.config",
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.register(ResourceLocation.parse("fusion_mod:general"))
        ));

        JarvisGuiManager.initializeHuds();

        // Start background price data fetching (Bazaar, LBIN, NPC prices)
        PriceDataManager.init();

        registerEvents();
    }

    private void registerEvents() {
        // ── Config keybind (G key) ──────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (configKeybind.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(FusionConfig.createDisplayScreen(client.screen));
                }
            }

            // ── Periodic tick tasks ─────────────────────────────────────
            tickCounter++;

            // Crystal Hollows map zone discovery (every 40 ticks = 2 seconds)
            // Interval from CrystalsLocationsManager: Scheduler.scheduleCyclic(update, 40)
            if (tickCounter % 40 == 0) {
                CrystalHollowsMapHud.tick();
            }
        });

        // ── Item tooltip overlay ────────────────────────────────────────
        ItemTooltipCallback.EVENT.register(new ItemTooltipListener());

        // ── World rendering (3D overlays) ───────────────────────────────
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            // Chest ESP
            if (FusionConfig.isChestEspEnabled()) {
                ChestEspRenderer.render(context);
            }

            // 3D Waypoints (commissions, crystal hollows, custom)
            WaypointRenderer.render(context);
        });
    }
}
