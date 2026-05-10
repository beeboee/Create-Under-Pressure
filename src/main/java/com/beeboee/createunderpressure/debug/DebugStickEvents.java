package com.beeboee.createunderpressure.debug;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.ParseResults;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class DebugStickEvents {
    private DebugStickEvents() {}

    private static final Map<UUID, Long> LAST_BLOCK_CLICK_TICK = new HashMap<>();

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        DebugInfo.rememberChat(event.getPlayer(), event.getMessage().getString());
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> results = event.getParseResults();
        if (results == null || results.getReader() == null) return;

        String command = results.getReader().getString();
        String sayMessage = sayMessage(command);
        if (sayMessage == null) return;

        String sourceName = "command";
        CommandContext<CommandSourceStack> context = results.getContext().build(command);
        if (context != null && context.getSource() != null) sourceName = context.getSource().getTextName();
        DebugInfo.rememberCommandSay(context == null ? null : context.getSource().getLevel(), sourceName, sayMessage);
    }

    private static String sayMessage(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return null;
        String command = rawCommand.strip();
        if (command.startsWith("/")) command = command.substring(1).strip();
        if (command.startsWith("minecraft:")) command = command.substring("minecraft:".length()).strip();
        if (!command.startsWith("say ")) return null;

        String message = command.substring(4).strip();
        return message.isBlank() ? null : message;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getLevel().isClientSide) return;
        if (!event.getItemStack().is(Items.STICK)) return;

        LAST_BLOCK_CLICK_TICK.put(event.getEntity().getUUID(), event.getLevel().getGameTime());
        DebugInfo.toggle(event.getLevel(), event.getEntity(), event.getItemStack(), event.getEntity().isShiftKeyDown(), event.getPos());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getLevel().isClientSide) return;
        if (!event.getItemStack().is(Items.STICK)) return;

        Long lastBlockClickTick = LAST_BLOCK_CLICK_TICK.get(event.getEntity().getUUID());
        if (lastBlockClickTick != null && lastBlockClickTick == event.getLevel().getGameTime()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        DebugInfo.toggle(event.getLevel(), event.getEntity(), event.getItemStack(), event.getEntity().isShiftKeyDown());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide) return;
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) DebugInfo.tick(serverLevel);
    }
}
