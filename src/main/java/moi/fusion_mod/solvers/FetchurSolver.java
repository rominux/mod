package moi.fusion_mod.solvers;

import moi.fusion_mod.config.FusionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetchur Solver — decodes Fetchur's riddles and displays the answer in chat.
 * Ported from pasunhack ChatSolverUtils.java (Yarn → Mojang 1.21.10).
 *
 * Fetchur is an NPC in the Dwarven Mines who asks for a random item each day.
 * His riddle always starts with "[NPC] Fetchur: its/theyre ..." and the solver
 * maps the riddle text to the correct item answer.
 */
public class FetchurSolver {

    private static final Map<String, String> FETCHUR_ANSWERS = new HashMap<>();
    private static final Pattern FETCHUR_PATTERN = Pattern
            .compile("^\\[NPC\\] Fetchur: (?:its|theyre) ([a-zA-Z, \\-]*)$");

    static {
        FETCHUR_ANSWERS.put("yellow and see through", "Yellow Stained Glass");
        FETCHUR_ANSWERS.put("circular and sometimes moves", "Compass");
        FETCHUR_ANSWERS.put("expensive minerals", "Mithril");
        FETCHUR_ANSWERS.put("useful during celebrations", "Firework Rocket");
        FETCHUR_ANSWERS.put("hot and gives energy", "Cheap / Decent / Black Coffee");
        FETCHUR_ANSWERS.put("tall and can be opened", "Any Wooden Door / Iron Door");
        FETCHUR_ANSWERS.put("brown and fluffy", "Rabbit's Foot");
        FETCHUR_ANSWERS.put("explosive but more than usual", "Superboom TNT");
        FETCHUR_ANSWERS.put("wearable and grows", "Pumpkin");
        FETCHUR_ANSWERS.put("shiny and makes sparks", "Flint and Steel");
        FETCHUR_ANSWERS.put("green and some dudes trade stuff for it", "Emerald");
        FETCHUR_ANSWERS.put("red and soft", "Red Wool");
    }

    /**
     * Called from the chat message listener with the stripped (no formatting codes) text.
     * If the message matches Fetchur's riddle pattern and the solver is enabled,
     * sends the decoded answer to the player's chat.
     */
    public static void onChatMessage(String strippedText) {
        if (!FusionConfig.isFetchurSolverEnabled()) return;

        Matcher matcher = FETCHUR_PATTERN.matcher(strippedText);
        if (matcher.matches()) {
            String riddle = matcher.group(1);
            String answer = FETCHUR_ANSWERS.getOrDefault(riddle, riddle);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("\u00A7e[Fetchur] \u00A7fAnswer: " + answer), false);
            }
        }
    }
}
