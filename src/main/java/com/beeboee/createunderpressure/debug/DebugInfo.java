package com.beeboee.createunderpressure.debug;

import com.beeboee.createunderpressure.CreateUnderPressure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class DebugInfo {
    private DebugInfo() {}

    private static final int DEFAULT_SECONDS = 10;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static long debugUntilGameTime = -1L;
    private static UUID activePlayerId = null;
    private static ItemStack activeStack = ItemStack.EMPTY;
    private static Path activeLogFile = null;
    private static boolean endedMessageSent = true;

    public static void toggle(Level level, Player player, ItemStack stack, boolean stop) {
        if (stop && isEnabled(level)) {
            disable(level, player, "Create: Under Pressure debug logging stopped");
            return;
        }

        enable(level, player, stack, DEFAULT_SECONDS);
    }

    public static void enable(Level level, Player player, ItemStack stack, int seconds) {
        long now = level.getGameTime();
        boolean extending = isEnabled(level);

        if (extending) debugUntilGameTime += seconds * 20L;
        else debugUntilGameTime = now + seconds * 20L;

        activePlayerId = player.getUUID();
        activeStack = stack;
        endedMessageSent = false;
        setGlint(stack, true);

        if (!extending || activeLogFile == null) activeLogFile = newLogFile();

        long remainingSeconds = Math.max(0, (debugUntilGameTime - now + 19L) / 20L);
        Component message = Component.literal(extending
            ? "Create: Under Pressure debug logging extended by " + seconds + " seconds (" + remainingSeconds + "s remaining)"
            : "Create: Under Pressure debug logging enabled for " + seconds + " seconds");

        player.displayClientMessage(message, true);
        writeLine("SESSION " + message.getString());
        CreateUnderPressure.LOGGER.info("{}", message.getString());
    }

    public static void tick(ServerLevel level) {
        if (endedMessageSent || debugUntilGameTime < 0L || level.getGameTime() <= debugUntilGameTime) return;

        endedMessageSent = true;
        setGlint(activeStack, false);

        ServerPlayer player = activePlayerId == null ? null : level.getServer().getPlayerList().getPlayer(activePlayerId);
        Component message = Component.literal("Create: Under Pressure debug logging finished");
        if (player != null) player.displayClientMessage(message, true);

        writeLine("SESSION " + message.getString());
        CreateUnderPressure.LOGGER.info("{}", message.getString());

        activePlayerId = null;
        activeStack = ItemStack.EMPTY;
        activeLogFile = null;
    }

    public static boolean isEnabled(Level level) {
        return level != null && level.getGameTime() <= debugUntilGameTime;
    }

    public static void log(Level level, String message, Object... args) {
        if (!isEnabled(level)) return;

        CreateUnderPressure.LOGGER.info(message, args);
        writeLine(format(message, args));
    }

    private static void disable(Level level, Player player, String text) {
        debugUntilGameTime = level.getGameTime() - 1L;
        endedMessageSent = true;
        setGlint(activeStack, false);

        Component message = Component.literal(text);
        player.displayClientMessage(message, true);
        writeLine("SESSION " + message.getString());
        CreateUnderPressure.LOGGER.info("{}", message.getString());

        activePlayerId = null;
        activeStack = ItemStack.EMPTY;
        activeLogFile = null;
    }

    private static void setGlint(ItemStack stack, boolean enabled) {
        if (stack == null || stack.isEmpty()) return;
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, enabled ? Boolean.TRUE : null);
    }

    private static Path newLogFile() {
        Path dir = Path.of("logs", "create-under-pressure");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CreateUnderPressure.LOGGER.warn("Could not create Create: Under Pressure debug log directory", e);
        }
        return dir.resolve("debug-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
    }

    private static void writeLine(String line) {
        if (activeLogFile == null) return;

        String timestamped = "[" + LocalDateTime.now().format(LINE_TIME) + "] " + line + System.lineSeparator();
        try {
            Files.writeString(activeLogFile, timestamped, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CreateUnderPressure.LOGGER.warn("Could not write Create: Under Pressure debug log file", e);
        }
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;

        StringBuilder out = new StringBuilder();
        int argIndex = 0;
        for (int i = 0; i < message.length(); i++) {
            if (i + 1 < message.length() && message.charAt(i) == '{' && message.charAt(i + 1) == '}' && argIndex < args.length) {
                out.append(String.valueOf(args[argIndex++]));
                i++;
            } else {
                out.append(message.charAt(i));
            }
        }

        while (argIndex < args.length) out.append(' ').append(String.valueOf(args[argIndex++]));
        return out.toString();
    }
}
