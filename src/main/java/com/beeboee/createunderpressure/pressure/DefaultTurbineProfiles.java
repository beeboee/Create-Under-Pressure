package com.beeboee.createunderpressure.pressure;

/**
 * Provisional balance values for the first playable turbine prototype.
 * These should move to server config after the block is working end to end.
 */
public final class DefaultTurbineProfiles {
    private DefaultTurbineProfiles() {}

    public static final TurbineProfile WATER = new TurbineProfile(
        "water",
        1.0,
        32.0,
        100.0,
        128.0,
        1024.0
    );

    public static final TurbineProfile LAVA = new TurbineProfile(
        "lava",
        1.0,
        32.0,
        25.0,
        64.0,
        2048.0
    );
}
