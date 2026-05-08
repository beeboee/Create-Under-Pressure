package com.beeboee.createunderpressure.pressure;

public final class PressureConfig {
    private PressureConfig() {}

    /** Base mechanical flow. Pressure gates routes; valves can lower this later. */
    public static final int BASE_FLOW_MB_PER_TICK = 128;

    public static final int WORLD_BLOCK_MB = 1000;
    public static final double EPSILON = 0.01;
    public static final double DEAD_BAND = 0.05;
}
