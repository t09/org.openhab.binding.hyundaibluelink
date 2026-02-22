package org.openhab.binding.hyundaibluelink.internal.model;

import org.eclipse.jdt.annotation.Nullable;
import com.google.gson.JsonObject;

import org.eclipse.jdt.annotation.NonNullByDefault;

@NonNullByDefault
public class VehicleCommandResponse {
    private final @Nullable String controlSegment;
    private final String action;
    private final @Nullable String messageId;
    private final @Nullable JsonObject responseBody;
    private final boolean remoteDoor;
    private final @Nullable String remoteDoorAction;

    public VehicleCommandResponse(@Nullable String controlSegment, String action, @Nullable String messageId,
            @Nullable JsonObject responseBody, boolean remoteDoor, @Nullable String remoteDoorAction) {
        this.controlSegment = controlSegment;
        this.action = action;
        this.messageId = messageId;
        this.responseBody = responseBody;
        this.remoteDoor = remoteDoor;
        this.remoteDoorAction = remoteDoorAction;
    }

    public @Nullable String getControlSegment() {
        return controlSegment;
    }

    public String getAction() {
        return action;
    }

    public @Nullable String getMessageId() {
        return messageId;
    }

    public @Nullable JsonObject getResponseBody() {
        return responseBody;
    }

    public boolean isRemoteDoor() {
        return remoteDoor;
    }

    public @Nullable String getRemoteDoorAction() {
        return remoteDoorAction;
    }
}
