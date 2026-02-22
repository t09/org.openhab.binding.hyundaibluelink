package org.openhab.binding.hyundaibluelink.internal.api;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Represents a distance measurement with a value and a unit.
 */
class DistanceMeasurement {
    final Double value;
    final DistanceUnit unit;

    DistanceMeasurement(Double value, DistanceUnit unit) {
        this.value = value;
        this.unit = unit;
    }

    static @Nullable DistanceMeasurement inKilometers(Double value) {
        return value == null ? null : new DistanceMeasurement(value, DistanceUnit.KILOMETERS);
    }
}
