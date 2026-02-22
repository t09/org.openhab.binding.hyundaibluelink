package org.openhab.binding.hyundaibluelink.internal;

import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.type.ChannelTypeUID;

public final class HyundaiBlueLinkBindingConstants {

        public static final String BINDING_ID = "hyundaibluelink";

        // Thing Type UIDs
        public static final ThingTypeUID THING_TYPE_ACCOUNT_BRIDGE = new ThingTypeUID(BINDING_ID, "accountBridge");
        public static final ThingTypeUID THING_TYPE_VEHICLE = new ThingTypeUID(BINDING_ID, "vehicle");

        // Config parameters
        public static final String CONFIG_INITIAL_REFRESH_TOKEN = "initialRefreshToken";
        public static final String CONFIG_BRAND = "brand";
        public static final String CONFIG_REGION = "region";
        public static final String CONFIG_PIN = "pin";
        public static final String CONFIG_REFRESH = "refresh";
        // Channels
        public static final String CHANNEL_LOCK_STATE = "lockState";
        public static final String CHANNEL_CLIMATE_CONTROL = "climateControl";
        public static final String CHANNEL_STATUS = "status";
        public static final String CHANNEL_ODOMETER = "odometer";
        public static final String CHANNEL_LOCATION = "location";
        public static final String CHANNEL_STARTCHARGE = "startCharge";
        public static final String CHANNEL_DOOR_STATUS = "doorStatus";
        public static final String CHANNEL_WINDOW_STATUS = "windowStatus";
        public static final String CHANNEL_CLIMATE_STATUS = "climateStatus";
        public static final String CHANNEL_ACC = "acc";
        public static final String CHANNEL_MINOR_WARNINGS = "minorWarnings";
        public static final String CHANNEL_LAST_NOTIFICATION = "lastNotification";
        public static final String CHANNEL_BATTERY_WARNING = "batteryWarning";
        public static final String CHANNEL_EV_MODE_RANGE = "evModeRange";
        public static final String CHANNEL_GAS_MODE_RANGE = "gasModeRange";
        public static final String CHANNEL_FUEL_LEVEL = "fuelLevel";

        public static final String CHANNEL_CHARGE_LIMIT_AC = "chargeLimitAC";
        public static final String CHANNEL_CHARGE_LIMIT_DC = "chargeLimitDC";
        public static final String CHANNEL_TARGET_TEMPERATURE = "targetTemperature";
        public static final String CHANNEL_CLIMATE_FRONT_WINDOW_HEATING = "frontWindowHeating";
        public static final String CHANNEL_CLIMATE_REAR_WINDOW_HEATING = "rearWindowHeating";
        public static final String CHANNEL_CLIMATE_HEATING_STEERING_WHEEL = "controlHeatingSteeringWheel";
        public static final String CHANNEL_CLIMATE_HEATING_SIDE_MIRROR = "controlHeatingSideMirror";
        public static final String CHANNEL_CLIMATE_HEATING_REAR_WINDOW = "controlHeatingRearWindow";

        // Reservation (Preheating) Channels
        public static final String CHANNEL_RESERVATION_TIME = "reservationTime";
        public static final String CHANNEL_RESERVATION_ACTIVE = "reservationActive";
        public static final String CHANNEL_RESERVATION_DEFROST = "reservationDefrost";

        // Additional channels
        public static final String CHANNEL_VIN = "vin";
        public static final String CHANNEL_BATTERY_LEVEL = "batteryLevel";
        public static final String CHANNEL_RANGE = "range";
        public static final String CHANNEL_DOORS_LOCKED = "doorsLocked";
        public static final String CHANNEL_CHARGING = "charging";
        public static final String CHANNEL_LAST_UPDATED = "lastUpdated";
        public static final String CHANNEL_CHARGING_STATE = "chargingState";
        public static final String CHANNEL_REMAINING_CHARGE_TIME = "chargingRemainTime";
        public static final String CHANNEL_CONNECTOR_FASTENED = "connectorFastened";

        // Channel type UIDs
        public static final ChannelTypeUID CHANNEL_TYPE_ODOMETER = new ChannelTypeUID(BINDING_ID, "odometer");
        public static final ChannelTypeUID CHANNEL_TYPE_RANGE = new ChannelTypeUID(BINDING_ID, "range");
        public static final ChannelTypeUID CHANNEL_TYPE_EV_RANGE = new ChannelTypeUID(BINDING_ID, "evModeRange");
        public static final ChannelTypeUID CHANNEL_TYPE_GAS_RANGE = new ChannelTypeUID(BINDING_ID, "gasModeRange");
        public static final ChannelTypeUID CHANNEL_TYPE_ODOMETER_KILOMETRES = new ChannelTypeUID(BINDING_ID,
                        "odometer-km");
        public static final ChannelTypeUID CHANNEL_TYPE_ODOMETER_MILES = new ChannelTypeUID(BINDING_ID, "odometer-mi");
        public static final ChannelTypeUID CHANNEL_TYPE_RANGE_KILOMETRES = new ChannelTypeUID(BINDING_ID, "range-km");
        public static final ChannelTypeUID CHANNEL_TYPE_RANGE_MILES = new ChannelTypeUID(BINDING_ID, "range-mi");
        public static final ChannelTypeUID CHANNEL_TYPE_EV_RANGE_KILOMETRES = new ChannelTypeUID(BINDING_ID,
                        "evModeRange-km");
        public static final ChannelTypeUID CHANNEL_TYPE_EV_RANGE_MILES = new ChannelTypeUID(BINDING_ID,
                        "evModeRange-mi");
        public static final ChannelTypeUID CHANNEL_TYPE_GAS_RANGE_KILOMETRES = new ChannelTypeUID(BINDING_ID,
                        "gasModeRange-km");
        public static final ChannelTypeUID CHANNEL_TYPE_GAS_RANGE_MILES = new ChannelTypeUID(BINDING_ID,
                        "gasModeRange-mi");

        // Discovery properties
        public static final String PROPERTY_MODEL_YEAR = "modelYear";
        public static final String PROPERTY_LICENSE_PLATE = "licensePlate";
        public static final String PROPERTY_IN_COLOR = "inColor";
        public static final String PROPERTY_OUT_COLOR = "outColor";
        public static final String PROPERTY_SALE_CARMDL_CD = "saleCarmdlCd";
        public static final String PROPERTY_BODY_TYPE = "bodyType";
        public static final String PROPERTY_SALE_CARMDL_EN_NM = "saleCarmdlEnNm";
        public static final String PROPERTY_VEHICLE_TYPE = "type";
        public static final String PROPERTY_PROTOCOL_TYPE = "protocolType";
        public static final String PROPERTY_CCU_CCS2_PROTOCOL_SUPPORT = "ccuCCS2ProtocolSupport";
        public static final String PROPERTY_VEHICLE_ID = "vehicleId";

        private HyundaiBlueLinkBindingConstants() {
                // utility class
        }
}
