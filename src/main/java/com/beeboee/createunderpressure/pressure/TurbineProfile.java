package com.beeboee.createunderpressure.pressure;

import java.util.Objects;

/**
 * Tunable gameplay values for one turbine fluid profile.
 *
 * <p>This type deliberately has no Minecraft or Create dependencies so the
 * pressure/output rules can be unit tested before they are connected to a
 * block entity.</p>
 */
public record TurbineProfile(
    String id,
    double minimumHeadBlocks,
    double maximumHeadBlocks,
    double designFlowMbPerTick,
    double maximumRpm,
    double maximumStressCapacity
) {
    public TurbineProfile {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");

        requireFiniteNonNegative("minimumHeadBlocks", minimumHeadBlocks);
        requireFinitePositive("maximumHeadBlocks", maximumHeadBlocks);
        requireFinitePositive("designFlowMbPerTick", designFlowMbPerTick);
        requireFinitePositive("maximumRpm", maximumRpm);
        requireFinitePositive("maximumStressCapacity", maximumStressCapacity);

        if (maximumHeadBlocks <= minimumHeadBlocks) {
            throw new IllegalArgumentException("maximumHeadBlocks must be greater than minimumHeadBlocks");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinitePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
