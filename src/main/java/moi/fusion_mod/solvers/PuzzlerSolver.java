package moi.fusion_mod.solvers;

import moi.fusion_mod.config.FusionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puzzler Solver — decodes the Puzzler NPC's arrow sequence and marks the solution block.
 * Ported from pasunhack ChatSolverUtils.java (Yarn → Mojang 1.21.10).
 *
 * The Puzzler NPC in the Dwarven Mines gives a 10-character arrow sequence using
 * ▲ ▶ ◀ ▼ characters. Starting from a fixed position (181, 195, 135), each arrow
 * moves one block in the corresponding direction. The solution block is placed
 * as a crimson plank marker so the player can find it.
 */
public class PuzzlerSolver {

    private static final Pattern PUZZLER_PATTERN =
            Pattern.compile("^\\[NPC\\] Puzzler: ((?:\\u25B2|\\u25B6|\\u25C0|\\u25BC){10})$");

    /**
     * Called from the chat message listener with the stripped (no formatting codes) text.
     * If the message matches the Puzzler's arrow pattern and the solver is enabled,
     * computes the target block and places a crimson plank marker + chat message.
     */
    public static void onChatMessage(String strippedText) {
        if (!FusionConfig.isPuzzlerSolverEnabled()) return;

        Matcher matcher = PUZZLER_PATTERN.matcher(strippedText);
        if (matcher.matches()) {
            int x = 181;
            int z = 135;
            for (char c : matcher.group(1).toCharArray()) {
                switch (c) {
                    case '\u25B2': z++; break;  // ▲
                    case '\u25BC': z--; break;  // ▼
                    case '\u25C0': x++; break;  // ◀
                    case '\u25B6': x--; break;  // ▶
                }
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                BlockPos targetPos = new BlockPos(x, 195, z);
                mc.level.setBlockAndUpdate(targetPos, Blocks.CRIMSON_PLANKS.defaultBlockState());

                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("\u00A7e[Puzzler] \u00A7fSolution at: " + x + ", 195, " + z),
                            false);
                }
            }
        }
    }
}
