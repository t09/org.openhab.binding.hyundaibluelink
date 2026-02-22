package org.openhab.binding.hyundaibluelink.internal.api;

import java.util.Objects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Represents a JSON response from the BlueLink API.
 */
public class JsonResponse {
    private final int statusCode;
    private final String body;
    private final String bodyForLog;

    public JsonResponse(int statusCode, String body, String bodyForLog) {
        this.statusCode = statusCode;
        this.body = body;
        this.bodyForLog = bodyForLog;
    }

    public JsonObject getBodyAsJson() {
        String b = body;
        if (b != null && !b.isEmpty()) {
            try {
                return Objects.requireNonNull(JsonParser.parseString(b).getAsJsonObject());
            } catch (Exception e) {
                // ignore
            }
        }
        return new JsonObject();
    }

    public String getBody() {
        return body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBodyForLog() {
        return bodyForLog;
    }

    public boolean isSuccessful() {
        return statusCode / 100 == 2;
    }

    public boolean isClientError() {
        return statusCode / 100 == 4;
    }
}
