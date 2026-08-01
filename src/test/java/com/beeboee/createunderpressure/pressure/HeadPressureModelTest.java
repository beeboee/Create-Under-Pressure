package com.beeboee.createunderpressure.pressure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeadPressureModelTest {
    private static final double EPSILON = 0.0001;

    @Test
    void stopsWithoutPositiveHead() {
        TurbineOperatingPoint result = HeadPressureModel.calculate(10.0, 10.0, 100.0, DefaultTurbineProfiles.WATER);

        assertFalse(result.isRunning());
        assertEquals(0.0, result.rpm(), EPSILON);
        assertEquals(0.0, result.stressCapacity(), EPSILON);
    }

    @Test
    void minimumHeadActsAsADeadZone() {
        TurbineOperatingPoint result = HeadPressureModel.calculate(11.0, 10.0, 100.0, DefaultTurbineProfiles.WATER);

        assertFalse(result.isRunning());
    }

    @Test
    void waterRunsFasterWhileLavaCarriesMoreStress() {
        TurbineOperatingPoint water = HeadPressureModel.calculate(26.0, 10.0, 100.0, DefaultTurbineProfiles.WATER);
        TurbineOperatingPoint lava = HeadPressureModel.calculate(26.0, 10.0, 25.0, DefaultTurbineProfiles.LAVA);

        assertTrue(water.rpm() > lava.rpm());
        assertTrue(lava.stressCapacity() > water.stressCapacity());
    }

    @Test
    void outputClampsAtConfiguredHeadAndFlow() {
        TurbineOperatingPoint result = HeadPressureModel.calculate(100.0, 0.0, 10_000.0, DefaultTurbineProfiles.WATER);

        assertEquals(DefaultTurbineProfiles.WATER.maximumHeadBlocks(), result.usedHeadBlocks(), EPSILON);
        assertEquals(1.0, result.headFactor(), EPSILON);
        assertEquals(1.0, result.flowFactor(), EPSILON);
        assertEquals(DefaultTurbineProfiles.WATER.maximumRpm(), result.rpm(), EPSILON);
        assertEquals(DefaultTurbineProfiles.WATER.maximumStressCapacity(), result.stressCapacity(), EPSILON);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(
            IllegalArgumentException.class,
            () -> HeadPressureModel.calculate(Double.NaN, 0.0, 100.0, DefaultTurbineProfiles.WATER)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HeadPressureModel.calculate(10.0, 0.0, -1.0, DefaultTurbineProfiles.WATER)
        );
    }
}
