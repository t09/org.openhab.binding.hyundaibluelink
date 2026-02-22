package org.openhab.binding.hyundaibluelink.internal.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.AccountBridgeHandler;
import org.openhab.binding.hyundaibluelink.internal.HyundaiBlueLinkBindingConstants;
import org.openhab.binding.hyundaibluelink.internal.model.*;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery service for Hyundai/Kia vehicles associated with an account bridge.
 */
@NonNullByDefault
@Component(service = { DiscoveryService.class,
        ThingHandlerService.class }, configurationPid = "binding.hyundaibluelink")
public class HyundaiBlueLinkDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    @SuppressWarnings("null")
    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Objects.requireNonNull(Set
            .of(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE));

    private final Logger logger = Objects
            .requireNonNull(LoggerFactory.getLogger(HyundaiBlueLinkDiscoveryService.class));

    private @Nullable AccountBridgeHandler accountHandler;
    private @Nullable ScheduledFuture<?> bridgeOnlineRetry;

    public HyundaiBlueLinkDiscoveryService() {
        super(SUPPORTED_THING_TYPES, 10, false);
    }

    @Override
    @SuppressWarnings("null")
    protected void startScan() {
        AccountBridgeHandler handler = accountHandler;
        if (handler == null) {
            logger.debug("Skipping discovery scan because account handler is not set");
            return;
        }

        Bridge bridge = handler.getThing();
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Skipping discovery scan because bridge {} is {}", bridge.getUID(), bridge.getStatus());
            return;
        }

        Instant scanTimestamp = Instant.now();
        removeOlderResults(scanTimestamp, bridge.getUID());

        List<VehicleSummary> vehicles;
        try {
            vehicles = handler.listVehicles();
        } catch (Exception e) {
            logger.warn("Vehicle discovery failed for {}: {}", bridge.getUID(), e.getMessage(), e);
            return;
        }

        for (VehicleSummary summary : vehicles) {
            String vin = summary.vin;
            if (vin == null || vin.isBlank()) {
                logger.debug("Ignoring vehicle without VIN during discovery");
                continue;
            }

            ThingUID bridgeUID = bridge.getUID();
            ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID, vin);
            DiscoveryResultBuilder resultBuilder = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withRepresentationProperty("vin").withProperty("vin", vin);

            if (summary.vehicleId != null && !summary.vehicleId.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, summary.vehicleId);
            }
            if (summary.modelYear != null && !summary.modelYear.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_MODEL_YEAR, summary.modelYear);
            }
            if (summary.licensePlate != null && !summary.licensePlate.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_LICENSE_PLATE,
                        summary.licensePlate);
            }
            if (summary.type != null && !summary.type.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_TYPE, summary.type);
            }
            if (summary.inColor != null && !summary.inColor.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_IN_COLOR, summary.inColor);
            }
            if (summary.outColor != null && !summary.outColor.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_OUT_COLOR, summary.outColor);
            }
            if (summary.saleCarmdlCd != null && !summary.saleCarmdlCd.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_SALE_CARMDL_CD,
                        summary.saleCarmdlCd);
            }
            if (summary.bodyType != null && !summary.bodyType.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_BODY_TYPE, summary.bodyType);
            }
            if (summary.saleCarmdlEnNm != null && !summary.saleCarmdlEnNm.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_SALE_CARMDL_EN_NM,
                        summary.saleCarmdlEnNm);
            }
            if (summary.protocolType != null && !summary.protocolType.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_PROTOCOL_TYPE,
                        summary.protocolType);
            }
            if (summary.ccuCCS2ProtocolSupport != null && !summary.ccuCCS2ProtocolSupport.isBlank()) {
                resultBuilder.withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_CCU_CCS2_PROTOCOL_SUPPORT,
                        summary.ccuCCS2ProtocolSupport);
            }

            String label = summary.label;
            if (label == null || label.isBlank()) {
                label = vin;
            }
            resultBuilder.withLabel(label);

            thingDiscovered(resultBuilder.build());
        }
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof AccountBridgeHandler accountBridgeHandler) {
            accountHandler = accountBridgeHandler;
            if (logger.isTraceEnabled()) {
                logger.trace("Account bridge handler {} injected into discovery service", handler.getThing().getUID());
            }
            triggerScanWhenBridgeOnline(accountBridgeHandler);
        }
    }

    public void unsetThingHandler(ThingHandler handler) {
        if (handler == accountHandler) {
            cancelBridgeOnlineRetry();
            accountHandler = null;
        }
    }

    public void thingHandlerDisposed(ThingHandler handler, ThingStatusInfo info) {
        if (handler == accountHandler) {
            cancelBridgeOnlineRetry();
            accountHandler = null;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return accountHandler;
    }

    @Override
    public void deactivate() {
        super.deactivate();
        cancelBridgeOnlineRetry();
        accountHandler = null;
    }

    private void triggerScanWhenBridgeOnline(AccountBridgeHandler handler) {
        Bridge bridge = handler.getThing();

        if (bridge.getStatus() == ThingStatus.ONLINE) {
            scheduler.execute(this::startScan);
            return;
        }

        ScheduledFuture<?> future = bridgeOnlineRetry;
        if (future == null || future.isDone()) {
            bridgeOnlineRetry = scheduler.schedule(this::retryScanWhenBridgeOnline, 2, TimeUnit.SECONDS);
        }
    }

    private void retryScanWhenBridgeOnline() {
        bridgeOnlineRetry = null;
        AccountBridgeHandler handler = accountHandler;
        if (handler == null) {
            return;
        }

        Bridge bridge = handler.getThing();
        if (bridge.getStatus() == ThingStatus.ONLINE) {
            startScan();
        } else {
            triggerScanWhenBridgeOnline(handler);
        }
    }

    private void cancelBridgeOnlineRetry() {
        ScheduledFuture<?> future = bridgeOnlineRetry;
        if (future != null) {
            future.cancel(true);
            bridgeOnlineRetry = null;
        }
    }
}
