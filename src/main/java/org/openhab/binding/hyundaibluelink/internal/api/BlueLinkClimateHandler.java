package org.openhab.binding.hyundaibluelink.internal.api;

import java.util.Objects;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandResponse;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandRequest;

/**
 * Handler for climate control and reservation commands.
 */
@NonNullByDefault
public class BlueLinkClimateHandler {
    private final BlueLinkApi api;

    public BlueLinkClimateHandler(BlueLinkApi api) {
        this.api = Objects.requireNonNull(api);
    }

    public VehicleCommandResponse start(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return start(vehicleId, vin, null, false, false, false, false, false, ccs2Supported);
    }

    public VehicleCommandResponse start(String vehicleId, String vin, @Nullable Double temperature, boolean defrost,
            boolean heating, boolean steeringWheel, boolean sideMirror, boolean rearWindow, boolean ccs2Supported)
            throws Exception {
        String controlTokenValue = api.ensureControlToken();
        VehicleCommandRequest request = VehicleCommandRequest.forV1AndV2("temperature", "control/temperature",
                api.buildV1ClimatePayload("start", controlTokenValue, temperature, defrost, heating, steeringWheel,
                        sideMirror, rearWindow),
                api.buildV2ClimatePayload("start", temperature, defrost, steeringWheel, sideMirror, rearWindow));
        return api.sendVehicleCommand(vehicleId, vin, "start", request, ccs2Supported);
    }

    public VehicleCommandResponse stop(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.sendVehicleCommand(vehicleId, vin, "stop", ccs2Supported);
    }

    public VehicleCommandResponse setTargetTemperature(String vehicleId, String vin, double temperature,
            boolean ccs2Supported) throws Exception {
        return start(vehicleId, vin, Double.valueOf(temperature), false, false, false, false, false, ccs2Supported);
    }

    public @Nullable Reservation getReservation(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return api.getReservationImpl(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse setReservation(String vehicleId, String vin, Reservation reservation,
            boolean ccs2Supported) throws Exception {
        return api.setReservationImpl(vehicleId, vin, reservation, ccs2Supported);
    }
}
