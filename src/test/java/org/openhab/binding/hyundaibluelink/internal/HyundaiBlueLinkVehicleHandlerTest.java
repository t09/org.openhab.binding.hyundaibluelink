package org.openhab.binding.hyundaibluelink.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.api.DistanceUnit;

import org.openhab.binding.hyundaibluelink.internal.model.*;
import org.openhab.binding.hyundaibluelink.internal.api.OAuthClient;
import org.openhab.binding.hyundaibluelink.internal.api.StampProvider;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.core.config.core.Configuration;

import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;

@org.eclipse.jdt.annotation.NonNullByDefault
@SuppressWarnings("null")
class HyundaiBlueLinkVehicleHandlerTest {
    private CompletingCommandApi api = new CompletingCommandApi();

    @Test
    void refreshUsesVehicleIdPropertyWhenAvailable() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "VIN1234567890");
        Thing thing = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID,
                        "33333333-4444-5555-6666-777777777777")
                .build();

        HyundaiBlueLinkVehicleHandler handler = new HyundaiBlueLinkVehicleHandler(thing);
        RecordingApi api = new RecordingApi();
        setApi(handler, api);

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        assertEquals("33333333-4444-5555-6666-777777777777", api.lastStatusVehicleId);
        assertEquals("VIN1234567890", api.lastStatusVin);
        assertEquals("33333333-4444-5555-6666-777777777777", api.lastLocationVehicleId);
        assertEquals("VIN1234567890", api.lastLocationVin);
    }

    @Test
    void refreshFallsBackToConfiguredVehicleId() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "VIN0987654321");
        Configuration configuration = new Configuration();
        configuration.put(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID,
                "44444444-5555-6666-7777-888888888888");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID).withConfiguration(configuration).build();

        HyundaiBlueLinkVehicleHandler handler = new HyundaiBlueLinkVehicleHandler(thing);
        RecordingApi api = new RecordingApi();
        setApi(handler, api);

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        assertEquals("44444444-5555-6666-7777-888888888888", api.lastStatusVehicleId);
        assertEquals("VIN0987654321", api.lastStatusVin);
        assertEquals("44444444-5555-6666-7777-888888888888", api.lastLocationVehicleId);
        assertEquals("VIN0987654321", api.lastLocationVin);
    }

    @Test
    void refreshResolvesVehicleIdFromBridgeWhenMissing() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "VINBRIDGE123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID).build();

        VehicleSummary summary = new VehicleSummary();
        summary.vin = "VINBRIDGE123456";
        summary.vehicleId = "11111111-2222-3333-4444-555555555555";

        TestAccountBridgeHandler accountHandler = new TestAccountBridgeHandler();
        accountHandler.vehicles = List.of(summary);

        TestVehicleHandler handler = new TestVehicleHandler(thing, accountHandler);
        RecordingApi api = new RecordingApi();
        setApi(handler, api);

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        assertEquals(1, accountHandler.listCallCount);
        assertEquals("11111111-2222-3333-4444-555555555555", api.lastStatusVehicleId);
        assertEquals("11111111-2222-3333-4444-555555555555", api.lastLocationVehicleId);
        assertNotNull(handler.lastUpdatedThing);
        assertEquals("11111111-2222-3333-4444-555555555555",
                handler.lastUpdatedThing.getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID));
    }

    @Test
    void refreshJobReschedulesWhenBridgeIntervalChanges() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-refresh");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "VINRESCHED123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-refresh-id").build();

        DynamicIntervalAccountBridgeHandler bridgeHandler = new DynamicIntervalAccountBridgeHandler(5);
        RefreshIntervalVehicleHandler handler = new RefreshIntervalVehicleHandler(thing, bridgeHandler);

        RecordingApi api = new RecordingApi();
        bridgeHandler.setApi(api);
        setApi(handler, api);

        invokeScheduleRefreshJob(handler);

        assertEquals(1, handler.scheduledIntervals.size());
        assertEquals(5, handler.scheduledIntervals.get(0));
        ManualScheduledFuture firstFuture = handler.lastRefreshFuture;
        assertNotNull(firstFuture);
        assertFalse(firstFuture.isCancelled());
        ScheduledFuture<?> future1 = getRefreshJobFuture(handler);
        assertNotNull(future1);

        bridgeHandler.setRefreshInterval(2);
        invokeEnsureRefreshScheduleUpToDate(handler);

        ManualScheduledFuture secondFuture = handler.lastRefreshFuture;
        assertTrue(firstFuture.isCancelled());
        assertNotNull(secondFuture);
        assertNotSame(firstFuture, secondFuture);
        assertEquals(2, handler.scheduledIntervals.get(handler.scheduledIntervals.size() - 1));
        assertEquals(2, getScheduledRefreshInterval(handler));
        assertSame(secondFuture, getRefreshJobFuture(handler));

        bridgeHandler.setRefreshInterval(0);
        invokeEnsureRefreshScheduleUpToDate(handler);

        assertTrue(secondFuture.isCancelled());
        assertEquals(1, handler.immediateRefreshInvocations);
        assertEquals(1, handler.immediateRefreshInvocations);

        bridgeHandler.setRefreshInterval(15);
        invokeEnsureRefreshScheduleUpToDate(handler);
        ManualScheduledFuture thirdFuture = handler.lastRefreshFuture;
        assertNotNull(thirdFuture);
        assertNotSame(secondFuture, thirdFuture);
        assertFalse(thirdFuture.isCancelled());
        assertEquals(15, handler.scheduledIntervals.get(handler.scheduledIntervals.size() - 1));
        assertEquals(15, getScheduledRefreshInterval(handler));
        assertSame(thirdFuture, getRefreshJobFuture(handler));
    }

    @Test
    void handlerProducesChannelUpdatesForEuStatus() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-eu");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID, "EU1234567890");
        ThingBuilder builder = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-eu-uuid");
        Thing thing = withDistanceChannels(builder, thingUID).build();

        CapturingVehicleHandler handler = new CapturingVehicleHandler(thing);

        VehicleStatus status = new VehicleStatus();
        status.vin = "VIN-EU-123";
        status.doorsLocked = Boolean.TRUE;
        status.charging = Boolean.FALSE;
        status.climateOn = Boolean.TRUE;
        status.batteryWarning = Boolean.FALSE;
        status.batteryLevel = 54.0;
        status.range = 235.0;
        status.rangeUnit = DistanceUnit.KILOMETERS;
        status.evModeRange = 235.0;
        status.evModeRangeUnit = DistanceUnit.KILOMETERS;
        status.gasModeRange = 645.0;
        status.gasModeRangeUnit = DistanceUnit.KILOMETERS;
        status.fuelLevel = 73.0;
        status.odometer = 12345.0;
        status.odometerUnit = DistanceUnit.KILOMETERS;
        status.doorStatusSummary = "frontLeft=CLOSED, frontRight=OPEN";
        status.windowStatusSummary = "rearLeft=CLOSED";
        status.lastUpdated = Instant.parse("2024-01-01T01:02:03Z");
        status.lowFuelLight = Boolean.FALSE;

        VehicleLocation location = new VehicleLocation();
        location.latitude = 52.52;
        location.longitude = 13.405;
        status.latitude = location.latitude;
        status.longitude = location.longitude;

        StubStatusApi api = new StubStatusApi(status, location);
        setApi(handler, api);

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        assertEquals(OnOffType.ON, handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE));
        assertEquals(OnOffType.OFF, handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_STARTCHARGE));
        State lastUpdatedState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_LAST_UPDATED);
        assertNotNull(lastUpdatedState);
        assertEquals(new DateTimeType(status.lastUpdated.atZone(ZoneOffset.UTC)), lastUpdatedState);

        State doorState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_DOOR_STATUS);
        assertNotNull(doorState);
        assertEquals("frontLeft=CLOSED, frontRight=OPEN", doorState.toString());

        State windowState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_WINDOW_STATUS);
        assertNotNull(windowState);
        assertEquals("rearLeft=CLOSED", windowState.toString());

        State statusState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_STATUS);
        assertNotNull(statusState);
        assertNotEquals(UnDefType.UNDEF, statusState);

        assertEquals(OnOffType.ON, handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_STATUS));
        assertEquals(OnOffType.ON, handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL));
        assertEquals(OnOffType.OFF, handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_WARNING));

        State batteryLevelState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_LEVEL);
        assertNotNull(batteryLevelState);
        assertEquals("54 %", batteryLevelState.toString());

        State rangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE);
        assertNotNull(rangeState);
        assertTrue(rangeState instanceof QuantityType);
        assertEquals(new QuantityType<>(235.0, MetricPrefix.KILO(SIUnits.METRE)), rangeState);

        State locationState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_LOCATION);
        assertNotNull(locationState);
        assertTrue(locationState instanceof PointType);
        PointType point = (PointType) locationState;
        assertEquals(location.latitude, point.getLatitude().doubleValue(), 0.0001);
        assertEquals(location.longitude, point.getLongitude().doubleValue(), 0.0001);
        assertEquals(0, api.locationCallCount);

        State evRangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE);
        assertNotNull(evRangeState);
        assertTrue(evRangeState instanceof QuantityType);
        assertEquals(new QuantityType<>(235.0, MetricPrefix.KILO(SIUnits.METRE)), evRangeState);

        State gasRangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE);
        assertNotNull(gasRangeState);
        assertTrue(gasRangeState instanceof QuantityType);
        assertEquals(new QuantityType<>(645.0, MetricPrefix.KILO(SIUnits.METRE)), gasRangeState);

        State fuelLevelState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_FUEL_LEVEL);
        assertNotNull(fuelLevelState);
        assertEquals("73 %", fuelLevelState.toString());

        State odometerState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER);
        assertNotNull(odometerState);
        assertEquals("12345 km", odometerState.toString());

        Thing updatedThing = handler.lastUpdatedThing;
        assertNotNull(updatedThing);
        Channel odometerChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER);
        assertNotNull(odometerChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_ODOMETER_KILOMETRES,
                odometerChannel.getChannelTypeUID());
        Channel rangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE);
        assertNotNull(rangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_RANGE_KILOMETRES, rangeChannel.getChannelTypeUID());
        Channel evRangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE);
        assertNotNull(evRangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_EV_RANGE_KILOMETRES,
                evRangeChannel.getChannelTypeUID());
        Channel gasRangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE);
        assertNotNull(gasRangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_GAS_RANGE_KILOMETRES,
                gasRangeChannel.getChannelTypeUID());
    }

    @Test
    void handlerSwitchesChannelUnitHintsForMilesStatus() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-us");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID, "US1234567890");
        ThingBuilder builder = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-us-uuid");
        Thing thing = withDistanceChannels(builder, thingUID).build();

        CapturingVehicleHandler handler = new CapturingVehicleHandler(thing);

        VehicleStatus status = new VehicleStatus();
        status.vin = "VIN-US-123";
        status.doorsLocked = Boolean.TRUE;
        status.charging = Boolean.FALSE;
        status.climateOn = Boolean.FALSE;
        status.batteryWarning = Boolean.FALSE;
        status.batteryLevel = 80.0;
        status.range = 150.0;
        status.rangeUnit = DistanceUnit.MILES;
        status.evModeRange = 120.0;
        status.evModeRangeUnit = DistanceUnit.MILES;
        status.gasModeRange = 320.0;
        status.gasModeRangeUnit = DistanceUnit.MILES;
        status.fuelLevel = 55.0;
        status.odometer = 9876.0;
        status.odometerUnit = DistanceUnit.MILES;
        status.lastUpdated = Instant.parse("2024-02-02T02:03:04Z");

        VehicleLocation location = new VehicleLocation();
        location.latitude = 40.7128;
        location.longitude = -74.0060;
        status.latitude = location.latitude;
        status.longitude = location.longitude;

        StubStatusApi api = new StubStatusApi(status, location);
        setApi(handler, api);

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        State rangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE);
        assertNotNull(rangeState);
        assertEquals(new QuantityType<>(150.0, ImperialUnits.MILE), rangeState);

        State evRangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE);
        assertNotNull(evRangeState);
        assertEquals(new QuantityType<>(120.0, ImperialUnits.MILE), evRangeState);

        State gasRangeState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE);
        assertNotNull(gasRangeState);
        assertEquals(new QuantityType<>(320.0, ImperialUnits.MILE), gasRangeState);

        State odometerState = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER);
        assertNotNull(odometerState);
        assertEquals(new QuantityType<>(9876.0, ImperialUnits.MILE), odometerState);

        Thing updatedThing = handler.lastUpdatedThing;
        assertNotNull(updatedThing);
        Channel odometerChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER);
        assertNotNull(odometerChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_ODOMETER_MILES,
                odometerChannel.getChannelTypeUID());
        Channel rangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_RANGE);
        assertNotNull(rangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_RANGE_MILES, rangeChannel.getChannelTypeUID());
        Channel evRangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE);
        assertNotNull(evRangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_EV_RANGE_MILES,
                evRangeChannel.getChannelTypeUID());
        Channel gasRangeChannel = updatedThing.getChannel(HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE);
        assertNotNull(gasRangeChannel);
        assertEquals(HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_GAS_RANGE_MILES,
                gasRangeChannel.getChannelTypeUID());
    }

    @Test
    void retainsPreviousLocationWhenFallbackFails() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-loc");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID, "LOCVIN123");
        Thing thing = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-location-id")
                .build();

        CapturingVehicleHandler handler = new CapturingVehicleHandler(thing);
        LocationFailureRecordingApi api = new LocationFailureRecordingApi();
        setApi(handler, api);

        VehicleStatus firstStatus = new VehicleStatus();
        firstStatus.latitude = Double.valueOf(48.099894);
        firstStatus.longitude = Double.valueOf(16.311561);
        api.nextStatus = firstStatus;

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        State initialLocation = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_LOCATION);
        assertNotNull(initialLocation);
        assertTrue(initialLocation instanceof PointType);

        VehicleStatus secondStatus = new VehicleStatus();
        api.nextStatus = secondStatus;
        api.returnInvalidLocation = true;

        handler.handleCommand(new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS),
                RefreshType.REFRESH);

        assertEquals(1, api.locationCallCount,
                "Location fallback should only be invoked after status failed to provide coordinates");
        State subsequentLocation = handler.state(HyundaiBlueLinkBindingConstants.CHANNEL_LOCATION);
        assertNotNull(subsequentLocation);
        assertSame(initialLocation, subsequentLocation, "Fallback failure should retain the previous location state");
    }

    @Test
    void lockCommandSchedulesDelayedRefreshWithoutImmediatePoll() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-lock");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "LOCKVIN123456");
        Thing thing = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-lock-id")
                .build();

        DelayCapturingVehicleHandler handler = new DelayCapturingVehicleHandler(thing);
        CommandRecordingApi api = new CommandRecordingApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);

        handler.handleCommand(lockChannel, OnOffType.ON);

        assertEquals(0, api.statusCallCount, "Vehicle status should not be refreshed immediately");
        assertEquals(0, api.locationCallCount, "Vehicle location should not be refreshed immediately");
        assertEquals(1, handler.commandRefreshScheduleCount, "A single delayed refresh should be scheduled");
        assertEquals(commandRefreshDelaySeconds(), handler.lastCommandRefreshDelaySeconds);
        assertNotNull(handler.lastScheduledFuture);
        assertEquals("lock", api.lastCommandAction);
        assertEquals(1, api.lockCallCount);
        assertEquals(0, api.pollCallCount);

        handler.handleCommand(lockChannel, OnOffType.OFF);

        assertEquals("lock", api.lastCommandAction);
        assertEquals(1, api.lockCallCount);
        assertEquals(0, api.unlockCallCount);
        assertEquals(1, handler.commandRefreshScheduleCount,
                "Additional commands should not schedule another refresh while one is pending");
    }

    @Test
    void pendingCommandBlocksOtherChannelsUntilRefreshCompletes() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-block");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "BLOCKVIN123456");
        Thing thing = ThingBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-block-id")
                .build();

        DelayCapturingVehicleHandler handler = new DelayCapturingVehicleHandler(thing);
        CommandRecordingApi api = new CommandRecordingApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);
        ChannelUID climateChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL);

        handler.handleCommand(lockChannel, OnOffType.ON);
        assertEquals(1, api.lockCallCount);

        handler.handleCommand(climateChannel, OnOffType.ON);

        assertEquals(0, api.climateStartCallCount, "Climate command should be ignored while lock command is pending");
        assertEquals(1, handler.commandRefreshScheduleCount);

        ManualScheduledFuture scheduledRefresh = handler.lastScheduledFuture;
        assertNotNull(scheduledRefresh);
        scheduledRefresh.run();

        assertEquals(1, api.statusCallCount, "Refresh should be executed when the delayed job runs");
        assertEquals(1, api.locationCallCount, "Location refresh should occur as part of the delayed job");

        handler.handleCommand(climateChannel, OnOffType.ON);

        assertEquals(1, api.climateStartCallCount,
                "Climate command should succeed after the pending command completes");
        assertEquals(2, handler.commandRefreshScheduleCount,
                "Completed commands should allow a new refresh to be scheduled");
        assertEquals("start", api.lastCommandAction);
    }

    @Test
    void commandResultPollTriggersRefreshAfterCompletion() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-poll");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "POLLVIN123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-poll-id")
                .build();

        PollControlledVehicleHandler handler = new PollControlledVehicleHandler(thing);
        CompletingCommandApi api = new CompletingCommandApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);

        handler.handleCommand(lockChannel, OnOffType.ON);

        assertEquals(0, handler.commandRefreshScheduleCount);
        assertEquals(-1, handler.lastCommandRefreshDelaySeconds);
        assertEquals(1, handler.pollDelays.size());
        assertEquals(commandResultPollIntervalSeconds(), handler.pollDelays.get(0));
        assertNull(handler.lastScheduledFuture);

        ManualScheduledFuture firstPoll = handler.nextPollFuture();
        assertNotNull(firstPoll);
        firstPoll.run();

        assertEquals(1, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);
        assertEquals(2, handler.pollDelays.size());
        assertEquals(commandResultPollIntervalSeconds(), handler.pollDelays.get(1));
        assertNull(handler.lastScheduledFuture);

        api.nextReady = true;
        ManualScheduledFuture secondPoll = handler.nextPollFuture();
        assertNotNull(secondPoll);
        secondPoll.run();

        assertEquals(2, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);
        assertEquals(1, handler.commandRefreshScheduleCount);
        assertEquals(commandRefreshDelaySeconds(), handler.lastCommandRefreshDelaySeconds);
        ManualScheduledFuture postPollRefresh = handler.lastScheduledFuture;
        assertNotNull(postPollRefresh);
        assertFalse(postPollRefresh.isCancelled());
        postPollRefresh.run();
        assertEquals(1, api.statusCallCount);
        assertEquals(1, api.locationCallCount);
        assertEquals(0, handler.remainingPollFutures());

        ChannelUID climateChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL);
        handler.handleCommand(climateChannel, OnOffType.ON);

        assertEquals(1, api.climateStartCallCount,
                "Subsequent commands should proceed once the job result has completed");
        assertEquals(2, handler.commandRefreshScheduleCount,
                "A new delayed refresh should be scheduled for the next command");
        assertNotNull(handler.lastScheduledFuture);
        assertFalse(handler.lastScheduledFuture.isCancelled());
    }

    @Test
    public void handleCommandChargeLimitAC() throws Exception {
        HyundaiBlueLinkVehicleHandler handler = setupHandler(true);
        ChannelUID channel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC);
        handler.handleCommand(channel, new DecimalType(80));
        waitForPolling(handler);

        assertEquals(new DecimalType(80), ((PollControlledVehicleHandler) handler).state("chargeLimitAC"));
        assertEquals("setChargeLimit", api.lastCommandAction);
        assertEquals(1, api.setChargeLimitCallCount);
    }

    @Test
    public void handleCommandChargeLimitDC() throws Exception {
        HyundaiBlueLinkVehicleHandler handler = setupHandler(true);
        ChannelUID channel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC);
        handler.handleCommand(channel, new DecimalType(90));
        waitForPolling(handler);

        assertEquals(new DecimalType(90), ((PollControlledVehicleHandler) handler).state("chargeLimitDC"));
        assertEquals("setChargeLimit", api.lastCommandAction);
        assertEquals(1, api.setChargeLimitCallCount);
    }

    @Test
    public void handleCommandTargetTemp() throws Exception {
        HyundaiBlueLinkVehicleHandler handler = setupHandler(true);
        ChannelUID channel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_TARGET_TEMPERATURE);
        handler.handleCommand(channel, new DecimalType(30));
        waitForPolling(handler);

        assertEquals(new DecimalType(30), ((PollControlledVehicleHandler) handler).state("targetTemperature"));
        assertEquals("setTargetTemperature", api.lastCommandAction);
        assertEquals(1, api.setTargetTemperatureCallCount);
    }

    @Test
    public void handleCommandExtendedHeatingOptions() throws Exception {
        HyundaiBlueLinkVehicleHandler handler = setupHandler(true);
        ChannelUID steeringChannel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_STEERING_WHEEL);
        ChannelUID sideMirrorChannel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_SIDE_MIRROR);
        ChannelUID rearWindowChannel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_REAR_WINDOW);
        ChannelUID climateChannel = new ChannelUID(handler.getThing().getUID(),
                HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL);

        // Enable message ID for start
        api.returnMessageIdForStart = true;

        // Turn on all extended options
        handler.handleCommand(steeringChannel, OnOffType.ON);
        handler.handleCommand(sideMirrorChannel, OnOffType.ON);
        handler.handleCommand(rearWindowChannel, OnOffType.ON);

        // Start climate
        api.nextReady = true;
        handler.handleCommand(climateChannel, OnOffType.ON);
        waitForPolling(handler);

        assertEquals("start", api.lastCommandAction);
        assertEquals(1, api.climateStartCallCount);
        assertTrue(api.lastStartSteering);
        assertTrue(api.lastStartSideMirror);
        assertTrue(api.lastStartRearWindow);
        assertFalse(api.lastStartDefrost);
        assertFalse(api.lastStartHeating);

        // Disable one and restart
        api.nextReady = true;
        handler.handleCommand(steeringChannel, OnOffType.OFF);
        handler.handleCommand(climateChannel, OnOffType.ON);
        waitForPolling(handler);

        assertEquals(2, api.climateStartCallCount);
        assertFalse(api.lastStartSteering);
        assertTrue(api.lastStartSideMirror);
        assertTrue(api.lastStartRearWindow);
    }

    @Test
    void refreshRequestsAreDeferredDuringCommandPolling() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-defer");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "DEFERVIN123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-defer-id")
                .build();

        PollControlledVehicleHandler handler = new PollControlledVehicleHandler(thing);
        CompletingCommandApi api = new CompletingCommandApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);
        ChannelUID statusChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_STATUS);

        handler.handleCommand(lockChannel, OnOffType.ON);

        handler.handleCommand(statusChannel, RefreshType.REFRESH);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);

        ManualScheduledFuture firstPoll = handler.nextPollFuture();
        assertNotNull(firstPoll);
        firstPoll.run();

        assertEquals(1, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);

        handler.handleCommand(statusChannel, RefreshType.REFRESH);
        assertEquals(0, api.statusCallCount, "Refresh requests should be deferred while polling");

        api.nextReady = true;
        ManualScheduledFuture secondPoll = handler.nextPollFuture();
        assertNotNull(secondPoll);
        secondPoll.run();

        assertEquals(2, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);
        assertEquals(1, handler.commandRefreshScheduleCount);

        ManualScheduledFuture scheduledRefresh = handler.lastScheduledFuture;
        assertNotNull(scheduledRefresh);
        scheduledRefresh.run();

        assertEquals(1, api.statusCallCount);
        assertEquals(1, api.locationCallCount);
    }

    @Test
    void commandResultPollStopsWhenApiReportsFinalFailure() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge-fail");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "FAILVIN123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-fail-id")
                .build();

        PollControlledVehicleHandler handler = new PollControlledVehicleHandler(thing);
        FailingCommandApi api = new FailingCommandApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);

        handler.handleCommand(lockChannel, OnOffType.ON);

        assertEquals(0, handler.commandRefreshScheduleCount);
        assertEquals(-1, handler.lastCommandRefreshDelaySeconds);
        assertEquals(1, handler.pollDelays.size());
        assertEquals(commandResultPollIntervalSeconds(), handler.pollDelays.get(0));

        ManualScheduledFuture pollFuture = handler.nextPollFuture();
        assertNotNull(pollFuture);

        pollFuture.run();

        assertEquals(1, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);
        assertEquals(0, handler.remainingPollFutures());
        assertEquals(1, handler.commandRefreshScheduleCount);
        ManualScheduledFuture scheduledRefresh = handler.lastScheduledFuture;
        assertNotNull(scheduledRefresh);
        scheduledRefresh.run();
        assertEquals(1, api.statusCallCount);
        assertEquals(1, api.locationCallCount);
    }

    @Test
    void delayedRefreshWaitsUntilJobPollingCompletes() throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE,
                "bridge-poll-wait");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID,
                "WAITVIN123456");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-wait-id")
                .build();

        PollControlledVehicleHandler handler = new PollControlledVehicleHandler(thing);
        CompletingCommandApi api = new CompletingCommandApi();
        setApi(handler, api);

        ChannelUID lockChannel = new ChannelUID(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE);

        handler.handleCommand(lockChannel, OnOffType.ON);
        assertEquals(0, handler.commandRefreshScheduleCount);
        assertNull(handler.lastScheduledFuture);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);

        ManualScheduledFuture firstPoll = handler.nextPollFuture();
        assertNotNull(firstPoll);
        firstPoll.run();

        assertEquals(1, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);

        api.nextReady = true;
        ManualScheduledFuture secondPoll = handler.nextPollFuture();
        assertNotNull(secondPoll);
        secondPoll.run();

        assertEquals(2, api.pollCallCount);
        assertEquals(0, api.statusCallCount);
        assertEquals(0, api.locationCallCount);
        assertEquals(1, handler.commandRefreshScheduleCount);

        ManualScheduledFuture postPollRefresh = handler.lastScheduledFuture;
        assertNotNull(postPollRefresh);
        postPollRefresh.run();

        assertEquals(1, api.statusCallCount);
        assertEquals(1, api.locationCallCount);
    }

    private static long commandRefreshDelaySeconds() throws Exception {
        Field field = HyundaiBlueLinkVehicleHandler.class.getDeclaredField("COMMAND_REFRESH_DELAY_SECONDS");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static long commandResultPollIntervalSeconds() throws Exception {
        Field field = HyundaiBlueLinkVehicleHandler.class.getDeclaredField("COMMAND_RESULT_POLL_INTERVAL_SECONDS");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private HyundaiBlueLinkVehicleHandler setupHandler(boolean usePolling) throws Exception {
        ThingUID bridgeUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "bridge");
        ThingUID thingUID = new ThingUID(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, bridgeUID, "VIN12345");
        Thing thing = ThingBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_VEHICLE, thingUID)
                .withBridge(bridgeUID)
                .withProperty(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_ID, "vehicle-id")
                .build();

        HyundaiBlueLinkVehicleHandler handler;
        if (usePolling) {
            handler = new PollControlledVehicleHandler(thing);
        } else {
            handler = new DelayCapturingVehicleHandler(thing);
        }

        api = new CompletingCommandApi();
        setApi(handler, api);
        return handler;
    }

    private void waitForPolling(HyundaiBlueLinkVehicleHandler handler) {
        if (handler instanceof PollControlledVehicleHandler pollHandler) {
            ManualScheduledFuture poll = pollHandler.nextPollFuture();
            if (poll != null) {
                poll.run();
            }
        }
    }

    private static ThingBuilder withDistanceChannels(ThingBuilder builder, ThingUID thingUID) {
        builder.withChannel(distanceChannel(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_ODOMETER,
                HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_ODOMETER));
        builder.withChannel(distanceChannel(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_RANGE,
                HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_RANGE));
        builder.withChannel(distanceChannel(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE,
                HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_EV_RANGE));
        builder.withChannel(distanceChannel(thingUID, HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE,
                HyundaiBlueLinkBindingConstants.CHANNEL_TYPE_GAS_RANGE));
        return builder;
    }

    private static Channel distanceChannel(ThingUID thingUID, String channelId, ChannelTypeUID channelTypeUID) {
        return ChannelBuilder.create(new ChannelUID(thingUID, channelId), "Number:Length").withType(channelTypeUID)
                .build();
    }

    private static void setApi(HyundaiBlueLinkVehicleHandler handler, BlueLinkApi api) throws Exception {
        Field field = HyundaiBlueLinkVehicleHandler.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(handler, api);
    }

    private static void invokeScheduleRefreshJob(HyundaiBlueLinkVehicleHandler handler) throws Exception {
        Method method = HyundaiBlueLinkVehicleHandler.class.getDeclaredMethod("scheduleRefreshJob");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private static void invokeEnsureRefreshScheduleUpToDate(HyundaiBlueLinkVehicleHandler handler) throws Exception {
        Method method = HyundaiBlueLinkVehicleHandler.class.getDeclaredMethod("ensureRefreshScheduleUpToDate");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private static int getScheduledRefreshInterval(HyundaiBlueLinkVehicleHandler handler) throws Exception {
        Field field = HyundaiBlueLinkVehicleHandler.class.getDeclaredField("scheduledRefreshIntervalMinutes");
        field.setAccessible(true);
        return field.getInt(handler);
    }

    private static ScheduledFuture<?> getRefreshJobFuture(HyundaiBlueLinkVehicleHandler handler) throws Exception {
        Field field = HyundaiBlueLinkVehicleHandler.class.getDeclaredField("refreshJob");
        field.setAccessible(true);
        return (ScheduledFuture<?>) field.get(handler);
    }

    private static Endpoints dummyEndpoints() {
        Endpoints endpoints = new Endpoints();
        endpoints.ccapi.baseUrl = "http://localhost";
        return endpoints;
    }

    private static class RecordingApi extends BlueLinkApi {
        @org.eclipse.jdt.annotation.Nullable
        String lastStatusVehicleId;
        @org.eclipse.jdt.annotation.Nullable
        String lastStatusVin;
        @org.eclipse.jdt.annotation.Nullable
        String lastLocationVehicleId;
        @org.eclipse.jdt.annotation.Nullable
        String lastLocationVin;

        RecordingApi() {
            super(dummyEndpoints(), mock(OAuthClient.class), new StampProvider(), "1234");
        }

        @Override
        public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) {
            lastStatusVehicleId = vehicleId;
            lastStatusVin = vinHint;
            VehicleStatus status = new VehicleStatus();
            status.vin = vinHint;
            return status;
        }

        @Override
        public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported) {
            lastLocationVehicleId = vehicleId;
            lastLocationVin = vin;
            VehicleLocation location = new VehicleLocation();
            location.latitude = Double.NaN;
            location.longitude = Double.NaN;
            return location;
        }
    }

    private static class CommandRecordingApi extends BlueLinkApi {
        int statusCallCount;
        int locationCallCount;
        int pollCallCount;
        int lockCallCount;
        int unlockCallCount;
        int climateStartCallCount;
        int setChargeLimitCallCount;
        int setTargetTemperatureCallCount;

        boolean lastStartDefrost;
        boolean lastStartHeating;
        boolean lastStartSteering;
        boolean lastStartSideMirror;
        boolean lastStartRearWindow;

        boolean returnMessageIdForStart = false;

        @org.eclipse.jdt.annotation.Nullable
        String lastCommandAction;

        CommandRecordingApi() {
            super(dummyEndpoints(), mock(OAuthClient.class), new StampProvider(), "1234");
        }

        @Override
        public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) {
            statusCallCount++;
            VehicleStatus status = new VehicleStatus();
            status.vin = vinHint;
            return status;
        }

        @Override
        public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported) {
            locationCallCount++;
            VehicleLocation location = new VehicleLocation();
            location.latitude = Double.NaN;
            location.longitude = Double.NaN;
            return location;
        }

        @Override
        public VehicleCommandResponse lock(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "lock";
            lockCallCount++;
            return new VehicleCommandResponse("ccs2/remote/door", "lock", null, null, true, "lock");
        }

        @Override
        public VehicleCommandResponse unlock(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "unlock";
            unlockCallCount++;
            return new VehicleCommandResponse("ccs2/remote/door", "unlock", null, null, true, "unlock");
        }

        @Override
        public VehicleCommandResponse start(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "start";
            climateStartCallCount++;
            String msgId = returnMessageIdForStart ? "job-start" : null;
            return new VehicleCommandResponse("temperature", "start", msgId, null, false, null);
        }

        @Override
        public VehicleCommandResponse start(String vehicleId, String vin, @Nullable Double temperature, boolean defrost,
                boolean heating, boolean steeringWheel, boolean sideMirror, boolean rearWindow, boolean ccs2Supported) {
            lastCommandAction = "start";
            climateStartCallCount++;
            lastStartDefrost = defrost;
            lastStartHeating = heating;
            lastStartSteering = steeringWheel;
            lastStartSideMirror = sideMirror;
            lastStartRearWindow = rearWindow;
            String msgId = returnMessageIdForStart ? "job-start" : null;
            return new VehicleCommandResponse("temperature", "start", msgId, null, false, null);
        }

        @Override
        public VehicleCommandResponse stop(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "stop";

            return new VehicleCommandResponse("temperature", "stop", null, null, false, null);
        }

        @Override
        public VehicleCommandResponse startCharge(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "startCharge";

            return new VehicleCommandResponse("charge", "start", null, null, false, null);
        }

        @Override
        public VehicleCommandResponse stopCharge(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "stopCharge";

            return new VehicleCommandResponse("charge", "stop", null, null, false, null);
        }

        @Override
        public VehicleCommandResponse setChargeLimit(String vehicleId, String vin, int limitAC, int limitDC,
                boolean ccs2Supported) {
            lastCommandAction = "setChargeLimit";
            setChargeLimitCallCount++;
            return new VehicleCommandResponse("charge", "setChargeLimit", null, null, false, null);
        }

        @Override
        public VehicleCommandResponse setTargetTemperature(String vehicleId, String vin, int temp,
                boolean ccs2Supported) {
            lastCommandAction = "setTargetTemperature";
            setTargetTemperatureCallCount++;
            return new VehicleCommandResponse("temperature", "setTargetTemperature", null, null, false, null);
        }

        @Override
        public VehicleCommandResponse setTargetTemperature(String vehicleId, String vin, double temp,
                boolean ccs2Supported) {
            lastCommandAction = "setTargetTemperature";
            setTargetTemperatureCallCount++;
            return new VehicleCommandResponse("temperature", "setTargetTemperature", null, null, false, null);
        }

        @Override
        public boolean pollVehicleCommandResult(String vehicleId, String vin, String messageId) throws Exception {
            pollCallCount++;
            return false;
        }

        @Override
        public VehicleCommandResponse sendVehicleCommand(String vehicleId, String vin, String action,
                VehicleCommandRequest request, boolean ccs2Supported) throws Exception {
            lastCommandAction = action;
            if ("lock".equals(action)) {
                lockCallCount++;
            } else if ("unlock".equals(action)) {
                unlockCallCount++;
            } else if ("start".equals(action)) {
                climateStartCallCount++;
            }
            return new VehicleCommandResponse(request.getLogSegment(ccs2Supported), action, null, null, true, action);
        }
    }

    private static class CompletingCommandApi extends CommandRecordingApi {
        boolean nextReady;

        @Override
        public VehicleCommandResponse lock(String vehicleId, String vin, boolean ccs2Supported) {
            lastCommandAction = "lock";
            lockCallCount++;
            return new VehicleCommandResponse("ccs2/remote/door", "lock", "job-42", null, true, "lock");
        }

        @Override
        public boolean pollVehicleCommandResult(String vehicleId, String vin, String messageId) throws Exception {
            pollCallCount++;
            if (nextReady) {
                nextReady = false;
                return true;
            }
            return false;
        }
    }

    private static class FailingCommandApi extends CompletingCommandApi {
        @Override
        public boolean pollVehicleCommandResult(String vehicleId, String vin, String messageId)
                throws java.io.IOException {
            pollCallCount++;
            throw new java.io.IOException("Bad request (4000)");
        }
    }

    private static class DelayCapturingVehicleHandler extends HyundaiBlueLinkVehicleHandler {
        protected final Map<String, @Nullable State> states = new HashMap<>();
        int commandRefreshScheduleCount;
        long lastCommandRefreshDelaySeconds = -1;
        @Nullable
        ManualScheduledFuture lastScheduledFuture;

        DelayCapturingVehicleHandler(Thing thing) {
            super(thing);
        }

        @Override
        public void updateState(ChannelUID channelUID, State state) {
            states.put(channelUID.getId(), state);
        }

        State state(String channelId) {
            return states.get(channelId);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleCommandRefreshTask(Runnable task, long delaySeconds) {
            commandRefreshScheduleCount++;
            lastCommandRefreshDelaySeconds = delaySeconds;
            lastScheduledFuture = new ManualScheduledFuture(task);
            return lastScheduledFuture;
        }
    }

    private static class PollControlledVehicleHandler extends DelayCapturingVehicleHandler {
        final Deque<ManualScheduledFuture> pollFutures = new ArrayDeque<>();
        final List<Long> pollDelays = new ArrayList<>();

        PollControlledVehicleHandler(Thing thing) {
            super(thing);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleCommandResultPollTask(Runnable task, long delaySeconds) {
            ManualScheduledFuture future = new ManualScheduledFuture(task);
            pollFutures.add(future);
            pollDelays.add(delaySeconds);
            return future;
        }

        ManualScheduledFuture nextPollFuture() {
            return pollFutures.pollFirst();
        }

        int remainingPollFutures() {
            return pollFutures.size();
        }
    }

    private static class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final Runnable task;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualScheduledFuture(Runnable task) {
            this.task = task;
        }

        void run() {
            if (!cancelled) {
                task.run();
                done = true;
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            done = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }
    }

    private static class CapturingVehicleHandler extends HyundaiBlueLinkVehicleHandler {
        private final Map<String, State> states = new HashMap<>();
        Thing lastUpdatedThing;

        CapturingVehicleHandler(Thing thing) {
            super(thing);
        }

        @Override
        public void updateState(ChannelUID channelUID, State state) {
            states.put(channelUID.getId(), state);
        }

        State state(String channelId) {
            return states.get(channelId);
        }

        @Override
        public void updateThing(Thing thing) {
            lastUpdatedThing = thing;
            super.thing = thing;
        }
    }

    private static class StubStatusApi extends BlueLinkApi {
        private final VehicleStatus status;
        private final VehicleLocation location;
        int locationCallCount;

        StubStatusApi(VehicleStatus status, VehicleLocation location) {
            super(dummyEndpoints(), mock(OAuthClient.class), new StampProvider(), "1234");
            this.status = status;
            this.location = location;
        }

        @Override
        public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) {
            return status;
        }

        @Override
        public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported) {
            locationCallCount++;
            return location;
        }
    }

    private static class DynamicIntervalAccountBridgeHandler extends AccountBridgeHandler {
        private volatile int refreshIntervalMinutes;
        private BlueLinkApi api;

        DynamicIntervalAccountBridgeHandler(int refreshIntervalMinutes) {
            super(BridgeBuilder
                    .create(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "dynamic-bridge")
                    .build());
            this.refreshIntervalMinutes = refreshIntervalMinutes;
        }

        void setRefreshInterval(int refreshIntervalMinutes) {
            this.refreshIntervalMinutes = refreshIntervalMinutes;
        }

        void setApi(BlueLinkApi api) {
            this.api = api;
        }

        @Override
        public int refreshIntervalMinutes() {
            return refreshIntervalMinutes;
        }

        @Override
        public BlueLinkApi api() {
            return api;
        }
    }

    private static class RefreshIntervalVehicleHandler extends HyundaiBlueLinkVehicleHandler {
        private final DynamicIntervalAccountBridgeHandler accountHandler;
        final List<Integer> scheduledIntervals = new ArrayList<>();
        @Nullable
        ManualScheduledFuture lastRefreshFuture;
        int immediateRefreshInvocations;

        RefreshIntervalVehicleHandler(Thing thing, DynamicIntervalAccountBridgeHandler accountHandler) {
            super(thing);
            this.accountHandler = accountHandler;
        }

        @Override
        public AccountBridgeHandler getAccountBridgeHandler() {
            return accountHandler;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleVehicleRefreshTask(Runnable task, int intervalMinutes) {
            ManualScheduledFuture future = new ManualScheduledFuture(task);
            scheduledIntervals.add(intervalMinutes);
            lastRefreshFuture = future;
            return future;
        }

        @Override
        public void executeImmediateRefresh(Runnable task) {
            immediateRefreshInvocations++;

        }
    }

    private static class LocationFailureRecordingApi extends BlueLinkApi {
        VehicleStatus nextStatus = new VehicleStatus();
        VehicleLocation nextLocation = new VehicleLocation();
        boolean returnInvalidLocation;
        int locationCallCount;

        LocationFailureRecordingApi() {
            super(dummyEndpoints(), mock(OAuthClient.class), new StampProvider(), "1234");
            nextLocation.latitude = Double.NaN;
            nextLocation.longitude = Double.NaN;
        }

        @Override
        public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) {
            return nextStatus;
        }

        @Override
        public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported)
                throws Exception {
            locationCallCount++;
            if (returnInvalidLocation) {
                VehicleLocation invalid = new VehicleLocation();
                invalid.latitude = Double.NaN;
                invalid.longitude = Double.NaN;
                return invalid;
            }
            return nextLocation;
        }
    }

    private static class TestVehicleHandler extends HyundaiBlueLinkVehicleHandler {
        private final AccountBridgeHandler accountHandler;
        Thing lastUpdatedThing;

        TestVehicleHandler(Thing thing, AccountBridgeHandler accountHandler) {
            super(thing);
            this.accountHandler = accountHandler;
        }

        @Override
        public AccountBridgeHandler getAccountBridgeHandler() {
            return accountHandler;
        }

        @Override
        public void updateThing(Thing thing) {
            lastUpdatedThing = thing;
            super.thing = thing;
        }
    }

    private static class TestAccountBridgeHandler extends AccountBridgeHandler {
        List<VehicleSummary> vehicles = List.of();
        int listCallCount;

        TestAccountBridgeHandler() {
            super(BridgeBuilder.create(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "test-bridge")
                    .build());
        }

        @Override
        public List<VehicleSummary> listVehicles() throws Exception {
            listCallCount++;
            return vehicles;
        }

        @Override
        public @Nullable BlueLinkApi api() {
            return null;
        }
    }

}
