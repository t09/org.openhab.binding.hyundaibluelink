package si.uom;

import javax.measure.Unit;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Radioactivity;

import tech.units.indriya.unit.Units;

public final class NonSI {
    public static final Unit<Angle> DEGREE_ANGLE = Units.RADIAN.multiply(Math.PI / 180.0);
    public static final Unit<Radioactivity> CURIE = Units.BECQUEREL.multiply(3.7e10);

    private NonSI() {
    }
}
