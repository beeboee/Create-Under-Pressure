package com.beeboee.createunderpressure.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
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
