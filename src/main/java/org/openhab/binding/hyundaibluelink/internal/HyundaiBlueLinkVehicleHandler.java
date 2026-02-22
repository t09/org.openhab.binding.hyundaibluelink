package org.openhab.binding.hyundaibluelink.internal;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.model.*;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HyundaiBlueLinkVehicleHandler} is responsible for handling
 * commands, which are
 * sent to one of the channels.
 *
 * @author Jochen Meisner - Initial contribution
 */
@NonNullByDefault
public class HyundaiBlueLinkVehicleHandler extends BaseThingHandler {

    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(HyundaiBlueLinkVehicleHandler.class));
    private static final long COMMAND_REFRESH_DELAY_SECONDS = 30;
    private static final long COMMAND_RESULT_TIMEOUT_SECONDS = 60;
    private static final long COMMAND_RESULT_POLL_INTERVAL_SECONDS = 5;

    private @Nullable BlueLinkApi api;
    private @Nullable ScheduledFuture<?> refreshJob;
    private volatile int scheduledRefreshIntervalMinutes = Integer.MIN_VALUE;
    private @Nullable ScheduledFuture<?> commandRefreshFuture;
    private @Nullable ScheduledFuture<?> channelInitFuture;
    private boolean commandRefreshPendingDuringPoll;
    private boolean commandRefreshBlockedForPoll;
    private final Object commandRefreshLock = new Object();
    private final Object commandResultLock = new Object();
    private @Nullable CommandResultPoller activeCommandResultPoller;
    private volatile boolean disposed;

    private final VehicleIdResolver idResolver;
    private final VehicleStatusManager statusManager;
    private final VehicleCommandManager commandManager;

    public HyundaiBlueLinkVehicleHandler(Thing thing) {
        super(thing);
        this.idResolver = new VehicleIdResolver(this);
        this.statusManager = new VehicleStatusManager(this);
        this.commandManager = new VehicleCommandManager(this);
    }

    @Override
    public void initialize() {
        disposed = false;
        idResolver.resetFallbackLogged();
        updateStatus(ThingStatus.ONLINE);
        logger.debug("HyundaiBlueLinkVehicleHandler initialized and set to ONLINE");
        resolveApiFromBridge();

        ScheduledExecutorService localScheduler = scheduler;
        if (localScheduler != null) {
            channelInitFuture = localScheduler.schedule(this::initializeChannels, 5, TimeUnit.SECONDS);
        }

        scheduleRefreshJob();
        scheduleInitialDataPoll();
    }

    @Override
    public void dispose() {
        disposed = true;
        cancelRefreshJob();
        cancelPendingCommandRefresh();
        cancelActiveCommandResultPoller();
        if (channelInitFuture != null) {
            channelInitFuture.cancel(false);
            channelInitFuture = null;
        }
        commandManager.completeCommandExecution();
        synchronized (commandRefreshLock) {
            commandRefreshPendingDuringPoll = false;
            commandRefreshBlockedForPoll = false;
        }
        api = null;
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            statusManager.refreshVehicleData();
            return;
        }
        commandManager.handleCommand(channelUID, command);
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    @Override
    public void updateThing(Thing thing) {
        super.updateThing(thing);
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        @Nullable
        String oldType = getThing().getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_TYPE);
        @Nullable
        String newType = thing.getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_TYPE);
        if (!Objects.equals(oldType, newType)) {
            initializeChannels();
        }
    }

    @Override
    public ThingBuilder editThing() {
        return super.editThing();
    }

    public void triggerAsyncRefresh(String vehicleId, String vin, VehicleCommandResponse response,
            @Nullable PendingCommandContext pendingCommand) {
        if (response != null) {
            String messageId = response.getMessageId();
            if (messageId != null && !messageId.isBlank()) {
                blockCommandRefreshForPoll();
                scheduleCommandRefresh();
                scheduleCommandResultPoll(vehicleId, vin, response, pendingCommand);
                return;
            }
        }
        scheduleCommandRefresh();
    }

    public void scheduleCommandRefresh() {
        if (disposed) {
            return;
        }
        synchronized (commandRefreshLock) {
            if (commandRefreshBlockedForPoll || isCommandResultPollActive()) {
                commandRefreshPendingDuringPoll = true;
                return;
            }
            if (commandRefreshFuture != null && !commandRefreshFuture.isDone()) {
                return;
            }
            Runnable task = () -> {
                try {
                    statusManager.refreshVehicleData();
                } finally {
                    clearPendingCommandRefresh();
                    commandManager.completeCommandExecution();
                }
            };
            onCommandRefreshScheduled(COMMAND_REFRESH_DELAY_SECONDS);
            commandRefreshFuture = scheduleCommandRefreshTask(task, COMMAND_REFRESH_DELAY_SECONDS);
        }
    }

    public boolean shouldDeferRefreshDuringCommandPoll() {
        synchronized (commandRefreshLock) {
            if (commandRefreshBlockedForPoll || isCommandResultPollActive()) {
                commandRefreshPendingDuringPoll = true;
                return true;
            }
        }
        return false;
    }

    private void blockCommandRefreshForPoll() {
        synchronized (commandRefreshLock) {
            commandRefreshBlockedForPoll = true;
            commandRefreshPendingDuringPoll = true;
        }
    }

    public void unblockCommandRefreshAfterPoll() {
        synchronized (commandRefreshLock) {
            commandRefreshBlockedForPoll = false;
        }
    }

    public void schedulePendingCommandRefreshAfterPoll() {
        boolean shouldSchedule;
        synchronized (commandRefreshLock) {
            shouldSchedule = commandRefreshPendingDuringPoll;
            commandRefreshPendingDuringPoll = false;
        }
        if (shouldSchedule) {
            scheduleCommandRefresh();
        }
    }

    public void scheduleCommandResultPoll(String vehicleId, String vin, VehicleCommandResponse response,
            @Nullable PendingCommandContext pendingCommand) {
        if (disposed) {
            return;
        }
        cancelActiveCommandResultPoller();
        CommandResultPoller poller = new CommandResultPoller(this, vehicleId, vin, response, pendingCommand,
                COMMAND_RESULT_TIMEOUT_SECONDS);
        synchronized (commandResultLock) {
            activeCommandResultPoller = poller;
        }
        poller.scheduleInitial(COMMAND_RESULT_POLL_INTERVAL_SECONDS);
    }

    public boolean isCommandResultPollActive() {
        synchronized (commandResultLock) {
            return activeCommandResultPoller != null;
        }
    }

    private void cancelPendingCommandRefresh() {
        synchronized (commandRefreshLock) {
            if (commandRefreshFuture != null) {
                commandRefreshFuture.cancel(false);
                commandRefreshFuture = null;
            }
        }
    }

    public void cancelPendingCommandRefreshPublic() {
        cancelPendingCommandRefresh();
    }

    private void clearPendingCommandRefresh() {
        synchronized (commandRefreshLock) {
            commandRefreshFuture = null;
        }
    }

    private void cancelActiveCommandResultPoller() {
        CommandResultPoller poller;
        synchronized (commandResultLock) {
            poller = activeCommandResultPoller;
            activeCommandResultPoller = null;
        }
        if (poller != null) {
            poller.cancel();
        }
    }

    protected void onCommandRefreshScheduled(long delaySeconds) {
        // hook for tests
    }

    @SuppressWarnings("null")
    protected ScheduledFuture<?> scheduleCommandRefreshTask(Runnable task, long delaySeconds) {
        return scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

    @SuppressWarnings("null")
    public ScheduledFuture<?> scheduleCommandResultPollTask(Runnable task, long delaySeconds) {
        return scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

    public boolean isPollerActive(CommandResultPoller poller) {
        synchronized (commandResultLock) {
            return activeCommandResultPoller == poller;
        }
    }

    public void onPollerCompleted(CommandResultPoller poller) {
        synchronized (commandResultLock) {
            if (activeCommandResultPoller == poller) {
                activeCommandResultPoller = null;
            }
        }
    }

    public void handleCommandResultPollCompletion(CommandResultPollOutcome outcome,
            @Nullable PendingCommandContext context) {
        try {
            if (outcome == CommandResultPollOutcome.SUCCESS) {
                refreshVehicleDataAfterCommandCompletion();
            } else {
                if (context != null) {
                    restorePendingCommandState(context);
                }
                commandManager.completeCommandExecution();
            }
        } finally {
            unblockCommandRefreshAfterPoll();
            schedulePendingCommandRefreshAfterPoll();
        }
    }

    public void restorePendingCommandState(@Nullable PendingCommandContext context) {
        if (context == null) {
            return;
        }
        ChannelUID channelUID = context.getChannelUID();

        State previous = context.getPreviousState();
        State targetState = previous != null ? previous : UnDefType.UNDEF;
        if (HyundaiBlueLinkBindingConstants.CHANNEL_LOCK_STATE.equals(channelUID.getId())) {
            commandManager.updateLockState(channelUID, targetState);
        } else {
            updateState(channelUID, targetState);
        }
    }

    private void refreshVehicleDataAfterCommandCompletion() {
        try {
            statusManager.refreshVehicleData();
        } finally {
            commandManager.completeCommandExecution();
        }
    }

    private void resolveApiFromBridge() {
        AccountBridgeHandler bridgeHandler = getAccountBridgeHandler();
        if (bridgeHandler != null) {
            api = bridgeHandler.api();
        }
    }

    public boolean ensureApi() {
        if (api != null) {
            return true;
        }
        resolveApiFromBridge();
        return api != null;
    }

    private int getRefreshIntervalMinutes() {
        AccountBridgeHandler bridgeHandler = getAccountBridgeHandler();
        if (bridgeHandler != null) {
            return bridgeHandler.refreshIntervalMinutes();
        }
        return 60;
    }

    private void scheduleRefreshJob() {
        int interval = getRefreshIntervalMinutes();
        scheduleRefreshJob(interval);
    }

    private void scheduleRefreshJob(int intervalMinutes) {
        cancelRefreshJob();
        scheduledRefreshIntervalMinutes = intervalMinutes;
        if (intervalMinutes <= 0) {
            logger.debug("Automatic refresh disabled for {}", getThing().getUID());
            executeImmediateRefresh(statusManager::refreshVehicleData);
            return;
        }
        refreshJob = scheduleVehicleRefreshTask(statusManager::refreshVehicleData, intervalMinutes);
    }

    @SuppressWarnings("null")
    protected ScheduledFuture<?> scheduleVehicleRefreshTask(Runnable task, int intervalMinutes) {
        return scheduler.scheduleWithFixedDelay(task, 0, intervalMinutes, TimeUnit.MINUTES);
    }

    protected void executeImmediateRefresh(Runnable task) {
        scheduler.execute(task);
    }

    private void scheduleInitialDataPoll() {
        scheduler.execute(statusManager::performInitialVehicleBootstrapPolls);
    }

    private void cancelRefreshJob() {
        if (refreshJob != null) {
            refreshJob.cancel(true);
            refreshJob = null;
        }
    }

    public void ensureRefreshScheduleUpToDate() {
        int interval = getRefreshIntervalMinutes();
        if (interval != scheduledRefreshIntervalMinutes) {
            if (scheduledRefreshIntervalMinutes == Integer.MIN_VALUE && refreshJob == null) {
                scheduledRefreshIntervalMinutes = interval;
                return;
            }
            logger.debug("Detected refresh interval change for {} ({} -> {})", getThing().getUID(),
                    Integer.valueOf(scheduledRefreshIntervalMinutes), Integer.valueOf(interval));
            scheduleRefreshJob(interval);
        }
    }

    public String resolveVehicleId(String vin) {
        return idResolver.resolveVehicleId(vin);
    }

    public void updateLockState(State state) {
        commandManager.updateLockState(state);
    }

    public @Nullable AccountBridgeHandler getAccountBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof AccountBridgeHandler handler) {
            return handler;
        }
        return null;
    }

    public @Nullable BlueLinkApi getApi() {
        return api;
    }

    public boolean isDisposed() {
        return disposed;
    }

    @SuppressWarnings("null")
    public boolean isCcs2Supported() {
        String val = getThing().getProperties()
                .get(HyundaiBlueLinkBindingConstants.PROPERTY_CCU_CCS2_PROTOCOL_SUPPORT);
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    public boolean isCcsp() {
        @SuppressWarnings("null")
        String val = getThing().getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_PROTOCOL_TYPE);
        return "2".equals(val);
    }

    public void scheduleRefreshTask(Runnable task, long delay, TimeUnit unit) {
        ScheduledExecutorService localScheduler = scheduler;
        if (localScheduler != null) {
            localScheduler.schedule(task, delay, unit);
        }
    }

    private void initializeChannels() {
        if (disposed) {
            return;
        }
        @Nullable
        String type = getThing().getProperties().get(HyundaiBlueLinkBindingConstants.PROPERTY_VEHICLE_TYPE);
        if (type == null || type.isBlank()) {
            return;
        }

        logger.debug("Initializing channels for vehicle type: {}", type);
        Set<String> toRemove = getChannelsToRemove(type);
        if (toRemove.isEmpty()) {
            return;
        }

        List<Channel> currentChannels = getThing().getChannels();
        List<Channel> filteredChannels = currentChannels.stream()
                .filter(c -> !toRemove.contains(c.getUID().getId()))
                .collect(Collectors.toList());

        if (filteredChannels.size() != currentChannels.size()) {
            logger.info("Dynamically removing {} channels for vehicle type {}",
                    Integer.valueOf(currentChannels.size() - filteredChannels.size()), type);
            updateThing(editThing().withChannels(filteredChannels).build());
        }
    }

    private Set<String> getChannelsToRemove(String type) {
        String t = type.toUpperCase(java.util.Locale.ROOT);
        Set<String> remove = new java.util.HashSet<>();

        // Shared lists
        List<String> chargingChannels = List.of(
                HyundaiBlueLinkBindingConstants.CHANNEL_STARTCHARGE,
                HyundaiBlueLinkBindingConstants.CHANNEL_CHARGING_STATE,
                HyundaiBlueLinkBindingConstants.CHANNEL_REMAINING_CHARGE_TIME,
                HyundaiBlueLinkBindingConstants.CHANNEL_CONNECTOR_FASTENED,
                HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_AC,
                HyundaiBlueLinkBindingConstants.CHANNEL_CHARGE_LIMIT_DC,
                HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_TIME,
                HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_ACTIVE,
                HyundaiBlueLinkBindingConstants.CHANNEL_RESERVATION_DEFROST);

        List<String> fuelChannels = List.of(
                HyundaiBlueLinkBindingConstants.CHANNEL_FUEL_LEVEL,
                HyundaiBlueLinkBindingConstants.CHANNEL_GAS_MODE_RANGE);

        List<String> evChannels = List.of(
                HyundaiBlueLinkBindingConstants.CHANNEL_BATTERY_LEVEL,
                HyundaiBlueLinkBindingConstants.CHANNEL_EV_MODE_RANGE);

        if ("EV".equals(t)) {
            remove.addAll(fuelChannels);
        } else if ("ICE".equals(t) || "GAS".equals(t) || "FCEV".equals(t)) {
            remove.addAll(evChannels);
            remove.addAll(chargingChannels);
        } else if ("HEV".equals(t)) {
            remove.addAll(chargingChannels);
        }
        // PHEV keeps everything

        return remove;
    }
}
