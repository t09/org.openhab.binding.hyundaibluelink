package org.openhab.binding.hyundaibluelink.internal.api.mapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleStatus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Maps the new Hyundai/Kia CCS2 telemetry protocol responses (e.g. from
 * /ccs2/carstatus/latest)
 * into the standard {@link VehicleStatus} object.
 */
public class Ccs2StatusMapper {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("UTC"));

    public static VehicleStatus map(String vin, JsonObject ccs2) {
        VehicleStatus s = new VehicleStatus();
        s.vin = vin;

        // Odometer
        Double odo = optDouble(ccs2, "Drivetrain", "Odometer");
        if (odo != null) {
            s.odometer = odo;
        }

        // Fuel / Range for ICE / PHEV
        Double fuelLevel = optDouble(ccs2, "Drivetrain", "FuelSystem", "FuelLevel");
        if (fuelLevel != null) {
            s.fuelLevel = fuelLevel;
        }

        Double totalRange = optDouble(ccs2, "Drivetrain", "FuelSystem", "DTE", "Total");
        if (totalRange != null) {
            s.range = totalRange;
            // EV range fallback to DTE total if it exists
            s.evModeRange = totalRange;
        }

        // DTE TargetSoC Standard/Quick (from Green component for EVs)
        Double evTargetRangeAc = optDouble(ccs2, "Green", "ChargingInformation", "DTE", "TargetSoC", "Standard");
        if (evTargetRangeAc != null) {
            s.range = evTargetRangeAc;
            s.evModeRange = evTargetRangeAc;
        }

        // Charge Limits (Target SoC)
        Double targetSoCAc = optDouble(ccs2, "Green", "ChargingInformation", "TargetSoC", "Standard");
        if (targetSoCAc != null) {
            s.chargeLimitAC = targetSoCAc;
        }
        Double targetSoCDc = optDouble(ccs2, "Green", "ChargingInformation", "TargetSoC", "Quick");
        if (targetSoCDc != null) {
            s.chargeLimitDC = targetSoCDc;
        }

        // Auxiliary Battery
        Double auxBattery = optDouble(ccs2, "Electronics", "Battery", "Level");
        if (auxBattery != null) {
            s.auxiliaryBatteryLevel = auxBattery;
        }

        // Main EV Battery
        Double evBattery = optDouble(ccs2, "Green", "BatteryManagement", "BatteryRemain", "Ratio");
        if (evBattery != null) {
            s.batteryLevel = evBattery;
        }

        // Ignition / Engine
        Boolean drivingReady = optBoolean(ccs2, "DrivingReady");
        if (drivingReady != null) {
            s.acc = drivingReady;
        }

        // Climate (HVAC)
        Double airTemp = optDouble(ccs2, "Cabin", "HVAC", "Row1", "Driver", "Temperature", "Value");
        if (airTemp != null) {
            s.climateOn = true;
        } else {
            // Check if string is OFF
            String tempStr = optString(ccs2, "Cabin", "HVAC", "Row1", "Driver", "Temperature", "Value");
            if ("OFF".equals(tempStr)) {
                s.climateOn = false;
            }
        }

        // Doors & Locks
        Boolean frontLeftLock = optBoolean(ccs2, "Cabin", "Door", "Row1", "Driver", "Lock");
        Boolean frontRightLock = optBoolean(ccs2, "Cabin", "Door", "Row1", "Passenger", "Lock");
        Boolean rearLeftLock = optBoolean(ccs2, "Cabin", "Door", "Row2", "Left", "Lock");
        Boolean rearRightLock = optBoolean(ccs2, "Cabin", "Door", "Row2", "Right", "Lock");

        if (frontLeftLock != null && frontRightLock != null && rearLeftLock != null && rearRightLock != null) {
            s.doorsLocked = frontLeftLock && frontRightLock && rearLeftLock && rearRightLock;
        }

        // Doors Open
        Boolean flOpen = optBoolean(ccs2, "Cabin", "Door", "Row1", "Driver", "Open");
        Boolean frOpen = optBoolean(ccs2, "Cabin", "Door", "Row1", "Passenger", "Open");
        Boolean rlOpen = optBoolean(ccs2, "Cabin", "Door", "Row2", "Left", "Open");
        Boolean rrOpen = optBoolean(ccs2, "Cabin", "Door", "Row2", "Right", "Open");
        Boolean trunkOpen = optBoolean(ccs2, "Body", "Trunk", "Open");
        Boolean hoodOpen = optBoolean(ccs2, "Body", "Hood", "Open");

        List<String> openDoors = new ArrayList<>();
        if (Boolean.TRUE.equals(flOpen))
            openDoors.add("Front Left");
        if (Boolean.TRUE.equals(frOpen))
            openDoors.add("Front Right");
        if (Boolean.TRUE.equals(rlOpen))
            openDoors.add("Rear Left");
        if (Boolean.TRUE.equals(rrOpen))
            openDoors.add("Rear Right");
        if (Boolean.TRUE.equals(trunkOpen))
            openDoors.add("Trunk");
        if (Boolean.TRUE.equals(hoodOpen))
            openDoors.add("Hood");

        if (!openDoors.isEmpty()) {
            s.doorStatusSummary = String.join(", ", openDoors);
        } else {
            s.doorStatusSummary = "Closed";
        }

        // Windows Open
        Boolean flwOpen = optBoolean(ccs2, "Cabin", "Window", "Row1", "Driver", "Open");
        Boolean frwOpen = optBoolean(ccs2, "Cabin", "Window", "Row1", "Passenger", "Open");
        Boolean rlwOpen = optBoolean(ccs2, "Cabin", "Window", "Row2", "Left", "Open");
        Boolean rrwOpen = optBoolean(ccs2, "Cabin", "Window", "Row2", "Right", "Open");
        Boolean sunroofOpen = optBoolean(ccs2, "Body", "Sunroof", "Glass", "Open");

        List<String> openWindows = new ArrayList<>();
        if (Boolean.TRUE.equals(flwOpen))
            openWindows.add("Front Left");
        if (Boolean.TRUE.equals(frwOpen))
            openWindows.add("Front Right");
        if (Boolean.TRUE.equals(rlwOpen))
            openWindows.add("Rear Left");
        if (Boolean.TRUE.equals(rrwOpen))
            openWindows.add("Rear Right");
        if (Boolean.TRUE.equals(sunroofOpen))
            openWindows.add("Sunroof");

        if (!openWindows.isEmpty()) {
            s.windowStatusSummary = String.join(", ", openWindows);
        } else {
            s.windowStatusSummary = "Closed";
        }

        // Charging
        // In CCS2, ChargingInformation.ElectricCurrentLevel.State (0=None),
        // ConnectorFastening.State (bool)
        Boolean connectorFastened = optBoolean(ccs2, "Green", "ChargingInformation", "ConnectorFastening", "State");
        if (connectorFastened != null) {
            s.connectorFastened = connectorFastened;
        }
        Integer remainTime = optInteger(ccs2, "Green", "ChargingInformation", "Charging", "RemainTime");
        if (remainTime != null && remainTime > 0) {
            s.remainingChargeTimeMinutes = remainTime;
            s.charging = true;
            s.chargingState = 1;
        } else if (remainTime != null) {
            s.charging = false;
            s.chargingState = 0;
            s.remainingChargeTimeMinutes = 0;
        }

        // Tires
        List<String> tireWarnings = new ArrayList<>();
        if (Boolean.TRUE.equals(optBoolean(ccs2, "Chassis", "Axle", "Row1", "Left", "Tire", "PressureLow"))) {
            tireWarnings.add("Front Left");
        }
        if (Boolean.TRUE.equals(optBoolean(ccs2, "Chassis", "Axle", "Row1", "Right", "Tire", "PressureLow"))) {
            tireWarnings.add("Front Right");
        }
        if (Boolean.TRUE.equals(optBoolean(ccs2, "Chassis", "Axle", "Row2", "Left", "Tire", "PressureLow"))) {
            tireWarnings.add("Rear Left");
        }
        if (Boolean.TRUE.equals(optBoolean(ccs2, "Chassis", "Axle", "Row2", "Right", "Tire", "PressureLow"))) {
            tireWarnings.add("Rear Right");
        }

        if (!tireWarnings.isEmpty()) {
            s.minorWarnings = "Low Tire Pressure: " + String.join(", ", tireWarnings);
        }

        // Location
        Double lat = optDouble(ccs2, "Location", "GeoCoord", "Latitude");
        Double lon = optDouble(ccs2, "Location", "GeoCoord", "Longitude");
        if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
            s.latitude = lat;
            s.longitude = lon;
        }

        // System Date
        String dateStr = optString(ccs2, "Date");
        if (dateStr != null && dateStr.length() >= 14) {
            try {
                // e.g., 20240915140000 -> Instant
                s.lastUpdated = Instant.from(DATE_FORMAT.parse(dateStr));
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        return s;
    }

    private static @Nullable JsonElement getPath(JsonObject obj, String... path) {
        JsonObject current = obj;
        for (int i = 0; i < path.length - 1; i++) {
            if (current == null || !current.has(path[i]) || !current.get(path[i]).isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject(path[i]);
        }
        if (current == null || !current.has(path[path.length - 1])) {
            return null;
        }
        JsonElement el = current.get(path[path.length - 1]);
        return el.isJsonNull() ? null : el;
    }

    private static @Nullable Double optDouble(JsonObject obj, String... path) {
        JsonElement el = getPath(obj, path);
        try {
            return el != null ? el.getAsDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Integer optInteger(JsonObject obj, String... path) {
        JsonElement el = getPath(obj, path);
        try {
            return el != null ? el.getAsInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Boolean optBoolean(JsonObject obj, String... path) {
        JsonElement el = getPath(obj, path);
        try {
            if (el != null && el.isJsonPrimitive()) {
                if (el.getAsJsonPrimitive().isBoolean()) {
                    return el.getAsBoolean();
                } else if (el.getAsJsonPrimitive().isNumber()) {
                    return el.getAsInt() != 0;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable String optString(JsonObject obj, String... path) {
        JsonElement el = getPath(obj, path);
        try {
            return el != null ? el.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
