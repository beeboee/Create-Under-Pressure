package com.beeboee.createunderpressure.visual;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Small adapter for Create's own pipe flow renderer/cache hooks. */
public final class CreatePipeFlowVisualBridge {
    private CreatePipeFlowVisualBridge() {}

    public static void apply(Level level, FluidTransportBehaviour pipe, BlockPos pipePos, Direction side, boolean inbound, float pressure) {
        if (level == null || level.isClientSide || pipe == null || pipePos == null || side == null) return;
        pipe.addPressure(side, inbound, pressure);
        FluidTransportBehaviour.cacheFlows(level, pipePos);
    }
}
