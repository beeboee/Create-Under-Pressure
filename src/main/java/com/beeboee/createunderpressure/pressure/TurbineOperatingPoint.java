package com.beeboee.createunderpressure.pressure;

/**
 * Calculated output for one turbine evaluation.
 */
public record TurbineOperatingPoint(
    double availableHeadBlocks,
    double usedHeadBlocks,
    double headFactor,
    double flowFactor,
    double rpm,
    double stressCapacity
) {
    public TurbineOperatingPoint {
        requireFiniteNonNegative("availableHeadBlocks", availableHeadBlocks);
        requireFiniteNonNegative("usedHeadBlocks", usedHeadBlocks);
        requireUnitInterval("headFactor", headFactor);
        requireUnitInterval("flowFactor", flowFactor);
        requireFiniteNonNegative("rpm", rpm);
        requireFiniteNonNegative("stressCapacity", stressCapacity);
    }

    public boolean isRunning() {
        return rpm > 0.0 && stressCapacity > 0.0;
    }

    public static TurbineOperatingPoint stopped(double availableHeadBlocks) {
        return new TurbineOperatingPoint(Math.max(0.0, availableHeadBlocks), 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireUnitInterval(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
