package org.openhab.binding.hyundaibluelink.internal.api;

import java.util.Objects;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandResponse;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandRequest;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Handler for generic vehicle commands like lock, unlock, and charging control.
 */
@NonNullByDefault
public class BlueLinkCommandHandler {
    private final BlueLinkApi api;

    public BlueLinkCommandHandler(BlueLinkApi api) {
        this.api = Objects.requireNonNull(api);
    }

    public VehicleCommandResponse lock(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.sendVehicleCommand(vehicleId, vin, "lock", ccs2Supported);
    }

    public VehicleCommandResponse unlock(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.sendVehicleCommand(vehicleId, vin, "unlock", ccs2Supported);
    }

    public VehicleCommandResponse startCharge(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.sendVehicleCommand(vehicleId, vin, "startCharge", ccs2Supported);
    }

    public VehicleCommandResponse stopCharge(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.sendVehicleCommand(vehicleId, vin, "stopCharge", ccs2Supported);
    }

    public VehicleCommandResponse setChargeLimit(String vehicleId, String vin, int limitAC, int limitDC,
            boolean ccs2Supported) throws Exception {
        String controlTokenValue = api.ensureControlToken();
        VehicleCommandRequest request = VehicleCommandRequest.forControlSegment("charge",
                api.buildChargeLimitPayload(limitAC, limitDC, controlTokenValue));
        return api.sendVehicleCommand(vehicleId, vin, "setChargeLimit", request, ccs2Supported);
    }
}
