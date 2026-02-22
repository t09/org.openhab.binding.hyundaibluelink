package org.openhab.binding.hyundaibluelink.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for polling the result of an asynchronous vehicle command.
 */
@NonNullByDefault
public class CommandResultPoller implements Runnable {
    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(CommandResultPoller.class));
    private final HyundaiBlueLinkVehicleHandler handler;
    private final String vehicleId;
    private final String vin;
    private @Nullable String messageId;
    private @Nullable PendingCommandContext pendingCommand;
    private long deadlineNanos;
    private @Nullable volatile ScheduledFuture<?> future;
    private boolean disposed;

    public CommandResultPoller(HyundaiBlueLinkVehicleHandler handler, String vehicleId, String vin,
            VehicleCommandResponse response,
            @Nullable PendingCommandContext pendingCommand,
            long timeoutSeconds) {
        this.handler = handler;
        this.vehicleId = vehicleId;
        this.vin = vin;
        this.messageId = response.getMessageId();
        this.pendingCommand = pendingCommand;
        this.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    public void scheduleInitial(long intervalSeconds) {
        scheduleNext(intervalSeconds);
    }

    public void cancel() {
        disposed = true;
        ScheduledFuture<?> localFuture;
        synchronized (this) {
            localFuture = future;
            future = null;
        }
        if (localFuture != null) {
            localFuture.cancel(false);
        }
    }

    @Override
    public void run() {
        if (!isStillActive()) {
            return;
        }
        try {
            BlueLinkApi activeApi = handler.getApi();
            if (activeApi == null) {
                if (!rescheduleIfTimeLeft()) {
                    handleFailure("API not available");
                }
                return;
            }
            boolean ready = activeApi.pollVehicleCommandResult(vehicleId, vin, messageId);
            if (ready) {
                handleSuccess();
                return;
            }
        } catch (IOException e) {
            String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;
            String messageIdForLog = messageId != null ? messageId : "UNKNOWN";
            String message = e.getMessage();
            logger.warn("Command result {} for {} failed: {}", messageIdForLog, vinForLog, message);
            handleFailure(message != null ? message : "IOException");
            return;
        } catch (Exception e) {
            logger.debug("Polling command result {} for {} failed: {}", messageId, vin, e.getMessage());
        }

        if (!rescheduleIfTimeLeft()) {
            handleFailure("Timeout");
        }
    }

    private void handleSuccess() {
        complete();
        handler.handleCommandResultPollCompletion(CommandResultPollOutcome.SUCCESS,
                pendingCommand);
    }

    private void handleFailure(String reason) {
        if (!disposed) {
            logger.warn("Command result {} for {} not received or failed: {}; completing pending operation", messageId,
                    vin, reason);
        }
        complete();
        handler.handleCommandResultPollCompletion(CommandResultPollOutcome.FAILURE,
                pendingCommand);
    }

    private boolean rescheduleIfTimeLeft() {
        if (System.nanoTime() >= deadlineNanos || disposed) {
            return false;
        }
        scheduleNext(5); // COMMAND_RESULT_POLL_INTERVAL_SECONDS
        return true;
    }

    private void scheduleNext(long delaySeconds) {
        if (disposed) {
            return;
        }
        ScheduledFuture<?> next = handler.scheduleCommandResultPollTask(this, delaySeconds);
        synchronized (this) {
            if (disposed) {
                if (next != null) {
                    next.cancel(false);
                }
                return;
            }
            future = next;
        }
    }

    private boolean isStillActive() {
        return !disposed && handler.isPollerActive(this);
    }

    private void complete() {
        disposed = true;
        handler.onPollerCompleted(this);
    }
}
