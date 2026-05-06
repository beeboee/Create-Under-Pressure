package com.beeboee.createunderpressure.mixin;

import com.beeboee.createunderpressure.pressure.TankPressureService;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidTransportBehaviour.class)
public abstract class FluidTransportBehaviourMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void createUnderPressure$tickPipePressure(CallbackInfo ci) {
        TankPressureService.tickPipe((FluidTransportBehaviour) (Object) this);
    }
}
