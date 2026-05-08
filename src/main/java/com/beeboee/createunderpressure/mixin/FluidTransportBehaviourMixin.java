package com.beeboee.createunderpressure.mixin;

import com.beeboee.createunderpressure.pressure.NetworkHeadBridgeService;
import com.beeboee.createunderpressure.pressure.NetworkPressurePlanner;
import com.beeboee.createunderpressure.visual.WorldExchangeVisualLayer;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidTransportBehaviour.class)
public abstract class FluidTransportBehaviourMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void createUnderPressure$tickPipePressure(CallbackInfo ci) {
        FluidTransportBehaviour pipe = (FluidTransportBehaviour) (Object) this;
        NetworkPressurePlanner.tickPipe(pipe);
        NetworkHeadBridgeService.tickPipe(pipe);
        WorldExchangeVisualLayer.tickPipe(pipe);
    }
}
