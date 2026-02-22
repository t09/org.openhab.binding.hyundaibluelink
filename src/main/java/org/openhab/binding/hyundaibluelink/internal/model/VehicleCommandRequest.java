package org.openhab.binding.hyundaibluelink.internal.model;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;

@NonNullByDefault
public class VehicleCommandRequest {
    public final @Nullable String controlSegmentV1;
    public final @Nullable String ccs2Suffix;
    public final @Nullable JsonObject staticV1Payload;
    public final @Nullable JsonObject staticV2Payload;
    public final boolean remoteDoor;
    public final @Nullable String remoteDoorAction;

    private VehicleCommandRequest(@Nullable String controlSegmentV1, @Nullable String ccs2Suffix,
            @Nullable JsonObject staticV1Payload, @Nullable JsonObject staticV2Payload, boolean remoteDoor,
            @Nullable String remoteDoorAction) {
        this.controlSegmentV1 = controlSegmentV1;
        this.ccs2Suffix = ccs2Suffix;
        this.staticV1Payload = staticV1Payload;
        this.staticV2Payload = staticV2Payload;
        this.remoteDoor = remoteDoor;
        this.remoteDoorAction = remoteDoorAction;
    }

    public static VehicleCommandRequest forV1Only(String controlSegment, JsonObject payload) {
        return new VehicleCommandRequest(controlSegment, controlSegment, payload, payload, false, null);
    }

    public static VehicleCommandRequest forV1AndV2(String segmentV1, String suffixV2, JsonObject payloadV1,
            JsonObject payloadV2) {
        return new VehicleCommandRequest(segmentV1, suffixV2, payloadV1, payloadV2, false, null);
    }

    public static VehicleCommandRequest forControlSegment(String controlSegment, JsonObject payload) {
        return forV1Only(controlSegment, payload);
    }

    public static VehicleCommandRequest forRemoteDoor(String action) {
        return new VehicleCommandRequest(null, "ccs2/remote/door", null, null, true, action);
    }

    public @Nullable JsonObject getPayload(String baseUrl, @Nullable String deviceId, boolean ccs2Supported) {
        boolean isV1 = baseUrl.toLowerCase(Locale.ROOT).contains("/api/v1/spa");
        if (remoteDoor) {
            if (remoteDoorAction == null) {
                return new JsonObject();
            }
            JsonObject p = new JsonObject();
            if (isV1) {
                p.addProperty("action", remoteDoorAction);
                if (deviceId != null && !deviceId.isBlank()) {
                    p.addProperty("deviceId", deviceId);
                }
            } else {
                // For V2, we include both to be safe, but always include 'command'
                // and for non-CCS2 V2, also include 'action'.
                p.addProperty("command", remoteDoorAction);
                if (!ccs2Supported) {
                    p.addProperty("action", remoteDoorAction);
                }
            }
            return p;
        }
        JsonObject p = isV1 ? staticV1Payload : staticV2Payload;
        if (!isV1 && p != null && !ccs2Supported) {
            // For standard V2, if we have a 'command' but no 'action', try to add it
            if (p.has("command") && !p.has("action")) {
                JsonObject p2 = p.deepCopy();
                p2.add("action", p.get("command"));
                return p2;
            }
        }
        return p;
    }

    public @Nullable JsonObject getPayload(String baseUrl, @Nullable String deviceId) {
        return getPayload(baseUrl, deviceId, false);
    }

    public URI buildUri(String baseUrl, String vehicleId, boolean ccs2Supported) {
        if (remoteDoor) {
            return Objects.requireNonNull(org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi
                    .buildRemoteDoorUri(baseUrl, vehicleId));
        }
        boolean isV1 = baseUrl.toLowerCase(Locale.ROOT).contains("/api/v1/spa");
        if (isV1) {
            return Objects
                    .requireNonNull(org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi
                            .buildControlUri(baseUrl, vehicleId, Objects.requireNonNull(controlSegmentV1)));
        } else {
            String suffix = Objects.requireNonNull(ccs2Suffix);
            if (!suffix.contains("/")) {
                suffix = "control/" + suffix;
            }
            if (ccs2Supported && !suffix.startsWith("ccs2/")) {
                suffix = "ccs2/" + suffix;
            }
            return Objects.requireNonNull(
                    org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi.buildSpaVehicleUri(baseUrl, vehicleId,
                            suffix, true));
        }
    }

    public boolean requiresCcspToken(boolean isV1, boolean ccs2Supported) {
        if (isV1) {
            return false;
        }
        if (remoteDoor || ccs2Supported) {
            return true;
        }
        if (ccs2Suffix != null && ccs2Suffix.startsWith("ccs2/")) {
            return true;
        }
        return false;
    }

    public String getLogSegment(boolean ccs2Supported) {
        if (remoteDoor) {
            return "ccs2/remote/door";
        }
        String segment = ccs2Suffix;
        if (segment == null) {
            segment = controlSegmentV1;
        }
        if (ccs2Supported && segment != null && !segment.startsWith("ccs2/")) {
            return "ccs2/" + segment;
        }
        return segment != null ? segment : "null";
    }
}
