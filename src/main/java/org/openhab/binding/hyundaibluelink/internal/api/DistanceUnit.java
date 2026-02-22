package org.openhab.binding.hyundaibluelink.internal.api;

/**
 * Enumeration of distance units used by the BlueLink API.
 */
public enum DistanceUnit {
    KILOMETERS("km"),
    MILES("mi");

    private final String display;

    DistanceUnit(String display) {
        this.display = display;
    }

    public String getDisplay() {
        return display;
    }

    public static DistanceUnit fromApiUnit(Integer unit) {
        return switch (unit.intValue()) {
            case 0, 2, 3 -> MILES;
            default -> KILOMETERS;
        };
    }
}
