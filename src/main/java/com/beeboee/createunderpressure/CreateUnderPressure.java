package com.beeboee.createunderpressure;

import com.beeboee.createunderpressure.debug.DebugStickEvents;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.common.NeoForge;

@Mod(CreateUnderPressure.MOD_ID)
public class CreateUnderPressure {
    public static final String MOD_ID = "create_under_pressure";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateUnderPressure(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        NeoForge.EVENT_BUS.register(DebugStickEvents.class);

        LOGGER.info("Create: Under Pressure loaded");
    }
}
