package org.openhab.binding.hyundaibluelink.internal.api;

/**
 * Enumeration of header inclusion modes for the BlueLink API.
 */
enum HeaderInclusion {
    ALL(true, true),
    OMIT_CONTROL_TOKEN_AND_PIN(false, false),
    NO_PIN(true, false);

    private final boolean includeControlToken;
    private final boolean includePin;

    HeaderInclusion(boolean includeControlToken, boolean includePin) {
        this.includeControlToken = includeControlToken;
        this.includePin = includePin;
    }

    boolean shouldIncludeControlToken() {
        return includeControlToken;
    }

    boolean shouldIncludePin() {
        return includePin;
    }
}
