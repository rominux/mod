package moi.fusion_mod.macros;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game setup GUI for configuring farm waypoints.
 * Replicates the Python setup.py recording workflow:
 *
 * 1. Open this screen → select a crop to configure
 * 2. Screen closes → player enters "recording mode" (walks around in-game)
 * 3. Keybindings (registered in Fusion_modClient):
 *      F8  = Mark Start
 *      F9  = Mark Turn
 *      F10 = Mark End (finishes recording, computes yaw/pitch, saves)
 *      F11 = Undo last point
 * 4. On "Mark End", waypoints are compiled and saved to FarmConfig.
 *
 * Uses Mojang mappings (1.21.10): GuiGraphics, Button, Component, etc.
 */
public class FarmSetupScreen extends Screen {
    private final Screen parent;

    // ── Layout constants ────────────────────────────────────────────────
    private static final int HEADER_HEIGHT = 30;
    private static final int PADDING = 8;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;

    // ══════════════════════════════════════════════════════════════════════
    // Static recording mode state (accessible from Fusion_modClient tick)
    // ══════════════════════════════════════════════════════════════════════

    /** Whether we are currently in recording mode (screen is closed, player walks around). */
    private static boolean recording = false;
    /** The crop name being recorded. */
    private static String recordingCrop = null;
    /** Recorded points: each has type, x, y, z, yaw, pitch. */
    private static final List<RecordedPoint> recordedPoints = new ArrayList<>();

    /** A single recorded point with position and camera orientation. */
    public static class RecordedPoint {
        public final String type; // "start", "turn", "end"
        public final double x, y, z;
        public final float yaw, pitch;

        public RecordedPoint(String type, double x, double y, double z, float yaw, float pitch) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Constructor
    // ══════════════════════════════════════════════════════════════════════

    public FarmSetupScreen(Screen parent) {
        super(Component.literal("Farm Setup"));
        this.parent = parent;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Screen lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        this.clearWidgets();

        int screenW = this.width;
        int screenH = this.height;
        int contentW = Math.min(300, screenW - 40);
        int startX = (screenW - contentW) / 2;
        int y = HEADER_HEIGHT + PADDING;

        // ── Crop buttons ────────────────────────────────────────────────
        for (String crop : FarmConfig.CROP_NAMES) {
            boolean configured = FarmConfig.hasFarm(crop);
            String status = configured ? " \u00A7a[OK]" : " \u00A77[--]";
            final String cropName = crop;

            this.addRenderableWidget(Button.builder(
                    Component.literal(crop + status),
                    button -> startRecording(cropName)
            ).bounds(startX, y, contentW, BUTTON_HEIGHT).build());

            y += ROW_HEIGHT;
        }

        // ── Done button ─────────────────────────────────────────────────
        y += PADDING;
        int doneW = 120;
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> onClose()
        ).bounds((screenW - doneW) / 2, Math.min(y, screenH - 28), doneW, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Header
        graphics.drawCenteredString(this.font, "\u00A7b\u00A7lFarm Setup", this.width / 2, 10, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, "\u00A77Select a crop to configure waypoints",
                this.width / 2, 20, 0xFFAAAAAA);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recording Mode Control
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start recording mode for a crop. Closes the screen so the player
     * can walk around and use keybindings.
     */
    private void startRecording(String cropName) {
        recording = true;
        recordingCrop = cropName;
        recordedPoints.clear();

        if (this.minecraft != null) {
            this.minecraft.setScreen(null); // Close screen to let player walk
            if (this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.literal("\u00A7b[Farm Setup] Recording for \u00A7e" + cropName), false);
                this.minecraft.player.displayClientMessage(
                        Component.literal("\u00A77  F8 = Start  |  F9 = Turn  |  F10 = End  |  F11 = Undo"), false);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Static API — called from Fusion_modClient keybinding handlers
    // ══════════════════════════════════════════════════════════════════════

    /** Check if recording mode is active. */
    public static boolean isRecording() {
        return recording;
    }

    /** Cancel recording without saving. */
    public static void cancelRecording() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c[Farm Setup] Recording cancelled for " + recordingCrop), false);
        }
        recording = false;
        recordingCrop = null;
        recordedPoints.clear();
    }

    /** Mark a Start waypoint at the player's current position. */
    public static void markStart() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Only one start allowed
        for (RecordedPoint p : recordedPoints) {
            if ("start".equals(p.type)) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7c[Farm Setup] Start already recorded. Press F11 to undo."), false);
                return;
            }
        }

        double x = Math.round(mc.player.getX() * 1000.0) / 1000.0;
        double y = Math.round(mc.player.getY() * 1000.0) / 1000.0;
        double z = Math.round(mc.player.getZ() * 1000.0) / 1000.0;
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();

        recordedPoints.add(new RecordedPoint("start", x, y, z, yaw, pitch));
        mc.player.displayClientMessage(
                Component.literal(String.format("\u00A7a[Farm Setup] START recorded \u00A77(%.1f, %.1f, %.1f)", x, y, z)), false);
    }

    /** Mark a Turn waypoint at the player's current position. */
    public static void markTurn() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Must have start first
        boolean hasStart = false;
        for (RecordedPoint p : recordedPoints) {
            if ("start".equals(p.type)) { hasStart = true; break; }
        }
        if (!hasStart) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c[Farm Setup] Record START (F8) first."), false);
            return;
        }

        double x = Math.round(mc.player.getX() * 1000.0) / 1000.0;
        double y = Math.round(mc.player.getY() * 1000.0) / 1000.0;
        double z = Math.round(mc.player.getZ() * 1000.0) / 1000.0;
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();

        int turnCount = 0;
        for (RecordedPoint p : recordedPoints) {
            if ("turn".equals(p.type)) turnCount++;
        }

        recordedPoints.add(new RecordedPoint("turn", x, y, z, yaw, pitch));
        mc.player.displayClientMessage(
                Component.literal(String.format("\u00A7e[Farm Setup] TURN %d recorded \u00A77(%.1f, %.1f, %.1f)", turnCount + 1, x, y, z)), false);
    }

    /** Mark an End waypoint and finish recording. Uses the player's exact current yaw/pitch. */
    public static void markEnd() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Must have start first
        boolean hasStart = false;
        for (RecordedPoint p : recordedPoints) {
            if ("start".equals(p.type)) { hasStart = true; break; }
        }
        if (!hasStart) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c[Farm Setup] Record START (F8) first."), false);
            return;
        }

        double x = Math.round(mc.player.getX() * 1000.0) / 1000.0;
        double y = Math.round(mc.player.getY() * 1000.0) / 1000.0;
        double z = Math.round(mc.player.getZ() * 1000.0) / 1000.0;

        // Capture the player's exact current yaw and pitch as the farm's locked angles
        float farmYaw = Math.round(mc.player.getYRot() * 10.0f) / 10.0f;
        float farmPitch = Math.round(mc.player.getXRot() * 10.0f) / 10.0f;

        recordedPoints.add(new RecordedPoint("end", x, y, z, farmYaw, farmPitch));

        // Build waypoint list (only type, x, y, z — yaw/pitch are per-farm)
        List<FarmConfig.Waypoint> waypoints = new ArrayList<>();
        for (RecordedPoint p : recordedPoints) {
            waypoints.add(new FarmConfig.Waypoint(p.type, p.x, p.y, p.z));
        }

        // Save to FarmConfig using the player's exact angles at the moment of Mark End
        FarmConfig.setFarm(recordingCrop, waypoints, farmYaw, farmPitch);

        mc.player.displayClientMessage(
                Component.literal(String.format(
                        "\u00A7a[Setup] Farm saved with Yaw: %.1f\u00B0 and Pitch: %.1f\u00B0",
                        farmYaw, farmPitch)), false);
        mc.player.displayClientMessage(
                Component.literal(String.format(
                        "\u00A7a[Setup] \u00A7e%s\u00A7a \u2014 %d waypoints saved.",
                        recordingCrop, waypoints.size())), false);

        // End recording
        recording = false;
        recordingCrop = null;
        recordedPoints.clear();
    }

    /** Undo the last recorded point. */
    public static void undoLast() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (recordedPoints.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.literal("\u00A7c[Farm Setup] Nothing to undo."), false);
            return;
        }

        RecordedPoint removed = recordedPoints.remove(recordedPoints.size() - 1);
        mc.player.displayClientMessage(
                Component.literal("\u00A7d[Farm Setup] Undone: " + removed.type.toUpperCase()), false);
    }

}
