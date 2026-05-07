package com.beeboee.createunderpressure.debug;

import com.beeboee.createunderpressure.CreateUnderPressure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
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
    private static final int MAX_FILENAME_LABEL_LENGTH = 64;
    private static final int LOOSE_TARGET_RADIUS = 8;
    private static final Pattern BLOCK_POS_PATTERN = Pattern.compile("BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<UUID, String> lastChatMessages = new HashMap<>();

    private static long debugUntilGameTime = -1L;
    private static UUID activePlayerId = null;
    private static ItemStack activeStack = ItemStack.EMPTY;
    private static Path activeLogFile = null;
    private static boolean endedMessageSent = true;
    private static BlockPos selectedTarget = null;

    private static boolean networkContextActive = false;
    private static boolean networkContextAllowed = true;
    private static String networkContextTag = null;

    public static void rememberChat(Player player, String message) {
        if (player == null || message == null || message.isBlank()) return;
        lastChatMessages.put(player.getUUID(), message.strip());
    }

    public static void toggle(Level level, Player player, ItemStack stack, boolean stop) {
        toggle(level, player, stack, stop, null);
    }

    public static void toggle(Level level, Player player, ItemStack stack, boolean stop, BlockPos target) {
        if (stop && isEnabled(level)) {
            disable(level, player, "Create: Under Pressure debug logging stopped");
            return;
        }

        enable(level, player, stack, DEFAULT_SECONDS, target);
    }

    public static void enable(Level level, Player player, ItemStack stack, int seconds) {
        enable(level, player, stack, seconds, null);
    }

    public static void enable(Level level, Player player, ItemStack stack, int seconds, BlockPos target) {
        long now = level.getGameTime();
        boolean extending = isEnabled(level);

        if (extending) debugUntilGameTime += seconds * 20L;
        else debugUntilGameTime = now + seconds * 20L;

        activePlayerId = player.getUUID();
        activeStack = stack;
        endedMessageSent = false;
        selectedTarget = target == null ? null : target.immutable();
        setGlint(stack, true);

        if (!extending || activeLogFile == null) activeLogFile = newLogFile(player);

        long remainingSeconds = Math.max(0, (debugUntilGameTime - now + 19L) / 20L);
        String scope = selectedTarget == null ? "global" : "network near " + selectedTarget.toShortString();
        Component message = Component.literal(extending
            ? "Create: Under Pressure debug logging extended by " + seconds + " seconds (" + remainingSeconds + "s remaining, " + scope + ")"
            : "Create: Under Pressure debug logging enabled for " + seconds + " seconds (" + scope + ")");

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
        selectedTarget = null;
        clearNetworkContext();
    }

    public static boolean isEnabled(Level level) {
        return level != null && level.getGameTime() <= debugUntilGameTime;
    }

    public static boolean beginNetwork(Level level, Set<BlockPos> networkPipes, BlockPos owner) {
        networkContextActive = true;
        networkContextTag = owner == null ? "net@unknown" : "net@" + owner.toShortString();
        networkContextAllowed = isEnabled(level) && (selectedTarget == null || touchesSelectedTarget(networkPipes));
        return networkContextAllowed;
    }

    public static void endNetwork() {
        clearNetworkContext();
    }

    public static boolean isNetworkAllowed() {
        return !networkContextActive || networkContextAllowed;
    }

    public static void log(Level level, String message, Object... args) {
        if (!isEnabled(level)) return;
        if (!isNetworkAllowed()) return;

        String formatted = format(message, args);
        if (!networkContextActive && !passesLooseTargetFilter(formatted)) return;
        if (networkContextActive && networkContextTag != null) formatted = "[" + networkContextTag + "] " + formatted;

        CreateUnderPressure.LOGGER.info("{}", formatted);
        writeLine(formatted);
    }

    private static boolean touchesSelectedTarget(Set<BlockPos> networkPipes) {
        if (selectedTarget == null) return true;
        for (BlockPos pipe : networkPipes) {
            if (pipe.distManhattan(selectedTarget) <= 1) return true;
        }
        return false;
    }

    private static boolean passesLooseTargetFilter(String line) {
        if (selectedTarget == null) return true;

        String shortPos = selectedTarget.toShortString();
        String longPos = selectedTarget.toString();
        if (line.contains(shortPos) || line.contains(longPos)) return true;

        Matcher matcher = BLOCK_POS_PATTERN.matcher(line);
        while (matcher.find()) {
            int x = Integer.parseInt(matcher.group(1));
            int y = Integer.parseInt(matcher.group(2));
            int z = Integer.parseInt(matcher.group(3));
            int distance = Math.abs(x - selectedTarget.getX()) + Math.abs(y - selectedTarget.getY()) + Math.abs(z - selectedTarget.getZ());
            if (distance <= LOOSE_TARGET_RADIUS) return true;
        }

        return false;
    }

    private static void clearNetworkContext() {
        networkContextActive = false;
        networkContextAllowed = true;
        networkContextTag = null;
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
        selectedTarget = null;
        clearNetworkContext();
    }

    private static void setGlint(ItemStack stack, boolean enabled) {
        if (stack == null || stack.isEmpty()) return;
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, enabled ? Boolean.TRUE : null);
    }

    private static Path newLogFile(Player player) {
        Path dir = Path.of("logs", "create-under-pressure");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CreateUnderPressure.LOGGER.warn("Could not create Create: Under Pressure debug log directory", e);
        }

        String label = sanitizedLabel(lastChatMessages.get(player.getUUID()));
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        return dir.resolve(label + "-" + timestamp + ".txt");
    }

    private static String sanitizedLabel(String raw) {
        if (raw == null || raw.isBlank()) return "debug";

        String cleaned = raw.strip()
            .replaceAll("[\\\\/:*?\"<>|]", "")
            .replaceAll("[^A-Za-z0-9._ -]", "")
            .replaceAll("\\s+", "_")
            .replaceAll("_+", "_");

        cleaned = cleaned.replaceAll("^[._ -]+", "").replaceAll("[._ -]+$", "");
        if (cleaned.isBlank()) return "debug";
        if (cleaned.length() > MAX_FILENAME_LABEL_LENGTH) cleaned = cleaned.substring(0, MAX_FILENAME_LABEL_LENGTH);
        return cleaned;
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
