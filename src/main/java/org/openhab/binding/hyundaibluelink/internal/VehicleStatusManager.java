package org.openhab.binding.hyundaibluelink.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.measure.Unit;
import javax.measure.quantity.Length;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.api.DistanceUnit;
import org.openhab.binding.hyundaibluelink.internal.api.JsonResponse;
import org.openhab.binding.hyundaibluelink.internal.api.Reservation;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleLocation;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleStatus;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for refreshing and applying vehicle status updates.
 */
@NonNullByDefault
public class VehicleStatusManager {
    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(VehicleStatusManager.class));
    private final HyundaiBlueLinkVehicleHandler handler;
    private final Object refreshLock = new Object();

    public VehicleStatusManager(HyundaiBlueLinkVehicleHandler handler) {
        this.handler = handler;
    }

    public void refreshVehicleData() {
        if (handler.isDisposed()) {
            return;
        }
        handler.ensureRefreshScheduleUpToDate();
        if (handler.shouldDeferRefreshDuringCommandPoll()) {
            return;
        }
        if (!handler.ensureApi()) {
            logger.debug("Skipping refresh for {} because API is not ready", handler.getThing().getUID());
            handler.scheduleRefreshTask(this::refreshVehicleData, 30, TimeUnit.SECONDS);
            return;
        }

        String vin = handler.getThing().getUID().getId();
        String vehicleId = handler.resolveVehicleId(vin);
        if (vehicleId == null || vehicleId.isBlank()) {
            logger.debug("Skipping refresh for {} because vehicle ID cannot be resolved (yet)", vin);
            return;
        }

        synchronized (refreshLock) {
            VehicleStatus status;
            try {
                BlueLinkApi activeApi = Objects.requireNonNull(handler.getApi());
                status = activeApi.getVehicleStatus(vehicleId, vin, handler.isCcs2Supported());
            } catch (Exception e) {
                logger.warn("Vehicle status refresh failed for {}: {}", vin, e.getMessage());
                markStatusChannelsError(e.getMessage());
                return;
            }

            applyVehicleStatus(status);

            if (status != null) {
                Double statusLatitude = status.latitude;
                Double statusLongitude = status.longitude;
                if (statusLatitude != null && statusLongitude != null) {
                    VehicleLocation location = new VehicleLocation();
                    location.latitude = statusLatitude.doubleValue();
                    location.longitude = statusLongitude.doubleValue();
                    updateLocationChannel(location);
                } else {
                    try {
                        BlueLinkApi activeApi = Objects.requireNonNull(handler.getApi());
                        VehicleLocation location = activeApi.getVehicleLocation(vehicleId, vin,
                                handler.isCcs2Supported());
                        updateLocationChannel(location);
                    } catch (Exception e) {
                        logger.warn("Vehicle location refresh failed for {}: {}", vin, e.getMessage());
                        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_LOCATION, UnDefType.UNDEF);
                    }
                }

                // Fetch Reservation Status
                try {
                    BlueLinkApi activeApi = Objects.requireNonNull(handler.getApi());
                    Reservation reservation = activeApi.getReservation(vehicleId, vin, handler.isCcs2Supported());
                    applyReservationStatus(reservation);
                } catch (Exception e) {
                    logger.debug("Reservation status refresh failed for {}: {}", vin, e.getMessage());
                    // Don't mark everything as error, just undef reservation channels
                    updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE, UnDefType.UNDEF);
                    updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME, UnDefType.UNDEF);
                    updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST, UnDefType.UNDEF);
                }
            }
        }
    }

    public void performInitialVehicleBootstrapPolls() {
        if (handler.isDisposed()) {
            return;
        }
        if (!handler.ensureApi()) {
            logger.debug("API is not ready for initial data poll on {}", handler.getThing().getUID());
            handler.scheduleRefreshTask(this::performInitialVehicleBootstrapPolls, 30, TimeUnit.SECONDS);
            return;
        }

        String vin = handler.getThing().getUID().getId();
        String vehicleId = handler.resolveVehicleId(vin);
        if (vehicleId == null || vehicleId.isBlank()) {
            logger.warn("Unable to resolve vehicle ID for {} during initial data poll", handler.getThing().getUID());
            handler.scheduleRefreshTask(this::performInitialVehicleBootstrapPolls, 30, TimeUnit.SECONDS);
            return;
        }

        BlueLinkApi activeApi = Objects.requireNonNull(handler.getApi());
        try {
            if (handler.isCcs2Supported()) {
                JsonResponse ccs2Response = activeApi.getVehicleCcs2CarStatusLatest(vehicleId, vin);
                if (ccs2Response != null) {
                    if (ccs2Response.isSuccessful()) {
                        logger.info("Initial CCS2 car status response for {} (HTTP {}): {}", vin,
                                Integer.valueOf(ccs2Response.getStatusCode()), ccs2Response.getBodyForLog());
                    } else if (ccs2Response.isClientError()) {
                        logger.info(
                                "CCS2 car status endpoint unavailable for {} (HTTP {}), falling back to legacy status",
                                vin, Integer.valueOf(ccs2Response.getStatusCode()));
                        JsonResponse legacyResponse = activeApi.getVehicleStatusLatestRaw(vehicleId, vin, false,
                                handler.isCcs2Supported());
                        if (legacyResponse != null) {
                            if (legacyResponse.isSuccessful()) {
                                logger.info("Initial legacy vehicle status response for {} (HTTP {}): {}", vin,
                                        Integer.valueOf(legacyResponse.getStatusCode()),
                                        legacyResponse.getBodyForLog());
                            } else {
                                logger.warn("Legacy vehicle status request for {} returned HTTP {}: {}", vin,
                                        Integer.valueOf(legacyResponse.getStatusCode()),
                                        legacyResponse.getBodyForLog());
                            }
                        }
                    } else {
                        logger.warn("Initial CCS2 car status request for {} returned HTTP {}: {}", vin,
                                Integer.valueOf(ccs2Response.getStatusCode()), ccs2Response.getBodyForLog());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Initial CCS2 car status request for {} failed: {}", vin, e.getMessage());
        }

        try {
            if (handler.isCcs2Supported()) {
                JsonResponse monthlyReport = activeApi.getVehicleMonthlyReport(vehicleId, vin,
                        handler.isCcs2Supported());
                if (monthlyReport != null) {
                    if (monthlyReport.isSuccessful()) {
                        logger.info("Initial monthly report response for {} (HTTP {}): {}", vin,
                                Integer.valueOf(monthlyReport.getStatusCode()), monthlyReport.getBodyForLog());
                    } else if (monthlyReport.isClientError()) {
                        logger.info("Monthly report endpoint unavailable for {} (HTTP {})", vin,
                                Integer.valueOf(monthlyReport.getStatusCode()));
                    } else {
                        logger.warn("Monthly report request for {} returned HTTP {}: {}", vin,
                                Integer.valueOf(monthlyReport.getStatusCode()), monthlyReport.getBodyForLog());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Initial monthly report request for {} failed: {}", vin, e.getMessage());
        }
    }

    private void applyVehicleStatus(VehicleStatus status) {
        if (status.doorsLocked != null) {
            handler.updateLockState(status.doorsLocked.booleanValue() ? OnOffType.ON : OnOffType.OFF);
        } else {
            handler.updateLockState(UnDefType.UNDEF);
        }

        if (status.charging != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_STARTCHARGE,
                    status.charging ? OnOffType.ON : OnOffType.OFF);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_STARTCHARGE, UnDefType.UNDEF);
        }

        Integer chargingState = status.chargingState;
        if (chargingState != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGING_STATE, new DecimalType(chargingState));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGING_STATE, UnDefType.UNDEF);
        }

        Integer remainTime = status.remainingChargeTimeMinutes;
        if (remainTime != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_REMAINING_CHARGE_TIME,
                    new QuantityType<>(remainTime, Units.MINUTE));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_REMAINING_CHARGE_TIME, UnDefType.UNDEF);
        }

        if (status.connectorFastened != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CONNECTOR_FASTENED,
                    status.connectorFastened ? OnOffType.ON : OnOffType.OFF);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CONNECTOR_FASTENED, UnDefType.UNDEF);
        }

        if (status.climateOn != null) {
            OnOffType climateState = status.climateOn ? OnOffType.ON : OnOffType.OFF;
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_STATUS, climateState);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL, climateState);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_STATUS, UnDefType.UNDEF);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL, UnDefType.UNDEF);
        }

        if (status.batteryWarning != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_WARNING,
                    status.batteryWarning ? OnOffType.ON : OnOffType.OFF);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_WARNING, UnDefType.UNDEF);
        }

        if (status.acc != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_ACC,
                    status.acc ? OnOffType.ON : OnOffType.OFF);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_ACC, UnDefType.UNDEF);
        }

        if (status.minorWarnings != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_MINOR_WARNINGS, new StringType(status.minorWarnings));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_MINOR_WARNINGS, UnDefType.UNDEF);
        }

        if (status.lastNotification != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_LAST_NOTIFICATION,
                    new StringType(status.lastNotification));
        }

        Thing thing = handler.getThing();
        ThingBuilder distanceChannelBuilder = handler.editThing();
        List<Channel> updatedChannels = new ArrayList<>(thing.getChannels());
        boolean distanceChannelTypeUpdated = false;

        distanceChannelTypeUpdated |= updateDistanceChannelUnit(updatedChannels, status.odometerUnit,
                HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER,
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_ODOMETER_KILOMETRES),
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_ODOMETER_MILES));
        Double odometer = status.odometer;
        if (odometer != null) {
            QuantityType<Length> odometerQuantity = new QuantityType<>(odometer,
                    resolveDistanceUnit(status.odometerUnit));
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER, odometerQuantity);
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER, UnDefType.UNDEF);
        }

        Double batteryLevel = status.batteryLevel;
        if (batteryLevel != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_LEVEL,
                    new QuantityType<>(batteryLevel, Units.PERCENT));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_LEVEL, UnDefType.UNDEF);
        }

        Double chargeLimitAc = status.chargeLimitAC;
        if (chargeLimitAc != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC,
                    new QuantityType<>(chargeLimitAc, Units.PERCENT));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC, UnDefType.UNDEF);
        }

        Double chargeLimitDc = status.chargeLimitDC;
        if (chargeLimitDc != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC,
                    new QuantityType<>(chargeLimitDc, Units.PERCENT));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC, UnDefType.UNDEF);
        }

        distanceChannelTypeUpdated |= updateDistanceChannelUnit(updatedChannels, status.rangeUnit,
                HyundaiBlueLinkBindingConstants.CHANNEL_RANGE,
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_RANGE_KILOMETRES),
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_RANGE_MILES));
        Double range = status.range;
        if (range != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE,
                    new QuantityType<>(range, resolveDistanceUnit(status.rangeUnit)));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE, UnDefType.UNDEF);
        }

        distanceChannelTypeUpdated |= updateDistanceChannelUnit(updatedChannels, status.evModeRangeUnit,
                HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE,
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_EV_RANGE_KILOMETRES),
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_EV_RANGE_MILES));
        Double evModeRange = status.evModeRange;
        if (evModeRange != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE,
                    new QuantityType<>(evModeRange, resolveDistanceUnit(status.evModeRangeUnit)));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE, UnDefType.UNDEF);
        }

        distanceChannelTypeUpdated |= updateDistanceChannelUnit(updatedChannels, status.gasModeRangeUnit,
                HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE,
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_GAS_RANGE_KILOMETRES),
                Objects.requireNonNull(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_GAS_RANGE_MILES));
        Double gasModeRange = status.gasModeRange;
        if (gasModeRange != null) {
            Unit<Length> unit = resolveDistanceUnit(status.gasModeRangeUnit);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE, new QuantityType<>(gasModeRange, unit));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE, UnDefType.UNDEF);
        }

        if (distanceChannelTypeUpdated) {
            distanceChannelBuilder.withChannels(updatedChannels);
            handler.updateThing(distanceChannelBuilder.build());
        }

        Double fuelLevel = status.fuelLevel;
        if (fuelLevel != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_FUEL_LEVEL,
                    new QuantityType<>(fuelLevel, Units.PERCENT));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_FUEL_LEVEL, UnDefType.UNDEF);
        }

        Instant lastUpdated = status.lastUpdated;
        if (lastUpdated != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_LAST_UPDATED,
                    new DateTimeType(Objects.requireNonNull(lastUpdated.atZone(ZoneOffset.UTC))));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_LAST_UPDATED, UnDefType.UNDEF);
        }

        if (status.doorStatusSummary != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_DOOR_STATUS, new StringType(status.doorStatusSummary));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_DOOR_STATUS, UnDefType.UNDEF);
        }

        if (status.windowStatusSummary != null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_WINDOW_STATUS,
                    new StringType(status.windowStatusSummary));
        } else {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_WINDOW_STATUS, UnDefType.UNDEF);
        }

        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_STATUS, new StringType(buildStatusSummary(status)));

    }

    private void updateLocationChannel(@Nullable VehicleLocation location) {
        if (location == null) {
            return;
        }
        if (Double.isNaN(location.latitude) || Double.isNaN(location.longitude)) {
            return;
        }
        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_LOCATION,
                new PointType(new DecimalType(location.latitude), new DecimalType(location.longitude)));
    }

    private void markStatusChannelsError(@Nullable String message) {
        String detail = (message == null || message.isBlank()) ? "Unknown error" : message;
        StringType error = new StringType("ERROR: " + detail);
        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_STATUS, error);
    }

    private String buildStatusSummary(VehicleStatus status) {
        StringBuilder sb = new StringBuilder();
        appendKeyValue(sb, "locked", status.doorsLocked);
        appendKeyValue(sb, "charging", status.charging);
        appendKeyValue(sb, "chargingState", status.chargingState);
        appendKeyValue(sb, "remainCharge", status.remainingChargeTimeMinutes);
        appendKeyValue(sb, "connectorFastened", status.connectorFastened);
        appendKeyValue(sb, "climate", status.climateOn);
        appendKeyValue(sb, "batteryWarning", status.batteryWarning);
        appendKeyValue(sb, "lowFuelLight", status.lowFuelLight);
        appendKeyValue(sb, "limitAC", status.chargeLimitAC);
        appendKeyValue(sb, "limitDC", status.chargeLimitDC);
        if (status.doorStatusSummary != null) {
            appendKeyValue(sb, "doors", status.doorStatusSummary);
        }
        if (status.windowStatusSummary != null) {
            appendKeyValue(sb, "windows", status.windowStatusSummary);
        }
        if (status.range != null) {
            appendKeyValue(sb, "range", formatDistance(status.range, status.rangeUnit));
        }
        if (status.odometer != null) {
            appendKeyValue(sb, "odometer", formatDistance(status.odometer, status.odometerUnit));
        }
        if (status.evModeRange != null) {
            appendKeyValue(sb, "evModeRange", formatDistance(status.evModeRange, status.evModeRangeUnit));
        }
        if (status.gasModeRange != null) {
            appendKeyValue(sb, "gasModeRange", formatDistance(status.gasModeRange, status.gasModeRangeUnit));
        }
        if (status.batteryLevel != null) {
            appendKeyValue(sb, "batteryLevel", status.batteryLevel);
        }
        if (status.fuelLevel != null) {
            appendKeyValue(sb, "fuelLevel", status.fuelLevel);
        }
        if (status.lastUpdated != null) {
            appendKeyValue(sb, "updated", status.lastUpdated);
        }
        return Objects.requireNonNull(sb.length() > 0 ? sb.toString() : "{}");
    }

    private Unit<Length> resolveDistanceUnit(@Nullable DistanceUnit unit) {
        if (unit == DistanceUnit.MILES) {
            return ImperialUnits.MILE;
        }
        return MetricPrefix.KILO(SIUnits.METRE);
    }

    private @Nullable String formatDistance(@Nullable Double value, @Nullable DistanceUnit unit) {
        if (value == null) {
            return null;
        }
        if (unit == null) {
            return value.toString();
        }
        return value + " " + unit.getDisplay();
    }

    private void appendKeyValue(StringBuilder sb, String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(value);
    }

    private boolean updateDistanceChannelUnit(List<Channel> channels, @Nullable DistanceUnit unit, String channelId,
            ChannelTypeUID kilometreType, ChannelTypeUID mileType) {
        if (unit == null) {
            return false;
        }
        ChannelTypeUID desiredType = unit == DistanceUnit.MILES ? mileType : kilometreType;
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = Objects.requireNonNull(channels.get(i));
            if (!channel.getUID().getId().equals(channelId)) {
                continue;
            }
            ChannelTypeUID currentType = channel.getChannelTypeUID();
            if (desiredType.equals(currentType)) {
                return false;
            }
            channels.set(i, ChannelBuilder.create(channel).withType(desiredType).build());
            return true;
        }
        return false;
    }

    private void applyReservationStatus(@Nullable Reservation reservation) {
        if (reservation == null) {
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE, UnDefType.UNDEF);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME, UnDefType.UNDEF);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST, UnDefType.UNDEF);
            return;
        }

        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE,
                reservation.active ? OnOffType.ON : OnOffType.OFF);
        updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST,
                reservation.defrost ? OnOffType.ON : OnOffType.OFF);

        // Construct DateTime for TODAY with the given Hour/Minute
        // Note: The API returns scheduling hour/minute (e.g. 07:30).
        // It's a recurring daily schedule usually.
        // Showing it as a DateTime today allows user to see the time easily.
        try {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
            java.time.ZonedDateTime departure = now.withHour(reservation.hour).withMinute(reservation.minute)
                    .withSecond(0).withNano(0);
            updateState(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME,
                    new DateTimeType(Objects.requireNonNull(departure)));
        } catch (Exception e) {
            logger.warn("Failed to construct reservation time from {}:{}", reservation.hour, reservation.minute);
        }
    }

    private void updateState(String channelId, State state) {
        handler.updateState(new ChannelUID(handler.getThing().getUID(), channelId), state);
    }
}
