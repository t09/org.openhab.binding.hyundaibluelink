package org.openhab.binding.hyundaibluelink.internal.api;

import java.util.Objects;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleLocation;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleStatus;

/**
 * Handler for vehicle status and location retrieval.
 */
@NonNullByDefault
public class BlueLinkStatusHandler {
    private final BlueLinkApi api;

    public BlueLinkStatusHandler(BlueLinkApi api) {
        this.api = Objects.requireNonNull(api);
    }

    public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) throws Exception {
        return api.getVehicleStatusImpl(vehicleId, vinHint, ccs2Supported);
    }

    public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.getVehicleLocationImpl(vehicleId, vin, ccs2Supported);
    }

    public JsonResponse getVehicleMonthlyReport(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.getVehicleMonthlyReportImpl(vehicleId, vin, ccs2Supported);
    }

    public JsonResponse getVehicleMonthlyReportList(String vehicleId, String vin, boolean ccs2Supported)
            throws Exception {
        return api.getVehicleMonthlyReportListImpl(vehicleId, vin, ccs2Supported);
    }

    public JsonResponse getVehicleCcs2CarStatusLatest(String vehicleId, String vinHint) throws Exception {
        return api.getVehicleCcs2CarStatusLatestImpl(vehicleId, vinHint);
    }
}
