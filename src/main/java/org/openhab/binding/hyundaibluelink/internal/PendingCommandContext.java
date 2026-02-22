package org.openhab.binding.hyundaibluelink.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.State;

/**
 * Context for a pending command, used to restore state on failure.
 */
@NonNullByDefault
public final class PendingCommandContext {
    private final ChannelUID channelUID;
    private final State previousState;

    public PendingCommandContext(ChannelUID channelUID, State previousState) {
        this.channelUID = channelUID;
        this.previousState = previousState;
    }

    public ChannelUID getChannelUID() {
        return channelUID;
    }

    public State getPreviousState() {
        return previousState;
    }
}
