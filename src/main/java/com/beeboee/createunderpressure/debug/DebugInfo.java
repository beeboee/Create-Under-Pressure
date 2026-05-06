package com.beeboee.createunderpressure.debug;

import com.beeboee.createunderpressure.CreateUnderPressure;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class DebugInfo {
    private DebugInfo() {}

    private static long debugUntilGameTime = -1L;

    public static void enable(Level level, Player player, int seconds) {
        debugUntilGameTime = level.getGameTime() + (seconds * 20L);

        Component message = Component.literal("Create: Under Pressure debug logging enabled for " + seconds + " seconds");
        player.displayClientMessage(message, true);
        CreateUnderPressure.LOGGER.info("{}", message.getString());
    }

    public static boolean isEnabled(Level level) {
        return level != null && level.getGameTime() <= debugUntilGameTime;
    }

    public static void log(Level level, String message, Object... args) {
        if (isEnabled(level)) {
            CreateUnderPressure.LOGGER.info(message, args);
        }
    }
}
