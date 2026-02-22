package org.openhab.binding.hyundaibluelink.internal;

import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.api.Reservation;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandResponse;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for handling vehicle commands.
 */
@NonNullByDefault
public class VehicleCommandManager {
    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(VehicleCommandManager.class));
    private final HyundaiBlueLinkVehicleHandler handler;
    private final Object commandExecutionLock = new Object();
    private boolean commandInProgress;

    private volatile State lastKnownLockState = UnDefType.UNDEF;
    private volatile boolean defrostHeating = false;
    private volatile boolean rearHeating = false;
    private volatile boolean steeringWheelHeating = false;
    private volatile boolean sideMirrorHeating = false;
    private volatile boolean rearWindowHeating = false;
    private @Nullable Double targetTemperatureValue;

    public VehicleCommandManager(HyundaiBlueLinkVehicleHandler handler) {
        this.handler = handler;
    }

    public void handleCommand(ChannelUID channelUID, Command command) {
        if (!handler.ensureApi()) {
            logger.warn("No API available to process command {}", command);
            return;
        }
        BlueLinkApi activeApi = Objects.requireNonNull(handler.getApi());

        String vin = handler.getThing().getUID().getId();
        String vehicleId = handler.resolveVehicleId(vin);
        String channelId = channelUID.getId();
        try {
            VehicleCommandResponse commandResponse = null;
            boolean commandHandled = false;
            PendingCommandContext pendingContext = null;
            switch (channelId) {
                case HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        if (!beginCommandExecution(channelUID, command)) {
                            return;
                        }
                        try {
                            State previousState = lastKnownLockState;
                            if (command == OnOffType.ON) {
                                commandResponse = activeApi.lock(vehicleId, vin, handler.isCcs2Supported());
                                updateLockState(channelUID, OnOffType.ON);
                            } else {
                                commandResponse = activeApi.unlock(vehicleId, vin, handler.isCcs2Supported());
                                updateLockState(channelUID, OnOffType.OFF);
                            }
                            pendingContext = new PendingCommandContext(channelUID,
                                    previousState);
                            commandHandled = true;
                        } catch (Exception e) {
                            completeCommandExecution();
                            throw e;
                        }
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_CONTROL:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        if (!beginCommandExecution(channelUID, command)) {
                            return;
                        }
                        try {
                            if (command == OnOffType.ON) {
                                commandResponse = activeApi.start(vehicleId, vin, targetTemperatureValue,
                                        defrostHeating, rearHeating, steeringWheelHeating, sideMirrorHeating,
                                        rearWindowHeating, handler.isCcs2Supported());
                            } else {
                                commandResponse = activeApi.stop(vehicleId, vin, handler.isCcs2Supported());
                            }
                            handler.updateState(channelUID, (State) command);
                            pendingContext = new PendingCommandContext(channelUID,
                                    UnDefType.UNDEF);
                            commandHandled = true;
                        } catch (Exception e) {
                            completeCommandExecution();
                            throw e;
                        }
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_STARTCHARGE:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        if (!beginCommandExecution(channelUID, command)) {
                            return;
                        }
                        try {
                            if (command == OnOffType.ON) {
                                commandResponse = activeApi.startCharge(vehicleId, vin, handler.isCcs2Supported());
                            } else {
                                commandResponse = activeApi.stopCharge(vehicleId, vin, handler.isCcs2Supported());
                            }
                            handler.updateState(channelUID, (State) command);
                            pendingContext = new PendingCommandContext(channelUID,
                                    UnDefType.UNDEF);
                            commandHandled = true;
                        } catch (Exception e) {
                            completeCommandExecution();
                            throw e;
                        }
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC:
                case HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC:
                    if (command instanceof DecimalType decimal) {
                        int limit = decimal.intValue();
                        int limitAC = HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC.equals(channelId) ? limit
                                : -1;
                        int limitDC = HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC.equals(channelId) ? limit
                                : -1;
                        if (!beginCommandExecution(channelUID, command)) {
                            return;
                        }
                        try {
                            commandResponse = activeApi.setChargeLimit(vehicleId, vin, limitAC, limitDC,
                                    handler.isCcs2Supported());
                            handler.updateState(channelUID, decimal);
                            pendingContext = new PendingCommandContext(channelUID,
                                    UnDefType.UNDEF);
                            commandHandled = true;
                        } catch (Exception e) {
                            completeCommandExecution();
                            throw e;
                        }
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_TARGET_TEMPERATURE:
                    if (command instanceof DecimalType decimal) {
                        if (!beginCommandExecution(channelUID, command)) {
                            return;
                        }
                        try {
                            targetTemperatureValue = decimal.doubleValue();
                            commandResponse = activeApi.setTargetTemperature(vehicleId, vin, targetTemperatureValue,
                                    handler.isCcs2Supported());
                            handler.updateState(channelUID, decimal);
                            pendingContext = new PendingCommandContext(channelUID,
                                    UnDefType.UNDEF);
                            commandHandled = true;
                        } catch (Exception e) {
                            completeCommandExecution();
                            throw e;
                        }
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_FRONT_WINDOW_HEATING:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        defrostHeating = command == OnOffType.ON;
                        handler.updateState(channelUID, (State) command);
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_REAR_WINDOW_HEATING:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        rearHeating = command == OnOffType.ON;
                        handler.updateState(channelUID, (State) command);
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_STEERING_WHEEL:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        steeringWheelHeating = command == OnOffType.ON;
                        handler.updateState(channelUID, (State) command);
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_SIDE_MIRROR:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        sideMirrorHeating = command == OnOffType.ON;
                        handler.updateState(channelUID, (State) command);
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_CLIMATE_HEATING_REAR_WINDOW:
                    if (command == OnOffType.ON || command == OnOffType.OFF) {
                        rearWindowHeating = command == OnOffType.ON;
                        handler.updateState(channelUID, (State) command);
                    }
                    break;
                case HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE:
                case HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST:
                case HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME:
                    if (!beginCommandExecution(channelUID, command)) {
                        return;
                    }
                    try {
                        performReservationChange(activeApi, vehicleId, vin, channelId, command);
                        handler.updateState(channelUID, (State) (command instanceof State ? command : UnDefType.UNDEF)); // Optimistic
                                                                                                                         // update

                        pendingContext = new PendingCommandContext(channelUID, UnDefType.UNDEF);
                        commandHandled = true;
                    } catch (Exception e) {
                        completeCommandExecution();
                        throw e;
                    }
                    break;
                default:
                    logger.debug("Unknown command for channel {}: {}", channelId, command);
                    break;
            }
            if (commandHandled && commandResponse != null) {
                handler.triggerAsyncRefresh(Objects.requireNonNull(vehicleId), Objects.requireNonNull(vin),
                        commandResponse,
                        pendingContext);
            }
        } catch (Exception e) {
            logger.warn("Command execution failed for channel {}: {}", channelId, e.getMessage());
            handler.updateState(channelUID, UnDefType.UNDEF);
            completeCommandExecution();
        }
    }

    public boolean beginCommandExecution(ChannelUID channelUID, Command command) {
        synchronized (commandExecutionLock) {
            if (commandInProgress) {
                logger.warn("Ignoring command {} for {} because another command is still pending", command, channelUID);
                return false;
            }
            commandInProgress = true;
            return true;
        }
    }

    public void completeCommandExecution() {
        synchronized (commandExecutionLock) {
            commandInProgress = false;
        }
    }

    public void updateLockState(ChannelUID channelUID, State state) {
        handler.updateState(channelUID, state);
        lastKnownLockState = state;
    }

    public void updateLockState(State state) {
        handler.updateState(
                new ChannelUID(handler.getThing().getUID(), HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE), state);
        lastKnownLockState = state;
    }

    public State getLastKnownLockState() {
        return lastKnownLockState;
    }

    private void performReservationChange(BlueLinkApi api, String vehicleId, String vin, String channelId,
            Command command) throws Exception {
        // 1. Fetch current reservation
        Reservation current = api.getReservation(vehicleId, vin, handler.isCcs2Supported());
        if (current == null) {
            // Default if none exists / failed to fetch but no exception
            current = new Reservation(false, 7, 0, false); // Default 07:00 AM
        }

        // 2. Modify based on command
        if (channelId.equals(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE)) {
            if (command instanceof OnOffType onOff) {
                current.active = (onOff == OnOffType.ON);
            }
        } else if (channelId.equals(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST)) {
            if (command instanceof OnOffType onOff) {
                current.defrost = (onOff == OnOffType.ON);
            }
        } else if (channelId.equals(HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME)) {
            if (command instanceof DateTimeType dt) {
                @SuppressWarnings("deprecation")
                java.time.ZonedDateTime zdt = dt.getZonedDateTime();
                current.hour = zdt.getHour();
                current.minute = zdt.getMinute();
            }
        }

        // 3. Set new reservation
        api.setReservation(vehicleId, vin, current, handler.isCcs2Supported());
    }
}
