package com.beeboee.createunderpressure.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.beeboee.createunderpressure.pressure.TankPressureService;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

@Mixin(FluidTankBlockEntity.class)
public abstract class FluidTankBlockEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void createUnderPressure$tickPressure(CallbackInfo ci) {
        TankPressureService.tickTank((FluidTankBlockEntity) (Object) this);
    }
}
