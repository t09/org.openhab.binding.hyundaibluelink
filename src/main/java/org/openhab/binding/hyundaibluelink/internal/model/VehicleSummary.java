package org.openhab.binding.hyundaibluelink.internal.model;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

@NonNullByDefault
public class VehicleSummary {
    public @Nullable String vehicleId;
    public @Nullable String vin;
    public @Nullable String label;
    public @Nullable String model;
    public @Nullable String modelYear;
    public @Nullable String licensePlate;
    public @Nullable String inColor;
    public @Nullable String outColor;
    public @Nullable String saleCarmdlCd;
    public @Nullable String bodyType;
    public @Nullable String saleCarmdlEnNm;
    public @Nullable String type;
    public @Nullable String protocolType;
    public @Nullable String ccuCCS2ProtocolSupport;
}
