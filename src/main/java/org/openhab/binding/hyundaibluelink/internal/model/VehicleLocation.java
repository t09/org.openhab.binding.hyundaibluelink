package org.openhab.binding.hyundaibluelink.internal.model;

import org.eclipse.jdt.annotation.NonNullByDefault;

@NonNullByDefault
public class VehicleLocation {
    public double latitude;
    public double longitude;

    public VehicleLocation() {
    }

    public VehicleLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
