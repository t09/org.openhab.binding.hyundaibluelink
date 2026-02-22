package org.openhab.binding.hyundaibluelink.internal;

import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for resolving and persisting the BlueLink Vehicle ID.
 */
@NonNullByDefault
public class VehicleIdResolver {

    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(VehicleIdResolver.class));
    private final HyundaiBlueLinkVehicleHandler handler;
    private final Object vehicleIdLock = new Object();
    private @Nullable volatile String cachedVehicleId;
    private volatile boolean vehicleIdFallbackLogged;

    public VehicleIdResolver(HyundaiBlueLinkVehicleHandler handler) {
        this.handler = handler;
        this.cachedVehicleId = getStoredVehicleId();
    }

    public String resolveVehicleId(String vin) {
        String vehicleId = cachedVehicleId;
        if (vehicleId != null && !vehicleId.isBlank()) {
            return vehicleId;
        }

        synchronized (vehicleIdLock) {
            vehicleId = cachedVehicleId;
            if (vehicleId != null && !vehicleId.isBlank()) {
                return vehicleId;
            }

            vehicleId = getStoredVehicleId();
            if (vehicleId != null && !vehicleId.isBlank()) {
                cachedVehicleId = vehicleId;
                return vehicleId;
            }

            vehicleId = discoverVehicleIdFromBridge(vin);
            if (vehicleId != null && !vehicleId.isBlank()) {
                cachedVehicleId = vehicleId;
                persistVehicleId(vehicleId);
                return vehicleId;
            }
        }

        if (!vehicleIdFallbackLogged) {
            vehicleIdFallbackLogged = true;
            logger.warn(
                    "Vehicle {} is missing a BlueLink vehicle UUID. Falling back to VIN which may fail; configure '{}' in the Thing properties or rediscover the vehicle.",
                    handler.getThing().getUID(), HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID);
        }
        return vin;
    }

    public @Nullable String getCachedVehicleId() {
        return cachedVehicleId;
    }

    public void setCachedVehicleId(@Nullable String vehicleId) {
        this.cachedVehicleId = vehicleId;
    }

    public void resetFallbackLogged() {
        this.vehicleIdFallbackLogged = false;
    }

    private @Nullable String getStoredVehicleId() {
        @Nullable
        String propertyValue = handler.getThing().getProperties()
                .get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        Configuration configuration = handler.getThing().getConfiguration();
        Object configured = configuration.get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID);
        if (configured instanceof String configuredId && !configuredId.isBlank()) {
            return configuredId;
        }

        return null;
    }

    private @Nullable String discoverVehicleIdFromBridge(String vin) {
        AccountBridgeHandler bridgeHandler = handler.getAccountBridgeHandler();
        if (bridgeHandler == null) {
            return null;
        }

        try {
            for (org.openhab.binding.hyundaibluelink.internal.model.VehicleSummary summary : bridgeHandler
                    .listVehicles()) {
                String summaryVin = summary.vin;
                if (summaryVin != null && summaryVin.equalsIgnoreCase(vin)) {
                    String summaryVehicleId = summary.vehicleId;
                    if (summaryVehicleId != null && !summaryVehicleId.isBlank()) {
                        logger.debug("Resolved vehicle {} to UUID {} via bridge", vin, summaryVehicleId);
                        return summaryVehicleId;
                    }
                }
            }
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            logger.warn("Failed to resolve BlueLink vehicle UUID for {} via bridge: {}", vin, detail);
        }
        return null;
    }

    private void persistVehicleId(String vehicleId) {
        if (vehicleId.isBlank()) {
            return;
        }
        @SuppressWarnings("null")
        String current = handler.getThing().getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID);
        if (vehicleId.equals(current)) {
            return;
        }
        handler.updateThing(
                handler.editThing().withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, vehicleId)
                        .build());
    }
}
