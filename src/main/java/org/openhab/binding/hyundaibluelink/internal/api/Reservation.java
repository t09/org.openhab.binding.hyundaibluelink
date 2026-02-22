package org.openhab.binding.hyundaibluelink.internal.api;

/**
 * Represents a vehicle reservation (e.g. for pre-heating/climate).
 */
public class Reservation {
    public boolean active;
    public int hour;
    public int minute;
    public boolean defrost;

    public Reservation() {
    }

    public Reservation(boolean active, int hour, int minute, boolean defrost) {
        this.active = active;
        this.hour = hour;
        this.minute = minute;
        this.defrost = defrost;
    }

    @Override
    public String toString() {
        return "Reservation [active=" + active + ", hour=" + hour + ", minute=" + minute + ", defrost=" + defrost
                + "]";
    }
}
