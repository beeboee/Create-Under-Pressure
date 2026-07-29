package com.beeboee.createunderpressure.mixin;

import com.beeboee.createunderpressure.pressure.HydraulicPlanRuntime;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import com.simibubi.create.foundation.ICapabilityProvider;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces an active open pipe endpoint's one-block handler with Create's own
 * persistent hose-pulley filler/drainer. The normal OpenEndedPipe handler remains
 * untouched whenever the hydraulic plan is not using this endpoint.
 */
@Mixin(value = OpenEndedPipe.class, remap = false)
public abstract class OpenEndedPipeMixin {
    @Shadow private Level world;
    @Shadow private BlockPos pos;
    @Shadow private BlockPos outputPos;

    @Unique private SmartBlockEntity createUnderPressure$host;
    @Unique private FluidFillingBehaviour createUnderPressure$filler;
    @Unique private FluidDrainingBehaviour createUnderPressure$drainer;
    @Unique private HosePulleyFluidHandler createUnderPressure$handler;
    @Unique private ICapabilityProvider<IFluidHandler> createUnderPressure$provider;
    @Unique private long createUnderPressure$lastBehaviourTick = Long.MIN_VALUE;

    @Inject(method = "manageSource", at = @At("TAIL"), remap = false)
    private void createUnderPressure$prepareHoseContext(Level level, BlockEntity networkBE, CallbackInfo ci) {
        if (!(networkBE instanceof SmartBlockEntity smart)) return;
        if (createUnderPressure$host != smart || createUnderPressure$handler == null) {
            createUnderPressure$host = smart;
            SmartFluidTank internal = new SmartFluidTank(1500, $ -> {});
            createUnderPressure$filler = new FluidFillingBehaviour(smart);
            createUnderPressure$drainer = new FluidDrainingBehaviour(smart);
            createUnderPressure$handler = new HosePulleyFluidHandler(
                    internal,
                    createUnderPressure$filler,
                    createUnderPressure$drainer,
                    () -> outputPos,
                    this::createUnderPressure$isActive);
            createUnderPressure$provider = ICapabilityProvider.of(() -> createUnderPressure$handler);
            createUnderPressure$lastBehaviourTick = Long.MIN_VALUE;
        }

        if (level != null && createUnderPressure$lastBehaviourTick != level.getGameTime()) {
            createUnderPressure$filler.tick();
            createUnderPressure$drainer.tick();
            createUnderPressure$lastBehaviourTick = level.getGameTime();
        }
    }

    @Inject(method = "provideHandler", at = @At("HEAD"), cancellable = true, remap = false)
    private void createUnderPressure$provideHoseHandler(
            CallbackInfoReturnable<ICapabilityProvider<IFluidHandler>> cir) {
        if (createUnderPressure$provider == null || !createUnderPressure$isActive()) return;
        cir.setReturnValue(createUnderPressure$provider);
    }

    @Unique
    private boolean createUnderPressure$isActive() {
        Direction face = createUnderPressure$face();
        return world != null && face != null
                && HydraulicPlanRuntime.worldMode(world, pos, face) != HydraulicPlanRuntime.WorldMode.NONE;
    }

    @Unique
    private Direction createUnderPressure$face() {
        if (pos == null || outputPos == null) return null;
        for (Direction direction : Direction.values()) {
            if (pos.relative(direction).equals(outputPos)) return direction;
        }
        return null;
    }
}
