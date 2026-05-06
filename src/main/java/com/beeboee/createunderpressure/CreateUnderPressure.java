package com.beeboee.createunderpressure;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.common.Mod;

@Mod(CreateUnderPressure.MOD_ID)
public class CreateUnderPressure {
    public static final String MOD_ID = "create_under_pressure";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateUnderPressure() {
        LOGGER.info("Create: Under Pressure loaded");
    }
}
