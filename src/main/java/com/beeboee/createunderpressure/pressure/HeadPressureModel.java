package com.beeboee.createunderpressure.pressure;

import java.util.Objects;

/**
 * Standalone head-pressure-to-turbine-output model.
 *
 * <p>The model intentionally stops at gameplay values. Create integration,
 * fluid extraction, overstress handling, and block-entity state belong in
 * separate adapter/content layers.</p>
 */
public final class HeadPressureModel {
    private HeadPressureModel() {}

    public static TurbineOperatingPoint calculate(
        double sourceSurfaceY,
        double turbineY,
        double availableFlowMbPerTick,
        TurbineProfile profile
    ) {
        Objects.requireNonNull(profile, "profile");
        requireFinite("sourceSurfaceY", sourceSurfaceY);
        requireFinite("turbineY", turbineY);
        requireFiniteNonNegative("availableFlowMbPerTick", availableFlowMbPerTick);

        double availableHead = Math.max(0.0, sourceSurfaceY - turbineY);
        if (availableHead <= profile.minimumHeadBlocks() || availableFlowMbPerTick <= 0.0) {
            return TurbineOperatingPoint.stopped(availableHead);
        }

        double usedHead = Math.min(availableHead, profile.maximumHeadBlocks());
        double headRange = profile.maximumHeadBlocks() - profile.minimumHeadBlocks();
        double headFactor = clamp01((usedHead - profile.minimumHeadBlocks()) / headRange);
        double flowFactor = clamp01(availableFlowMbPerTick / profile.designFlowMbPerTick());

        // Speed comes online quickly with modest head, while useful torque
        // remains more dependent on the full head difference.
        double rpm = profile.maximumRpm() * Math.sqrt(headFactor) * flowFactor;
        double stressCapacity = profile.maximumStressCapacity() * headFactor * flowFactor;

        return new TurbineOperatingPoint(
            availableHead,
            usedHead,
            headFactor,
            flowFactor,
            rpm,
            stressCapacity
        );
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
