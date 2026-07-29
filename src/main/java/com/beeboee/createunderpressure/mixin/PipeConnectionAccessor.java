package com.beeboee.createunderpressure.mixin;

import com.simibubi.create.content.fluids.PipeConnection;
import net.createmod.catnip.data.Couple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Allows the hydraulic runtime to replace, rather than stack, pressure values. */
@Mixin(value = PipeConnection.class, remap = false)
public interface PipeConnectionAccessor {
    @Accessor("pressure")
    Couple<Float> createUnderPressure$getPressure();
}
