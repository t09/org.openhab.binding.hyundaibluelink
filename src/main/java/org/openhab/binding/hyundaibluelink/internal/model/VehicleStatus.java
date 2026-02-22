package org.openhab.binding.hyundaibluelink.internal.model;

import java.time.Instant;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.DistanceUnit;

@NonNullByDefault
public class VehicleStatus {
    public @Nullable String vin;
    public @Nullable Double batteryLevel;
    public @Nullable Double range;
    public @Nullable Double evModeRange;
    public @Nullable Double gasModeRange;
    public @Nullable Double odometer;
    public @Nullable Double fuelLevel;

    public @Nullable DistanceUnit rangeUnit;
    public @Nullable DistanceUnit odometerUnit;
    public @Nullable DistanceUnit evModeRangeUnit;
    public @Nullable DistanceUnit gasModeRangeUnit;

    public @Nullable Double auxiliaryBatteryLevel;
    public @Nullable Boolean doorsLocked;
    public @Nullable Boolean engineOn;
    public @Nullable Boolean trunkOpen;
    public @Nullable Boolean hoodOpen;
    public @Nullable Boolean charging;
    public @Nullable Integer chargingState;
    public @Nullable Integer remainingChargeTimeMinutes;
    public @Nullable Boolean connectorFastened;
    public @Nullable String doorStatusSummary;
    public @Nullable String windowStatusSummary;
    public @Nullable Boolean climateOn;
    public @Nullable Boolean acc;
    public @Nullable String minorWarnings;
    public @Nullable Boolean batteryWarning;
    public @Nullable Boolean lowFuelLight;

    public @Nullable String lastNotification;
    public @Nullable Instant lastUpdated;
    public @Nullable Double latitude;
    public @Nullable Double longitude;
    public @Nullable Double chargeLimitAC;
    public @Nullable Double chargeLimitDC;
}
