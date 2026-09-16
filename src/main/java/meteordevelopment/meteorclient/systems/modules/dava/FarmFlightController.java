/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.dava;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;

/** Coordinates the toggle-style island flight command across all farming modules. */
final class FarmFlightController {
    private static final int COMMAND_TIMEOUT_TICKS = 60;

    private static Object playerSession;
    private static boolean commandAttempted;
    private static boolean warnedFailure;
    private static int commandSentAtAge;

    private FarmFlightController() {}

    public static boolean ensureFlying(Module requester) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        if (playerSession != mc.player) {
            playerSession = mc.player;
            commandAttempted = false;
            warnedFailure = false;
            commandSentAtAge = 0;
        }

        if (mc.player.getAbilities().flying) return true;

        // The server has already granted flight, so enabling the client flying
        // flag is safe and does not invoke the toggle-style island command.
        if (mc.player.getAbilities().allowFlying) {
            mc.player.getAbilities().flying = true;
            mc.player.sendAbilitiesUpdate();
            return true;
        }

        if (!commandAttempted) {
            ChatUtils.sendPlayerMsg("/is fly", false);
            commandAttempted = true;
            commandSentAtAge = mc.player.age;
            requester.info("Flight is unavailable; trying /is fly once.");
            return false;
        }

        if (mc.player.age - commandSentAtAge < COMMAND_TIMEOUT_TICKS) return false;

        if (!warnedFailure) {
            requester.warning("/is fly did not grant flight. Farm flight is paused and the command will not be sent again this session.");
            warnedFailure = true;
        }
        return false;
    }
}
