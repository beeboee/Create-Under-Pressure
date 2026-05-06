package com.beeboee.createunderpressure.debug;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class DebugStickEvents {
    private DebugStickEvents() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        if (event.getLevel().isClientSide) {
            return;
        }

        if (!event.getItemStack().is(Items.STICK)) {
            return;
        }

        DebugInfo.enable(event.getLevel(), event.getEntity(), 10);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
