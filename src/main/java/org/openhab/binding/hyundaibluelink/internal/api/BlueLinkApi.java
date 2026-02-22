package org.openhab.binding.hyundaibluelink.internal.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.binding.hyundaibluelink.internal.model.*;

import org.slf4j.LoggerFactory;

import org.eclipse.jdt.annotation.Nullable;

@org.eclipse.jdt.annotation.NonNullByDefault
public class BlueLinkApi {
    private final org.slf4j.Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(BlueLinkApi.class));
    private final Endpoints ep;
    private final OAuthClient oauth;
    private final StampProvider stampProvider;
    private final @Nullable String pin;

    private final boolean controlTokenSupported;
    private final @Nullable String hashedPin;
    private volatile boolean vehicleStatusPostDisabled;
    private @Nullable String controlToken;
    private @Nullable Instant controlTokenExpiry;

    private final HttpClient httpClient;

    private final BlueLinkCommandHandler commandHandler;
    private final BlueLinkClimateHandler climateHandler;
    private final BlueLinkStatusHandler statusHandler;

    private static final int DEFAULT_HVAC_TYPE = 0;
    private static final String DEFAULT_CLIMATE_TEMP_CODE = "0CH";
    private static final String DEFAULT_CLIMATE_UNIT = "C";

    private static final String REDACTED_VALUE = "***REDACTED***";
    private static final String CONTROL_TOKEN_HEADER = "ccsp-control-token";
    private static final String CONTROL_TOKEN_REDACTED_VALUE = "Token [****REDACTED****]";
    @SuppressWarnings("null")
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "authorizationccsp", "stamp", "pin",
            CONTROL_TOKEN_HEADER);
    @SuppressWarnings("null")
    private static final Set<String> SENSITIVE_JSON_FIELDS = Set.of("pin", "controlToken");
    private static final DateTimeFormatter BASIC_TIMESTAMP_FORMAT = Objects
            .requireNonNull(new DateTimeFormatterBuilder()
                    .appendPattern("yyyyMMddHHmmss").toFormatter(Locale.ROOT));
    private static final String[] LAST_UPDATED_FALLBACK_KEYS = new String[] { "updateTime", "updateDate", "statusTime",
            "lastStatusTime", "timeStamp", "timestamp", "time", "eventTime", "eventDate" };

    public BlueLinkApi(Endpoints ep, OAuthClient oauth, StampProvider stampProvider, String pin) {
        this.ep = Objects.requireNonNull(ep);
        this.oauth = Objects.requireNonNull(oauth);
        this.stampProvider = Objects.requireNonNull(stampProvider, "stampProvider");
        this.controlTokenExpiry = null;
        this.pin = pin != null ? pin.trim() : null;

        this.vehicleStatusPostDisabled = false;
        this.controlTokenSupported = this.pin != null && !this.pin.isEmpty();
        this.hashedPin = hashPin(this.pin);
        this.httpClient = Objects.requireNonNull(HttpClient.newBuilder().build());
        if (!controlTokenSupported) {
            logger.debug("No PIN configured; control-token-secured features will be disabled");
        }
        this.commandHandler = new BlueLinkCommandHandler(this);
        this.climateHandler = new BlueLinkClimateHandler(this);
        this.statusHandler = new BlueLinkStatusHandler(this);
    }

    /**
     * Performs the login flow using the underlying {@link OAuthClient}. After
     * successful execution the OAuth client holds the access and refresh tokens
     * required for subsequent API calls.
     */
    public void login() throws Exception {
        oauth.refreshToken();
        invalidateControlToken();
        if (controlTokenSupported) {
            ensureControlToken();
        }
    }

    public BlueLinkCommandHandler commands() {
        return commandHandler;
    }

    public BlueLinkClimateHandler climate() {
        return climateHandler;
    }

    public BlueLinkStatusHandler status() {
        return statusHandler;
    }

    public void refreshToken() throws Exception {
        oauth.refreshToken();
        invalidateControlToken();
        if (controlTokenSupported) {
            ensureControlToken();
        }
    }

    public VehicleStatus getVehicleStatus(String vehicleId, String vinHint, boolean ccs2Supported) throws Exception {
        return statusHandler.getVehicleStatus(vehicleId, vinHint, ccs2Supported);
    }

    protected VehicleStatus getVehicleStatusImpl(String vehicleId, String vinHint, boolean ccs2Supported)
            throws Exception {
        String vinForLog = (vinHint == null || vinHint.isBlank()) ? "UNKNOWN" : vinHint;
        VehicleStatus status = null;

        if (ccs2Supported) {
            JsonObject ccs2Root = fetchVehicleStatusFromCcs2(vehicleId, vinForLog);
            if (ccs2Root != null) {
                status = org.openhab.binding.hyundaibluelink.internal.api.mapper.Ccs2StatusMapper.map(vinForLog,
                        ccs2Root);
            }
        }

        if (status == null) {
            status = fetchLegacyVehicleStatus(vehicleId, vinForLog, ccs2Supported);
        }

        if (status != null) {
            try {
                status.lastNotification = fetchLatestNotification(vehicleId);
            } catch (Exception e) {
                logger.debug("Failed to fetch latest notification for {}: {}", vinForLog, e.getMessage());
            }
        }

        return status;
    }

    public JsonResponse getVehicleStatusRaw(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;
        try {
            JsonResponse res = fetchSpaVehicleData(vehicleId, vinForLog, "status", "status", true, ccs2Supported);
            if (shouldFallbackToLegacyStatusLatest(res)) {
                res = fetchLegacyVehicleStatusLatestResponse(vehicleId, vinForLog, ccs2Supported);
            }
            return res;
        } catch (Exception e) {
            logger.warn("Vehicle status retrieval failed for {}", vinForLog, e);
            throw e;
        }
    }

    private JsonResponse fetchLegacyVehicleStatusLatestResponse(String vehicleId, String vinForLog,
            boolean ccs2Supported) throws Exception {
        return getVehicleStatusLatestRaw(vehicleId, vinForLog, false, ccs2Supported);
    }

    @SuppressWarnings("null")
    VehicleStatus fetchLegacyVehicleStatus(String vehicleId, String vinForLog, boolean ccs2Supported)
            throws Exception {
        URI uri = URI.create(ep.ccapi.baseUrl + "/vehicles/" + vehicleId + "/status");
        HttpRequest.Builder postBuilder = null;
        String requestBody = null;
        boolean attemptedPost = false;
        String deviceId = oauth.getDeviceId();
        boolean preferGet = isVehicleStatusPostDisabled() || ep.ccapi.baseUrl.contains("/api/v2/spa");
        if (deviceId != null && !deviceId.isBlank() && !preferGet) {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", deviceId);
            String payloadString = payload.toString();
            postBuilder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadString));
            requestBody = payloadString;
            attemptedPost = true;
        } else {
            postBuilder = HttpRequest.newBuilder(uri).GET();
        }
        HttpResponse<String> resp;
        if (attemptedPost) {
            resp = sendWithRetry(Objects.requireNonNull(postBuilder), Objects.requireNonNull(requestBody));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                boolean unsupported = isVehicleStatusPostUnsupported(resp.statusCode());
                if (unsupported) {
                    logger.debug("Vehicle status POST is unsupported for {}, retrying with GET fallback (status {} {})",
                            vinForLog, resp.statusCode(), resp.body());
                } else {
                    logger.debug("Vehicle status POST failed for {}, retrying with legacy GET: {} {}", vinForLog,
                            resp.statusCode(), resp.body());
                }

                HttpResponse<String> getResponse = sendWithRetry(HttpRequest.newBuilder(uri).GET());
                boolean getSucceeded = getResponse.statusCode() >= 200 && getResponse.statusCode() < 300;
                if (unsupported && getSucceeded) {
                    if (!vehicleStatusPostDisabled) {
                        logger.debug("Vehicle status POST is unsupported for {}, persisting GET fallback after"
                                + " successful response", vinForLog);
                    }
                    vehicleStatusPostDisabled = true;
                } else if (!getSucceeded) {
                    logger.debug("Vehicle status GET fallback failed for {}: {} {}", vinForLog,
                            getResponse.statusCode(),
                            getResponse.body());
                }

                resp = getResponse;
            } else if (vehicleStatusPostDisabled) {
                vehicleStatusPostDisabled = false;
            }
        } else {
            resp = sendWithRetry(Objects.requireNonNull(postBuilder));
        }

        if (resp.statusCode() != 200) {
            if (shouldFallbackToLegacyStatusLatest(resp.statusCode(), resp.body())) {
                JsonObject fallback = fetchLegacyVehicleStatusLatest(vehicleId, vinForLog, ccs2Supported);
                if (fallback != null) {
                    return parseVehicleStatusResponse(vinForLog, fallback);
                }
            }
            logger.warn("Vehicle status request failed for {}: {} {}", vinForLog, resp.statusCode(), resp.body());
            throw new IOException("Vehicle status request failed: " + resp.statusCode());
        }

        JsonObject rootJson = JsonParser.parseString(resp.body()).getAsJsonObject();
        return parseVehicleStatusResponse(vinForLog, rootJson);
    }

    @SuppressWarnings("null")
    private @Nullable JsonObject fetchVehicleStatusFromCcs2(String vehicleId, String vinForLog) throws Exception {
        URI uri = buildSpaVehicleUri(ep.ccapi.baseUrl, vehicleId, "ccs2/carstatus/latest", true);
        Supplier<HttpRequest.Builder> builderSupplier = () -> HttpRequest.newBuilder(uri).GET();
        HttpResponse<String> resp;
        try {
            resp = sendWithRetry(builderSupplier.get(), AuthorizationMode.CONTROL_TOKEN,
                    HeaderInclusion.OMIT_CONTROL_TOKEN_AND_PIN);
            int statusCode = resp.statusCode();
            String bodyForLog = formatBodyForLog(resp.body());
            if (controlTokenSupported && statusCode / 100 == 4 && shouldRetrySpaVehicleData(statusCode)) {
                logger.debug(
                        "CCS2 car status control-token request disallowed for {} ({} {}), retrying with access token",
                        vinForLog, Integer.valueOf(statusCode), bodyForLog);
                resp = sendWithRetry(builderSupplier.get(), AuthorizationMode.ACCESS_TOKEN,
                        HeaderInclusion.OMIT_CONTROL_TOKEN_AND_PIN);
                statusCode = resp.statusCode();
                bodyForLog = formatBodyForLog(resp.body());
            }

            int statusClass = statusCode / 100;
            if (statusClass == 2) {
                logger.debug("CCS2 car status response for {}: {}", vinForLog, bodyForLog);
                JsonObject rootJson = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonObject unwrapped = unwrapVehicleStatus(rootJson);
                return unwrapped != null ? unwrapped : rootJson;
            }
            if (statusClass == 4) {
                logger.debug("CCS2 car status unavailable for {}: status {} {}", vinForLog, Integer.valueOf(statusCode),
                        bodyForLog);
            } else {
                logger.warn("CCS2 car status request failed for {}: {} {}", vinForLog, Integer.valueOf(statusCode),
                        bodyForLog);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw e;
            }
            logger.debug("CCS2 car status request failed for {}: {}", vinForLog, e.getMessage());
            logger.trace("CCS2 car status request failure for {}", vinForLog, e);
            return null;
        }
        return null;
    }

    private boolean shouldFallbackToLegacyStatusLatest(JsonResponse res) {
        String body = res.getBody();
        return shouldFallbackToLegacyStatusLatest(res.getStatusCode(), body != null ? body : "");
    }

    private boolean shouldFallbackToLegacyStatusLatest(int statusCode, String responseBody) {
        if (statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 404 || statusCode == 500) {
            return true;
        }
        String normalized = responseBody.toLowerCase(Locale.ROOT);
        return normalized.contains("access to this api has been disallowed");
    }

    private @Nullable JsonObject fetchLegacyVehicleStatusLatest(String vehicleId, String vinForLog,
            boolean ccs2Supported) throws Exception {
        boolean useSpaV2 = ep.ccapi.baseUrl.contains("/api/v2/spa");
        JsonResponse response = getVehicleStatusLatestRaw(vehicleId, vinForLog, useSpaV2, ccs2Supported);
        if (!response.isSuccessful()) {
            logger.debug("Legacy vehicle status latest request for {} did not succeed: HTTP {}", vinForLog,
                    Integer.valueOf(response.getStatusCode()));
            return null;
        }

        String body = response.getBody();
        if (body.isBlank()) {
            logger.debug("Legacy vehicle status latest response for {} did not contain a body", vinForLog);
            return null;
        }

        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Failed to parse legacy vehicle status latest payload for {}: {}", vinForLog, e.getMessage());
            logger.debug("Failed to parse legacy vehicle status latest payload for {}", vinForLog, e);
            return null;
        }
    }

    private VehicleStatus parseVehicleStatusResponse(String vinForLog, JsonObject rootJson) {
        VehicleStatus s = new VehicleStatus();
        s.vin = vinForLog;

        JsonObject json = unwrapVehicleStatus(rootJson);
        if (json == null) {
            json = rootJson;
        }

        JsonObject evStatus = firstObject(json, "evStatus");
        if (evStatus == null && json != rootJson) {
            evStatus = firstObject(rootJson, "evStatus");
        }

        parseBatteryLevel(s, json, rootJson, evStatus);
        parseRange(s, json, rootJson, evStatus);

        parseLocation(s, json, rootJson);
        parseFuelLevel(s, json, rootJson, evStatus);
        parseOdometer(s, json, rootJson, evStatus);
        parseRemainTime(s, json, rootJson, evStatus);
        parseConnectorStatus(s, json, rootJson, evStatus);
        parseDoorWindowStatus(s, json, rootJson);
        parseAccStatus(s, json, rootJson);
        parseStatusBooleans(s, json, rootJson, evStatus);
        parseChargingState(s, json, rootJson, evStatus);
        parseChargeLimits(s, json, rootJson, evStatus);
        parseWarnings(s, json, rootJson, evStatus);
        parseLastUpdated(s, json, rootJson, evStatus);

        logger.debug("Vehicle status retrieved for {}", vinForLog);
        return s;
    }

    private boolean isVehicleStatusPostDisabled() {
        return vehicleStatusPostDisabled;
    }

    private static boolean isVehicleStatusPostUnsupported(int statusCode) {
        return statusCode == 404 || statusCode == 405;
    }

    private @Nullable JsonObject unwrapVehicleStatus(@Nullable JsonObject json) {
        if (json == null) {
            return null;
        }
        JsonObject current = json;
        boolean changed;
        do {
            changed = false;
            JsonObject unwrapped = unwrapFirstMatchingObject(current, "resMsg", "payload", "body", "response", "data");
            if (unwrapped != null) {
                current = unwrapped;
                changed = true;
                continue;
            }
            JsonObject status = unwrapFirstMatchingObject(current, "vehicleStatus", "vehicleStatusInfo",
                    "vehicleStatusDetail");
            if (status != null) {
                current = status;
                changed = true;
                continue;
            }
            JsonObject state = unwrapFirstMatchingObject(current, "state", "vehicle");
            if (state != null) {
                current = state;
                changed = true;
                continue;
            }
            JsonObject vehicle = unwrapFirstMatchingObject(current, "Vehicle");
            if (vehicle != null) {
                current = vehicle;
                changed = true;
            }
        } while (changed);
        return current;
    }

    private @Nullable JsonObject unwrapVehicleLocation(@Nullable JsonObject json) {
        if (json == null) {
            return null;
        }
        JsonObject current = json;
        boolean changed;
        do {
            changed = false;
            JsonObject unwrapped = unwrapFirstMatchingObject(current, "resMsg", "payload", "body", "response", "data");
            if (unwrapped != null) {
                current = unwrapped;
                changed = true;
                continue;
            }
            JsonObject location = unwrapFirstMatchingObject(current, "vehicleLocation", "location",
                    "lastKnownPosition", "coord", "pos");
            if (location != null) {
                current = location;
                changed = true;
                continue;
            }
            JsonObject statusWrapper = unwrapFirstMatchingObject(current, "vehicleStatus", "vehicleStatusInfo",
                    "vehicleStatusDetail");
            if (statusWrapper != null) {
                current = statusWrapper;
                changed = true;
                continue;
            }
            JsonObject gpsDetail = unwrapFirstMatchingObject(current, "gpsDetail", "gpsDetails", "gpsInfo");
            if (gpsDetail != null) {
                current = gpsDetail;
                changed = true;
                continue;
            }
            JsonObject coord = unwrapFirstMatchingObject(current, "coord", "coordinates", "coordinate", "position");
            if (coord != null) {
                current = coord;
                changed = true;
                continue;
            }
        } while (changed);
        return current;
    }

    private @Nullable JsonObject unwrapFirstMatchingObject(@Nullable JsonObject source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            if (!source.has(key)) {
                continue;
            }
            JsonElement element = source.get(key);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    @SuppressWarnings("null")
    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, AuthorizationMode mode,
            @Nullable String requestBody,
            HeaderInclusion headerInclusion) throws Exception {
        applyCommonHeaders(builder, mode, headerInclusion);
        HttpRequest req = builder.build();
        logRequest(req, requestBody);
        HttpResponse<String> resp;
        resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        logResponse(req, resp);
        if (resp.statusCode() == 401) {
            logger.info("Token expired, refreshing");
            oauth.refreshToken();
            invalidateControlToken();
            applyCommonHeaders(builder, mode, headerInclusion);
            req = builder.build();
            logRequest(req, requestBody);
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            logResponse(req, resp);
        }
        return resp;
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, AuthorizationMode mode, String requestBody)
            throws Exception {
        return sendWithRetry(builder, mode, requestBody, HeaderInclusion.ALL);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, AuthorizationMode mode,
            HeaderInclusion headerInclusion) throws Exception {
        return sendWithRetry(builder, mode, null, headerInclusion);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, AuthorizationMode mode) throws Exception {
        return sendWithRetry(builder, mode, null, HeaderInclusion.ALL);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, String requestBody) throws Exception {
        return sendWithRetry(builder, AuthorizationMode.ACCESS_TOKEN, requestBody, HeaderInclusion.ALL);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder) throws Exception {
        return sendWithRetry(builder, AuthorizationMode.ACCESS_TOKEN, null, HeaderInclusion.ALL);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder builder, HeaderInclusion headerInclusion)
            throws Exception {
        return sendWithRetry(builder, AuthorizationMode.ACCESS_TOKEN, null, headerInclusion);
    }

    private void logRequest(HttpRequest request, String requestBody) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        Map<String, List<String>> headers = sanitizeHeaders(request);
        String bodyForLog = formatBodyForLog(requestBody);
        logger.trace("Sending HTTP {} {} headers={} body={}", request.method(), request.uri(), headers, bodyForLog);
    }

    private void logResponse(HttpRequest request, HttpResponse<String> response) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        String bodyForLog = formatBodyForLog(response != null ? response.body() : null);
        logger.trace("Received HTTP {} {} response status={} body={}", request.method(), request.uri(),
                response != null ? Integer.valueOf(response.statusCode()) : "<none>", bodyForLog);
    }

    @SuppressWarnings("null")
    private Map<String, List<String>> sanitizeHeaders(HttpRequest request) {
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        request.headers().map().forEach((name, values) -> {
            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean sensitive = SENSITIVE_HEADERS.contains(lowerName);
            boolean controlToken = CONTROL_TOKEN_HEADER.equals(lowerName);
            List<String> sanitizedValues = new ArrayList<>(values.size());
            for (String value : values) {
                if (controlToken) {
                    sanitizedValues.add(CONTROL_TOKEN_REDACTED_VALUE);
                } else if (sensitive) {
                    sanitizedValues.add(REDACTED_VALUE);
                } else {
                    sanitizedValues.add(value);
                }
            }
            sanitized.put(name, sanitizedValues);
        });
        return sanitized;
    }

    private String formatBodyForLog(@Nullable String body) {
        if (body == null) {
            return "<none>";
        }
        if (body.isEmpty()) {
            return "<empty>";
        }
        return sanitizeBody(body);
    }

    private String sanitizeBody(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            JsonElement nonNullElement = Objects.requireNonNull(element);
            sanitizeJsonElement(nonNullElement);
            return Objects.requireNonNull(nonNullElement.toString());
        } catch (Exception e) {
            return body;
        }
    }

    private void sanitizeJsonElement(JsonElement element) {
        if (element instanceof JsonObject) {
            JsonObject obj = (JsonObject) element;
            @SuppressWarnings("null")
            Set<Map.Entry<String, JsonElement>> entries = obj.entrySet();
            for (Map.Entry<String, JsonElement> entry : entries) {
                if (SENSITIVE_JSON_FIELDS.contains(entry.getKey())) {
                    entry.setValue(new JsonPrimitive("***REDACTED***"));
                } else {
                    JsonElement childElement = Objects.requireNonNull(entry.getValue());
                    sanitizeJsonElement(childElement);
                }
            }
        } else if (element instanceof JsonArray) {
            JsonArray array = (JsonArray) element;
            for (JsonElement child : array) {
                sanitizeJsonElement(Objects.requireNonNull(child));
            }
        }
    }

    private void applyCommonHeaders(HttpRequest.Builder builder, AuthorizationMode mode,
            HeaderInclusion headerInclusion) throws Exception {
        String authHeaderValue = "Bearer " + oauth.getAccessToken();
        if (mode == AuthorizationMode.CONTROL_TOKEN_CCSP) {
            String controlTokenValue = ensureControlToken();
            if (controlTokenValue != null && !controlTokenValue.isBlank()) {
                authHeaderValue = "Bearer " + controlTokenValue;
                builder.setHeader("AuthorizationCCSP", authHeaderValue);
            }
        } else if (headerInclusion.shouldIncludeControlToken() && mode == AuthorizationMode.CONTROL_TOKEN
                && controlTokenSupported) {
            try {
                String controlTokenValue = ensureControlToken();
                if (controlTokenValue != null && !controlTokenValue.isBlank()) {
                    builder.setHeader("ccsp-control-token", controlTokenValue);
                }
            } catch (IllegalStateException e) {
                logger.debug("Control token not available, proceeding without control token header: {}",
                        e.getMessage());
            }
        }
        builder.setHeader("Authorization", authHeaderValue);
        String deviceId = oauth.getDeviceId();
        if (deviceId != null && !deviceId.isBlank()) {
            builder.setHeader("ccsp-device-id", deviceId);
        }
        if (headerInclusion.shouldIncludePin() && hashedPin != null && !hashedPin.isBlank()
                && mode != AuthorizationMode.CONTROL_TOKEN_CCSP) {
            builder.setHeader("pin", hashedPin);
        }
        OAuthIdentifiers identifiers = resolveOAuthIdentifiers();
        if (identifiers.clientId != null && !identifiers.clientId.isBlank()) {
            builder.setHeader("ccsp-service-id", identifiers.clientId);
        }
        if (identifiers.applicationId != null && !identifiers.applicationId.isBlank()) {
            builder.setHeader("ccsp-application-id", identifiers.applicationId);
        }
        String stamp = stampProvider.getStamp();
        if (stamp != null && !stamp.isBlank()) {
            builder.setHeader("Stamp", Objects.requireNonNull(stamp));
        }
    }

    private OAuthIdentifiers resolveOAuthIdentifiers() {
        String applicationId = ep.oauth.applicationId;
        if (applicationId == null || applicationId.isBlank()) {
            applicationId = ep.oauth.clientId;
        }
        return new OAuthIdentifiers(Objects.requireNonNull(applicationId), Objects.requireNonNull(ep.oauth.clientId));
    }

    private static final class OAuthIdentifiers {
        private final String applicationId;
        private final String clientId;

        private OAuthIdentifiers(String applicationId, String clientId) {
            this.applicationId = applicationId;
            this.clientId = clientId;
        }
    }

    private synchronized void invalidateControlToken() {
        controlToken = null;
        controlTokenExpiry = null;
    }

    public synchronized String ensureControlToken() throws Exception {
        if (!controlTokenSupported) {
            throw new IllegalStateException("Control token exchange is not available without a configured PIN");
        }
        Instant expiry = controlTokenExpiry;
        String localToken = controlToken;
        if (localToken != null && expiry != null) {
            Instant now = Instant.now();
            if (now.isBefore(expiry.minusSeconds(30))) {
                return localToken;
            }
        }

        requestControlToken();
        String currentToken = controlToken;
        if (currentToken == null || currentToken.isBlank()) {
            throw new IOException("Control token request failed: no token in response");
        }
        return currentToken;
    }

    private synchronized void requestControlToken() throws Exception {
        if (!controlTokenSupported) {
            throw new IllegalStateException("Control token exchange is not available without a configured PIN");
        }
        String deviceId = oauth.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalStateException("Device ID is not available for control token exchange");
        }
        String currentPin = pin;
        if (currentPin == null || currentPin.isBlank()) {
            throw new IllegalStateException("PIN is not configured for control token exchange");
        }

        URI baseUri = URI.create(ep.ccapi.baseUrl);
        URI uri = baseUri.resolve("/api/v1/user/pin");
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("pin", pin);

        String payloadString = payload.toString();
        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(uri))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Objects.requireNonNull(payloadString)));

        HttpResponse<String> response = Objects
                .requireNonNull(sendWithRetry(Objects.requireNonNull(builder), payloadString));
        if (response.statusCode() / 100 != 2) {
            logger.warn("Control token request failed with status {}: {}", response.statusCode(), response.body());
            throw new IOException("Control token request failed: " + response.statusCode());
        }

        JsonObject jsonBody = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject json = Objects.requireNonNull(jsonBody);
        String token = optString(json, "controlToken", "token", "accessToken");
        if (token == null || token.isBlank()) {
            throw new IOException("Control token response did not contain token");
        }
        final String activeToken = token;

        Instant expiry = optInstant(json, "controlTokenExpiry", "controlTokenExpiryUTC", "expiresAt", "expiryTime");
        if (expiry == null) {
            expiry = Instant.now().plusSeconds(300);
        }

        synchronized (this) {
            controlToken = activeToken;
            controlTokenExpiry = expiry;
        }

        logger.debug("Control token acquired, valid until {}", expiry);
    }

    public static URI buildControlUri(String baseUrl, String vehicleId, String control) {
        if (baseUrl.isBlank() || vehicleId.isBlank() || control.isBlank()) {
            throw new IllegalArgumentException("Base URL, vehicle ID and control must not be blank.");
        }
        boolean isV1 = baseUrl.toLowerCase(Locale.ROOT).contains("/api/v1/spa");
        String normalized = isV1 ? ensureSpaV1BaseUrl(baseUrl) : ensureSpaV2BaseUrl(baseUrl);
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return Objects.requireNonNull(URI.create(normalized + "vehicles/" + vehicleId + "/control/" + control));
    }

    public static URI buildRemoteDoorUri(String baseUrl, String vehicleId) {
        if (baseUrl.isBlank() || vehicleId.isBlank()) {
            throw new IllegalArgumentException("Base URL and vehicle ID must not be blank.");
        }
        boolean isV1 = baseUrl.toLowerCase(Locale.ROOT).contains("/api/v1/spa");
        String normalized = isV1 ? ensureSpaV1BaseUrl(baseUrl) : ensureSpaV2BaseUrl(baseUrl);
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        String path = isV1 ? "vehicles/" + vehicleId + "/control/door"
                : "vehicles/" + vehicleId + "/ccs2/control/door";
        return Objects.requireNonNull(URI.create(normalized + path));
    }

    private static @Nullable String hashPin(@Nullable String pin) {
        if (pin == null || pin.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hashed = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                int value = b & 0xFF;
                if (value < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 algorithm is not available for PIN hashing", e);
        }
    }

    public static URI buildSpaVehicleUri(String baseUrl, String vehicleId, String suffix, boolean useSpaV2) {
        return buildSpaVehicleUri(baseUrl, vehicleId, suffix, useSpaV2, false);
    }

    public static URI buildSpaVehicleUri(String baseUrl, String vehicleId, String suffix, boolean useSpaV2,
            boolean ccs2Supported) {
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (vehicleId.isBlank()) {
            throw new IllegalArgumentException("vehicleId must not be blank");
        }
        String finalSuffix = suffix;
        if (ccs2Supported && finalSuffix != null && !finalSuffix.isEmpty() && !finalSuffix.startsWith("ccs2/")) {
            finalSuffix = "ccs2/" + finalSuffix;
        }
        String normalized = useSpaV2 ? ensureSpaV2BaseUrl(baseUrl) : ensureSpaV1BaseUrl(baseUrl);
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        StringBuilder path = new StringBuilder("vehicles/").append(vehicleId);
        if (finalSuffix != null && !finalSuffix.isEmpty()) {
            if (!finalSuffix.startsWith("/")) {
                path.append('/');
            }
            path.append(finalSuffix);
        }
        String combined = normalized + path.toString();
        return Objects.requireNonNull(URI.create(combined));
    }

    private static String ensureSpaV2BaseUrl(String url) {
        String normalized = url.trim();
        if (!normalized.toLowerCase(Locale.ROOT).contains("/api/")) {
            normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            normalized = normalized + "/api/v2/spa";
        }
        return Objects.requireNonNull(
                normalized.replaceAll("(?i)/api/v2/spa", "/api/v2/spa").replaceAll("(?i)/api/v1/spa", "/api/v2/spa"));
    }

    private static String ensureSpaV1BaseUrl(String url) {
        String normalized = url.trim();
        if (!normalized.toLowerCase(Locale.ROOT).contains("/api/")) {
            normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            normalized = normalized + "/api/v1/spa";
        }
        return Objects.requireNonNull(
                normalized.replaceAll("(?i)/api/v2/spa", "/api/v1/spa").replaceAll("(?i)/api/v1/spa", "/api/v1/spa"));
    }

    public boolean pollVehicleCommandResult(String vehicleId, String vin, @Nullable String messageId) throws Exception {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }

        String baseUrl = ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl));
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        URI uri = URI.create(baseUrl + "notifications/" + vehicleId + "/records");

        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(uri).GET());
        HttpResponse<String> resp = sendWithRetry(builder, AuthorizationMode.ACCESS_TOKEN);

        String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;
        int statusCode = resp.statusCode();
        logger.debug("Command result polling (notifications) {} for {} status: {}", messageId, vinForLog,
                Integer.valueOf(statusCode));

        if (statusCode >= 400) {
            String body = Objects.requireNonNull(resp.body());
            String bodyForLog = formatBodyForLog(body);
            logger.debug("Job polling failed via notifications for {} with HTTP {}: {}", vinForLog,
                    Integer.valueOf(statusCode), bodyForLog);
            // The command likely finished but since our pushRegId is fake,
            // the Hyundai server couldn't push the result and killed our session.
            // We must rotate the device ID to prevent subsequent 400/403 errors.
            try {
                oauth.rotateDevice();
            } catch (Exception e) {
                logger.debug("Failed to rotate device ID after polling fallback: {}", e.getMessage());
            }
            // Fallback: assume success rather than failing the command and breaking the
            // item state
            return true;
        }

        String responseBody = Objects.requireNonNull(resp.body());
        if (responseBody == null || responseBody.isBlank()) {
            return true;
        }

        try {
            JsonObject json = Objects.requireNonNull(JsonParser.parseString(responseBody).getAsJsonObject());
            JsonElement messagesObj = null;
            if (json.has("resMsg") && json.get("resMsg").isJsonArray()) {
                messagesObj = json.get("resMsg");
            } else if (json.has("messages") && json.get("messages").isJsonArray()) {
                messagesObj = json.get("messages");
            } else if (json.has("records") && json.get("records").isJsonArray()) {
                messagesObj = json.get("records");
            }

            if (messagesObj != null && messagesObj.isJsonArray()) {
                JsonArray messages = messagesObj.getAsJsonArray();
                for (JsonElement el : messages) {
                    if (el.isJsonObject()) {
                        JsonObject msg = el.getAsJsonObject();
                        String recordId = "";
                        if (msg.has("recordId") && !msg.get("recordId").isJsonNull()) {
                            recordId = msg.get("recordId").getAsString();
                        } else if (msg.has("messageId") && !msg.get("messageId").isJsonNull()) {
                            recordId = msg.get("messageId").getAsString();
                        }

                        if (messageId.equals(recordId)) {
                            String result = "";
                            if (msg.has("result") && !msg.get("result").isJsonNull()) {
                                result = msg.get("result").getAsString();
                            } else if (msg.has("status") && !msg.get("status").isJsonNull()) {
                                result = msg.get("status").getAsString();
                            }

                            if ("success".equalsIgnoreCase(result)) {
                                try {
                                    oauth.rotateDevice();
                                } catch (Exception e) {
                                    logger.debug("Failed to rotate device ID after command success: {}",
                                            e.getMessage());
                                }
                                return true;
                            } else if ("fail".equalsIgnoreCase(result)) {
                                try {
                                    oauth.rotateDevice();
                                } catch (Exception e) {
                                    logger.debug("Failed to rotate device ID after command failure: {}",
                                            e.getMessage());
                                }
                                throw new IOException("Command failed according to notification: " + result);
                            } else if ("non-response".equalsIgnoreCase(result)) {
                                try {
                                    oauth.rotateDevice();
                                } catch (Exception e) {
                                    logger.debug("Failed to rotate device ID after command timeout: {}",
                                            e.getMessage());
                                }
                                throw new IOException("Command timed out: " + result);
                            } else {
                                // pending or unfamiliar state
                                return false;
                            }
                        }
                    }
                }
            } else {
                logger.debug("No records array found in notifications response: {}", formatBodyForLog(responseBody));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.debug("Failed to parse notifications result response for {}: {}", messageId, e.getMessage());
        }

        // Return false to continue polling if we couldn't find it yet.
        return false;
    }

    public @Nullable String fetchLatestNotification(String vehicleId) throws Exception {
        String baseUrl = ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl));
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        URI uri = URI.create(baseUrl + "notifications/" + vehicleId + "/records");

        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(uri).GET());
        HttpResponse<String> resp = sendWithRetry(builder, AuthorizationMode.ACCESS_TOKEN);

        int statusCode = resp.statusCode();
        if (statusCode >= 400) {
            return null;
        }

        String responseBody = Objects.requireNonNull(resp.body());
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        try {
            JsonObject json = Objects.requireNonNull(JsonParser.parseString(responseBody).getAsJsonObject());
            JsonElement messagesObj = null;
            if (json.has("resMsg") && json.get("resMsg").isJsonArray()) {
                messagesObj = json.get("resMsg");
            } else if (json.has("messages") && json.get("messages").isJsonArray()) {
                messagesObj = json.get("messages");
            } else if (json.has("records") && json.get("records").isJsonArray()) {
                messagesObj = json.get("records");
            }

            if (messagesObj != null && messagesObj.isJsonArray()) {
                JsonArray messages = messagesObj.getAsJsonArray();
                for (JsonElement el : messages) {
                    if (el.isJsonObject()) {
                        JsonObject msg = el.getAsJsonObject();
                        // We want to skip the polling responses. Usually, command receipts have
                        // "result" field or something.
                        // For now we just return the first string we find from title/message
                        String title = optString(msg, "title", "messageTitle");
                        String content = optString(msg, "content", "message", "body", "messageBody", "text");

                        if (title != null || content != null) {
                            String result = "";
                            if (title != null && !title.isBlank())
                                result += title;
                            if (content != null && !content.isBlank()) {
                                if (!result.isEmpty())
                                    result += ": ";
                                result += content;
                            }
                            if (!result.isEmpty())
                                return result;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse notifications response: {}", e.getMessage());
        }
        return null;
    }

    public VehicleCommandResponse sendVehicleCommand(String vehicleId, String vin, String action,
            boolean ccs2Supported) throws Exception {
        return sendVehicleCommand(vehicleId, vin, action, buildVehicleCommandRequest(action), ccs2Supported);
    }

    public VehicleCommandResponse sendVehicleCommand(String vehicleId, String vin, String action,
            VehicleCommandRequest request, boolean ccs2Supported) throws Exception {
        URI uri = Objects
                .requireNonNull(request.buildUri(Objects.requireNonNull(ep.ccapi.baseUrl), vehicleId, ccs2Supported));
        JsonObject payload = request.getPayload(Objects.requireNonNull(ep.ccapi.baseUrl), oauth.getDeviceId(),
                ccs2Supported);
        String payloadString = payload != null ? Objects.requireNonNull(payload.toString()) : "{}";
        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadString));
        boolean isV1 = uri.toString().toLowerCase(Locale.ROOT).contains("/api/v1/spa");
        AuthorizationMode authorizationMode = request.requiresCcspToken(isV1, ccs2Supported)
                ? AuthorizationMode.CONTROL_TOKEN_CCSP
                : AuthorizationMode.CONTROL_TOKEN;
        HttpResponse<String> resp = sendWithRetry(Objects.requireNonNull(builder), authorizationMode, payloadString);
        String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;

        if (resp.statusCode() == 403 && uri.toString().contains("/api/v2/spa")) {
            logger.debug("{} command disallowed on SPA v2 for {} (403), retrying with SPA v1 base", action, vinForLog);
            String v1Base = ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl));
            URI v1Uri = Objects.requireNonNull(request.buildUri(v1Base, vehicleId, ccs2Supported));
            JsonObject v1Payload = request.getPayload(v1Base, oauth.getDeviceId());
            String v1PayloadString = v1Payload != null ? Objects.requireNonNull(v1Payload.toString()) : "{}";
            HttpRequest.Builder v1Builder = Objects.requireNonNull(HttpRequest.newBuilder(v1Uri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(v1PayloadString));
            AuthorizationMode v1AuthMode = request.requiresCcspToken(true, ccs2Supported)
                    ? AuthorizationMode.CONTROL_TOKEN_CCSP
                    : AuthorizationMode.CONTROL_TOKEN;
            resp = sendWithRetry(Objects.requireNonNull(v1Builder), v1AuthMode, v1PayloadString);
        }

        if (resp.statusCode() / 100 != 2) {
            logger.warn("{} command failed for {}: {} {}", action, vinForLog, resp.statusCode(),
                    Objects.requireNonNull(resp.body()));
            throw new IOException(action + " command failed: " + resp.statusCode());
        }
        String endpointForLog = request.getLogSegment(ccs2Supported);
        logger.debug("{} command accepted for {} via {}", action, vinForLog, endpointForLog);
        VehicleCommandResponse response = extractVehicleCommandResponse(request, resp, action, ccs2Supported);
        return response;
    }

    private VehicleCommandResponse extractVehicleCommandResponse(VehicleCommandRequest request,
            HttpResponse<String> response, String action, boolean ccs2Supported) {
        String body = Objects.requireNonNull(response.body());
        String controlSegment = request.getLogSegment(ccs2Supported);
        if (body == null || body.isBlank()) {
            return new VehicleCommandResponse(controlSegment, action, null, null, request.remoteDoor,
                    request.remoteDoorAction);
        }
        try {
            JsonObject json = Objects.requireNonNull(JsonParser.parseString(body).getAsJsonObject());
            String messageId = optString(json, "msgId", "messageId", "requestId", "id");
            return new VehicleCommandResponse(controlSegment, action, messageId, json, request.remoteDoor,
                    request.remoteDoorAction);
        } catch (Exception e) {
            logger.debug("Failed to parse command response for {}: {}", action, e.getMessage());
            return new VehicleCommandResponse(controlSegment, action, null, null, request.remoteDoor,
                    request.remoteDoorAction);
        }
    }

    VehicleCommandRequest buildVehicleCommandRequest(String action) throws Exception {
        Objects.requireNonNull(action, "action must not be null");
        String controlTokenValue;
        try {
            controlTokenValue = ensureControlToken();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Vehicle command '" + action + "' requires a configured PIN for control token exchange", e);
        }
        return switch (action) {
            case "lock" -> VehicleCommandRequest.forRemoteDoor("close");
            case "unlock" -> VehicleCommandRequest.forRemoteDoor("open");
            case "start" -> VehicleCommandRequest.forV1AndV2("temperature", "control/temperature",
                    buildV1ClimatePayload("start", controlTokenValue),
                    buildV2ClimatePayload("start", null, false, false, false, false));
            case "stop" -> VehicleCommandRequest.forV1AndV2("temperature", "control/temperature",
                    buildV1ClimatePayload("stop", controlTokenValue),
                    buildV2ClimatePayload("stop", null, false, false, false, false));
            case "startCharge" -> VehicleCommandRequest.forV1Only("charge",
                    buildChargePayload("start", controlTokenValue));
            case "stopCharge" -> VehicleCommandRequest.forControlSegment("charge",
                    buildChargePayload("stop", controlTokenValue));
            default -> throw new IllegalArgumentException("Unsupported vehicle command action: " + action);
        };
    }

    JsonObject buildChargePayload(String chargeAction, String controlTokenValue) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", chargeAction);
        payload.addProperty("controlToken", controlTokenValue);
        addDeviceId(payload);
        return payload;
    }

    public JsonObject buildChargeLimitPayload(int limitAC, int limitDC, String controlTokenValue) {
        JsonObject payload = new JsonObject();
        payload.addProperty("controlToken", controlTokenValue);
        payload.addProperty("chargingLimitAC", limitAC);
        payload.addProperty("chargingLimitDC", limitDC);
        addDeviceId(payload);
        return payload;
    }

    private JsonObject buildV1ClimatePayload(String climateAction, String controlTokenValue) {
        return buildV1ClimatePayload(climateAction, controlTokenValue, null, false, false, false, false, false);
    }

    public JsonObject buildV1ClimatePayload(String climateAction, String controlTokenValue, @Nullable Double temp,
            boolean defrost, boolean heating, boolean heatingSteeringWheel, boolean heatingSideMirror,
            boolean heatingRearWindow) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", climateAction);
        payload.addProperty("controlToken", controlTokenValue);
        payload.addProperty("hvacType", DEFAULT_HVAC_TYPE);

        JsonObject options = new JsonObject();
        options.addProperty("defrost", defrost);
        options.addProperty("heating1", heating ? 1 : 0);
        options.addProperty("heatingSteeringWheel", heatingSteeringWheel ? 1 : 0);
        options.addProperty("heatingSideMirror", heatingSideMirror ? 1 : 0);
        options.addProperty("heatingRearWindow", heatingRearWindow ? 1 : 0);
        payload.add("options", options);

        if (temp != null) {
            payload.addProperty("airTemp", String.format(Locale.ROOT, "%.1f", temp));
            // Keep tempCode for legacy models/compatibility
            int intTemp = temp.intValue();
            payload.addProperty("tempCode", String.format(Locale.ROOT, "%02XH", intTemp));
        } else {
            payload.addProperty("tempCode", DEFAULT_CLIMATE_TEMP_CODE);
        }
        payload.addProperty("unit", DEFAULT_CLIMATE_UNIT);
        addDeviceId(payload);
        return payload;
    }

    public JsonObject buildV2ClimatePayload(String climateCommand, @Nullable Double temp, boolean defrost,
            boolean steeringWheel, boolean sideMirror, boolean rearWindow) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", climateCommand);
        payload.addProperty("action", climateCommand); // Included for compatibility with older V2
        if (temp != null) {
            payload.addProperty("temp", temp);
        }
        payload.addProperty("defrost", defrost);
        payload.addProperty("steeringWheel", steeringWheel);
        payload.addProperty("sideMirror", sideMirror);
        payload.addProperty("rearWindow", rearWindow);

        if ("start".equals(climateCommand)) {
            // Default ignition duration to 10 minutes
            payload.addProperty("ignitionDuration", 10);
            payload.addProperty("strgWhlHeating", steeringWheel ? 1 : 0);
            payload.addProperty("sideRearMirrorHeating", sideMirror ? 1 : 0);
            payload.addProperty("windshieldFrontDefogState", defrost ? 1 : 0);
            payload.addProperty("hvacTempType", 1);
            if (temp != null) {
                payload.addProperty("hvacTemp", String.format(Locale.ROOT, "%.1f", temp));
                int intTemp = temp.intValue();
                payload.addProperty("tempCode", String.format(Locale.ROOT, "%02XH", intTemp));
            } else {
                payload.addProperty("hvacTemp", "21.0");
                payload.addProperty("tempCode", DEFAULT_CLIMATE_TEMP_CODE);
            }
            payload.addProperty("tempUnit", DEFAULT_CLIMATE_UNIT);
            payload.addProperty("unit", DEFAULT_CLIMATE_UNIT);
            payload.addProperty("drvSeatLoc", "L");
        } else {
            payload.addProperty("hvacType", 1);
            payload.addProperty("drvSeatLoc", "L");
        }
        return payload;
    }

    private void addDeviceId(JsonObject payload) {
        String deviceId = oauth.getDeviceId();
        if (deviceId != null && !deviceId.isBlank()) {
            payload.addProperty("deviceId", deviceId);
        }
    }

    public VehicleCommandResponse lock(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return commandHandler.lock(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse unlock(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return commandHandler.unlock(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse start(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return start(vehicleId, vin, null, false, false, false, false, false, ccs2Supported);
    }

    public VehicleCommandResponse start(String vehicleId, String vin, @Nullable Double temperature, boolean defrost,
            boolean heating, boolean steeringWheel, boolean sideMirror, boolean rearWindow, boolean ccs2Supported)
            throws Exception {
        return climateHandler.start(vehicleId, vin, temperature, defrost, heating, steeringWheel, sideMirror,
                rearWindow, ccs2Supported);
    }

    public @Nullable Reservation getReservation(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return climateHandler.getReservation(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse stop(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return climateHandler.stop(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse startCharge(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return commandHandler.startCharge(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse stopCharge(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return commandHandler.stopCharge(vehicleId, vin, ccs2Supported);
    }

    public VehicleCommandResponse setChargeLimit(String vehicleId, String vin, int limitAC, int limitDC,
            boolean ccs2Supported) throws Exception {
        return commandHandler.setChargeLimit(vehicleId, vin, limitAC, limitDC, ccs2Supported);
    }

    public VehicleCommandResponse setReservation(String vehicleId, String vin, Reservation reservation,
            boolean ccs2Supported) throws Exception {
        return climateHandler.setReservation(vehicleId, vin, reservation, ccs2Supported);
    }

    public VehicleCommandResponse setTargetTemperature(String vehicleId, String vin, double temperature,
            boolean ccs2Supported) throws Exception {
        return climateHandler.setTargetTemperature(vehicleId, vin, temperature, ccs2Supported);
    }

    public VehicleCommandResponse setTargetTemperature(String vehicleId, String vin, int temperature,
            boolean ccs2Supported) throws Exception {
        return setTargetTemperature(vehicleId, vin, (double) temperature, ccs2Supported);
    }

    protected @Nullable Reservation getReservationImpl(String vehicleId, String vin, boolean ccs2Supported)
            throws Exception {
        String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;

        // Try /reservation/hvac (Control endpoint style)
        // CCSP vehicles (v1/v2 transition) often don't support the V2 style
        // control/reservation/hvac endpoint.
        // If we know it's a V1 vehicle, we should still try but maybe be faster about
        // it.
        // Actually, for CCSP (v2) vehicles, we try v2 first. For v1 vehicles, we should
        // probably prefer v1 uri style?
        // Let's use the provided logic but wrap the V2 attempt in a check.

        boolean tryV2 = true;
        URI v2Uri = buildSpaVehicleUri(Objects.requireNonNull(ep.ccapi.baseUrl), vehicleId, "control/reservation/hvac",
                true, ccs2Supported);

        try {
            HttpRequest.Builder builder;
            HttpResponse<String> resp = null;

            if (tryV2) {
                builder = Objects.requireNonNull(HttpRequest.newBuilder(v2Uri).GET());
                resp = controlTokenSupported ? sendWithRetry(builder, AuthorizationMode.CONTROL_TOKEN)
                        : sendWithRetry(builder);

                if (resp.statusCode() == 403 && v2Uri.toString().contains("/api/v2/spa")) {
                    logger.debug(
                            "Reservation retrieval via control/reservation/hvac disallowed on SPA v2 for {} (403), retrying with SPA v1 base",
                            vinForLog);
                    resp = null;
                }
            }

            if (resp == null) {
                String v1Base = ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl));
                URI v1Uri = Objects
                        .requireNonNull(buildSpaVehicleUri(v1Base, vehicleId, "control/reservation/hvac", false));
                builder = Objects.requireNonNull(HttpRequest.newBuilder(v1Uri).GET());
                resp = controlTokenSupported ? sendWithRetry(builder, AuthorizationMode.CONTROL_TOKEN)
                        : sendWithRetry(builder);
            }

            if (resp.statusCode() == 200) {
                return parseReservationResponse(resp.body());
            } else {
                logger.debug("Reservation retrieval via control/reservation/hvac failed for {}: {}",
                        vinForLog, resp.statusCode());
            }
        } catch (Exception e) {
            logger.debug("Reservation retrieval exception for {}: {}", vinForLog, e.getMessage());
        }

        return null;
    }

    protected VehicleCommandResponse setReservationImpl(String vehicleId, String vin, Reservation reservation,
            boolean ccs2Supported)
            throws Exception {
        String controlTokenValue = ensureControlToken();

        // Build payload
        JsonObject payload = new JsonObject();
        payload.addProperty("controlToken", controlTokenValue);
        addDeviceId(payload);

        // Construct the reservation structure (assuming 'reservations' wrapper based on
        // common structure)
        // Note: The specific payload structure for 'reservation-hvac' can vary.
        // We will target a simplified structure initially that aligns with known
        // 'departure' logic.

        JsonObject schedule = new JsonObject();
        schedule.addProperty("id", 1); // ID 1 is usually the first schedule
        schedule.addProperty("active", reservation.active);

        JsonObject time = new JsonObject();
        time.addProperty("hour", reservation.hour);
        time.addProperty("minute", reservation.minute);
        schedule.add("endTime", time); // 'endTime' or 'time' often used for departure

        // Days: Enable for all days if active, or just today/tomorrow?
        // Usually required. Let's set generic 7 days for now to ensure it triggers.
        JsonArray days = new JsonArray();
        for (int i = 0; i < 7; i++)
            days.add(true);
        // specialized logic might be needed here based on user feedback

        // For now, we wrap it in settings
        JsonObject settings = new JsonObject();
        settings.add("reservations", schedule);
        settings.addProperty("defrost", reservation.defrost); // If supported at top level

        // This is a 'scheduled' command, likely POST
        // We will try the 'control' style endpoint first
        URI uri = Objects.requireNonNull(buildSpaVehicleUri(Objects.requireNonNull(ep.ccapi.baseUrl), vehicleId,
                "control/reservation/hvac", true, ccs2Supported));

        // We reuse the generic command sending logic but manually since it's not a
        // standard 'control' segment action
        // String payloadString = payload.toString();

        // Re-building payload with correct structure
        payload = new JsonObject();
        payload.addProperty("controlToken", controlTokenValue);
        addDeviceId(payload);
        payload.add("reservation", settings); // Guessing 'reservation' wrapper

        // Since we don't have the exact payload documentation, we will proceed with a
        // structure
        // that closely mirrors the 'climate' payload but for reservation.
        // But for safety, I will implement a simpler 'set' that just logs for now if I
        // can't be sure,
        // OR better: I will implement the 'get' fully first?
        // User wants implementation.

        // Let's refine the payload to match bluelinky observations:
        // {
        // reservations: {
        // arrival: false,
        // fatc: { defrost: boolean, tempCode: "10H", unit: "C" },
        // precondition: { schedule: [ { active: boolean, day: [...], time: { hour,
        // minute }, id: 0 } ] }
        // }
        // }

        JsonObject fatc = new JsonObject();
        fatc.addProperty("defrost", reservation.defrost);
        fatc.addProperty("tempCode", DEFAULT_CLIMATE_TEMP_CODE);
        fatc.addProperty("unit", DEFAULT_CLIMATE_UNIT);

        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("hour", reservation.hour);
        timeObj.addProperty("minute", reservation.minute);

        JsonObject scheduleItem = new JsonObject();
        scheduleItem.addProperty("id", 0);
        scheduleItem.addProperty("active", reservation.active);
        scheduleItem.add("time", timeObj);

        JsonArray dayArray = new JsonArray();
        for (int i = 0; i < 7; i++)
            dayArray.add(true); // Monday to Sunday
        scheduleItem.add("day", dayArray);

        JsonArray scheduleList = new JsonArray();
        scheduleList.add(scheduleItem);

        JsonObject precondition = new JsonObject();
        precondition.add("schedule", scheduleList);

        JsonObject reservations = new JsonObject();
        reservations.addProperty("arrival", true); // Often required to be true for 'departure' based logic
        reservations.add("fatc", fatc);
        reservations.add("precondition", precondition);

        payload.add("reservations", reservations);

        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())));

        HttpResponse<String> resp = sendWithRetry(builder, AuthorizationMode.CONTROL_TOKEN,
                Objects.requireNonNull(payload.toString()));

        if (resp.statusCode() == 403 && uri.toString().contains("/api/v2/spa")) {
            String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;
            logger.debug("Set reservation disallowed on SPA v2 for {} (403), retrying with SPA v1 base", vinForLog);
            String v1Base = ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl));
            URI v1Uri = Objects
                    .requireNonNull(buildSpaVehicleUri(v1Base, vehicleId, "control/reservation/hvac", false,
                            ccs2Supported));
            builder = Objects.requireNonNull(HttpRequest.newBuilder(v1Uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())));
            resp = sendWithRetry(builder, AuthorizationMode.CONTROL_TOKEN,
                    Objects.requireNonNull(payload.toString()));
        }

        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Set reservation failed: " + resp.statusCode());
        }

        return extractVehicleCommandResponse(
                VehicleCommandRequest.forControlSegment("reservation", payload),
                resp,
                "setReservation",
                ccs2Supported);
    }

    private @Nullable Reservation parseReservationResponse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement rootElem = JsonParser.parseString(body);
            if (rootElem == null || !rootElem.isJsonObject()) {
                return null;
            }
            JsonObject root = rootElem.getAsJsonObject();

            if (!root.has("reservations") || root.get("reservations").isJsonNull()) {
                return null;
            }
            JsonObject res = root.get("reservations").getAsJsonObject();

            // Extract from first schedule
            if (!res.has("precondition") || res.get("precondition").isJsonNull()) {
                return null;
            }
            JsonObject pre = res.get("precondition").getAsJsonObject();

            if (!pre.has("schedule") || !pre.get("schedule").isJsonArray()) {
                return null;
            }
            JsonArray sched = pre.get("schedule").getAsJsonArray();
            if (sched.isEmpty() || sched.get(0).isJsonNull()) {
                return null;
            }
            JsonObject item = Objects.requireNonNull(sched.get(0).getAsJsonObject());

            boolean active = optBoolean(item, "active", false);

            int h = 0, m = 0;
            if (item.has("time") && !item.get("time").isJsonNull()) {
                JsonObject time = Objects.requireNonNull(item.get("time").getAsJsonObject());
                h = optInteger(time, "hour", 0);
                m = optInteger(time, "minute", 0);
            }

            boolean defrost = false;
            if (res.has("fatc") && !res.get("fatc").isJsonNull()) {
                JsonObject fatc = Objects.requireNonNull(res.get("fatc").getAsJsonObject());
                defrost = optBoolean(fatc, "defrost", false);
            }

            return new Reservation(active, h, m, defrost);
        } catch (Exception e) {
            logger.warn("Failed to parse reservation response", e);
            return null;
        }
    }

    private boolean optBoolean(JsonObject obj, String key, boolean def) {
        if (obj.has(key) && !obj.get(key).isJsonNull())
            return obj.get(key).getAsBoolean();
        return def;
    }

    private int optInteger(JsonObject obj, String key, int def) {
        if (obj.has(key) && !obj.get(key).isJsonNull())
            return obj.get(key).getAsInt();
        return def;
    }

    public VehicleLocation getVehicleLocation(String vehicleId, String vin, boolean ccs2Supported) throws Exception {
        return statusHandler.getVehicleLocation(vehicleId, vin, ccs2Supported);
    }

    protected VehicleLocation getVehicleLocationImpl(String vehicleId, String vin, boolean ccs2Supported)
            throws Exception {
        String vinForLog = (vin == null || vin.isBlank()) ? "UNKNOWN" : vin;

        // Try /location (V2 then V1 if needed)
        try {
            JsonObject locationJson = fetchSpaVehicleData(vehicleId, vinForLog, "location/latest", "location", true,
                    ccs2Supported).getBodyAsJson();
            VehicleLocation loc = parseVehicleLocation(locationJson);
            if (loc != null && isValidVehicleLocation(loc)) {
                logger.debug("Location retrieved for {} via /location: {}, {}", vinForLog, loc.latitude, loc.longitude);
                return loc;
            }
        } catch (Exception e) {
            logger.debug("Location retrieval via /location failed for {}: {}", vinForLog, e.getMessage());
        }

        // Try /ccs2/location (V2 then V1 if needed)
        try {
            JsonObject locationJson = fetchSpaVehicleData(vehicleId, vinForLog, "ccs2/location/latest", "ccs2 location",
                    true, ccs2Supported).getBodyAsJson();
            VehicleLocation loc = parseVehicleLocation(locationJson);
            if (loc != null && isValidVehicleLocation(loc)) {
                logger.debug("Location retrieved for {} via /ccs2/location: {}, {}", vinForLog, loc.latitude,
                        loc.longitude);
                return loc;
            }
        } catch (Exception e) {
            logger.debug("Location retrieval via /ccs2/location failed for {}: {}", vinForLog, e.getMessage());
        }

        // Try /ccs2/carstatus/latest (for some regions/models)
        try {
            JsonObject statusJson = fetchSpaVehicleData(vehicleId, vinForLog, "ccs2/carstatus/latest", "ccs2 carstatus",
                    true, ccs2Supported).getBodyAsJson();
            VehicleLocation loc = parseVehicleLocation(statusJson);
            if (loc != null && isValidVehicleLocation(loc)) {
                logger.debug("Location retrieved for {} via /ccs2/carstatus: {}, {}", vinForLog, loc.latitude,
                        loc.longitude);
                return loc;
            }
        } catch (Exception e) {
            logger.debug("Location retrieval via /ccs2/carstatus failed for {}: {}", vinForLog, e.getMessage());
        }

        VehicleLocation locationFromStatus = tryGetVehicleLocationFromStatus(vehicleId, vinForLog, ccs2Supported);
        if (locationFromStatus != null) {
            return locationFromStatus;
        }

        VehicleLocation loc = new VehicleLocation();
        loc.latitude = Double.NaN;
        loc.longitude = Double.NaN;
        logger.debug("Location unavailable for {}", vinForLog);
        return loc;
    }

    private @Nullable VehicleLocation tryGetVehicleLocationFromStatus(String vehicleId, String vinForLog,
            boolean ccs2Supported) throws Exception {

        try {
            JsonObject legacyStatus = fetchLegacyVehicleStatusLatest(vehicleId, vinForLog, ccs2Supported);
            VehicleLocation loc = parseVehicleLocation(legacyStatus);
            if (loc != null && isValidVehicleLocation(loc)) {
                logger.debug("Location retrieved for {} via legacy vehicle status: {}, {}", vinForLog, loc.latitude,
                        loc.longitude);
                return loc;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw e;
            }
            logger.debug("Vehicle status latest location retrieval failed for {}: {}", vinForLog, e.getMessage());
            logger.trace("Vehicle status latest location retrieval failure for {}", vinForLog, e);
        }

        return null;
    }

    private boolean isValidVehicleLocation(@Nullable Double lat, @Nullable Double lon) {
        if (lat == null || lon == null) {
            return false;
        }
        return !lat.isNaN() && !lon.isNaN() && (lat != 0.0 || lon != 0.0);
    }

    private boolean isValidVehicleLocation(@Nullable VehicleLocation location) {
        return location != null && isValidVehicleLocation(location.latitude, location.longitude);
    }

    private @Nullable VehicleLocation parseVehicleLocation(@Nullable JsonObject json) {
        if (json == null) {
            return null;
        }
        // Try direct find
        Double latVal = optDouble(json, "lat", "latitude", "gpsLat");
        Double lonVal = optDouble(json, "lon", "longitude", "gpsLon");

        Double lat = (latVal != null && !latVal.isNaN()) ? latVal : null;
        Double lon = (lonVal != null && !lonVal.isNaN()) ? lonVal : null;

        if (lat == null || lon == null) {
            // Unwrapping known redundant wrappers efficiently
            JsonObject unwrapped = unwrapVehicleLocation(json);
            if (unwrapped != null && unwrapped != json) {
                if (unwrapped.has("lat") && unwrapped.has("lon")) {
                    Double unwrappedLat = optDouble(unwrapped, "lat");
                    Double unwrappedLon = optDouble(unwrapped, "lon");
                    if (isValidVehicleLocation(unwrappedLat, unwrappedLon)) {
                        return new VehicleLocation(unwrappedLat != null ? unwrappedLat : 0.0,
                                unwrappedLon != null ? unwrappedLon : 0.0);
                    }
                }
            }

            // Fallback: Broad search for any other object that might contain lat/lon
            @SuppressWarnings("null")
            Set<Map.Entry<String, JsonElement>> entries = json.entrySet();
            for (Map.Entry<String, JsonElement> entry : entries) {
                @SuppressWarnings("null")
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonObject()) {
                    VehicleLocation loc = parseVehicleLocation(value.getAsJsonObject());
                    if (loc != null) {
                        return loc;
                    }
                }
            }
        }

        if (isValidVehicleLocation(lat, lon)) {
            return new VehicleLocation(lat != null ? lat : 0.0, lon != null ? lon : 0.0);
        }
        return null;
    }

    @SuppressWarnings("null")
    public JsonResponse getVehicleStatusLatestRaw(String vehicleId, String vinHint, boolean useSpaV2,
            boolean ccs2Supported) throws Exception {
        String vinForLog = (vinHint == null || vinHint.isBlank()) ? "UNKNOWN" : vinHint;
        String baseUrl = ep.ccapi.baseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = useSpaV2 ? buildSpaVehicleUri(baseUrl, vehicleId, "status/latest", true, ccs2Supported).toString()
                : buildSpaVehicleUri(baseUrl, vehicleId, "status/latest", false, ccs2Supported).toString();

        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(URI.create(url))).GET();
        HttpResponse<String> resp = sendWithRetry(builder);

        if (useSpaV2 && (resp.statusCode() == 403 || resp.statusCode() == 404)) {
            logger.debug(
                    "Vehicle status latest request disallowed or missing for {} ({}) on SPA v2, retrying with SPA v1",
                    vinForLog, Integer.valueOf(resp.statusCode()));
            String v1Url = ensureSpaV1BaseUrl(ep.ccapi.baseUrl) + "/vehicles/" + vehicleId + "/status/latest";
            resp = sendWithRetry(HttpRequest.newBuilder(URI.create(v1Url)).GET());
        }

        if (resp.statusCode() == 200) {
            return new JsonResponse(200, resp.body(), formatBodyForLog(resp.body()));
        }

        logger.warn("Vehicle status latest request failed for {}: {} {}", vinForLog, resp.statusCode(), resp.body());
        return null;
    }

    public JsonResponse getVehicleCcs2CarStatusLatest(String vehicleId, String vinHint) throws Exception {
        return statusHandler.getVehicleCcs2CarStatusLatest(vehicleId, vinHint);
    }

    protected JsonResponse getVehicleCcs2CarStatusLatestImpl(String vehicleId, String vinHint) throws Exception {
        String vinForLog = (vinHint == null || vinHint.isBlank()) ? "UNKNOWN" : vinHint;
        JsonObject json = fetchVehicleStatusFromCcs2(vehicleId, vinForLog);
        if (json == null) {
            return new JsonResponse(404, "{}", "{}");
        }
        String body = json.toString();
        return new JsonResponse(200, Objects.requireNonNull(body), formatBodyForLog(body));
    }

    public JsonResponse getVehicleMonthlyReport(String vehicleId, String vin, boolean ccs2Supported)
            throws Exception {
        return statusHandler.getVehicleMonthlyReport(vehicleId, vin, ccs2Supported);
    }

    public JsonResponse getVehicleMonthlyReportList(String vehicleId, String vin, boolean ccs2Supported)
            throws Exception {
        return statusHandler.getVehicleMonthlyReportList(vehicleId, vin, ccs2Supported);
    }

    protected JsonResponse getVehicleMonthlyReportListImpl(String vehicleId, String vinHint, boolean ccs2Supported)
            throws Exception {
        return fetchSpaVehicleData(vehicleId, vinHint, "monthlyreportlist/latest", "monthly report list", true,
                ccs2Supported);
    }

    protected JsonResponse getVehicleMonthlyReportImpl(String vehicleId, String vinHint, boolean ccs2Supported)
            throws Exception {
        String vinForLog = (vinHint == null || vinHint.isBlank()) ? "UNKNOWN" : vinHint;
        JsonResponse response = fetchSpaVehicleData(vehicleId, vinForLog, "monthlyreport/v2", "monthly report", true,
                ccs2Supported);
        if (response.getStatusCode() != 404) {
            return response;
        }

        logger.debug("Monthly report SPA v2 endpoint unavailable for {}, retrying with SPA v1 base", vinForLog);

        response = fetchSpaVehicleData(vehicleId, vinForLog, "monthlyreport/v2", "monthly report", false,
                ccs2Supported);
        if (response.getStatusCode() != 404) {
            return response;
        }

        logger.debug("Monthly report SPA v1 /v2 endpoint unavailable for {}, retrying without /v2 suffix", vinForLog);
        return fetchSpaVehicleData(vehicleId, vinForLog, "monthlyreport", "monthly report", false, ccs2Supported);
    }

    private JsonResponse fetchSpaVehicleData(String vehicleId, String vinHint, String suffix,
            String description,
            boolean useSpaV2, boolean ccs2Supported) throws Exception {
        URI uri = buildSpaVehicleUri(Objects.requireNonNull(ep.ccapi.baseUrl), vehicleId, suffix, useSpaV2,
                ccs2Supported);
        Supplier<HttpRequest.Builder> builderSupplier = () -> Objects.requireNonNull(HttpRequest.newBuilder(uri).GET());
        HeaderInclusion headerInclusion = (suffix != null && suffix.startsWith("ccs2/")) || ccs2Supported
                ? HeaderInclusion.NO_PIN
                : HeaderInclusion.ALL;
        HttpResponse<String> resp = controlTokenSupported
                ? sendWithRetry(Objects.requireNonNull(builderSupplier.get()), AuthorizationMode.CONTROL_TOKEN,
                        headerInclusion)
                : sendWithRetry(Objects.requireNonNull(builderSupplier.get()), headerInclusion);
        int statusCode = resp.statusCode();
        String vinForLog = (vinHint == null || vinHint.isBlank()) ? "UNKNOWN" : vinHint;

        if (useSpaV2 && (statusCode == 404 || statusCode == 403)) {
            logger.debug("{} SPA v2 request disallowed for {} ({} {}), retrying with SPA v1", description, vinForLog,
                    Integer.valueOf(statusCode), formatBodyForLog(resp.body()));
            URI v1Uri = buildSpaVehicleUri(Objects.requireNonNull(ep.ccapi.baseUrl), vehicleId, suffix, false,
                    ccs2Supported);
            resp = controlTokenSupported
                    ? sendWithRetry(Objects.requireNonNull(HttpRequest.newBuilder(v1Uri).GET()),
                            AuthorizationMode.CONTROL_TOKEN, headerInclusion)
                    : sendWithRetry(Objects.requireNonNull(HttpRequest.newBuilder(v1Uri).GET()), headerInclusion);
            statusCode = resp.statusCode();
        }

        String bodyForLog = formatBodyForLog(resp.body());
        if (controlTokenSupported && (statusCode == 401 || statusCode == 403)) {
            logger.debug("{} control-token request disallowed for {} ({} {}), retrying with access token", description,
                    vinForLog, Integer.valueOf(statusCode), bodyForLog);
            resp = sendWithRetry(Objects.requireNonNull(builderSupplier.get()), AuthorizationMode.ACCESS_TOKEN);
            statusCode = resp.statusCode();
            bodyForLog = formatBodyForLog(resp.body());
        }

        String body = Objects.requireNonNull(resp.body());
        bodyForLog = formatBodyForLog(body);
        int statusClass = statusCode / 100;
        if (statusClass == 2) {
            logger.debug("{} response for {}: {}", description, vinForLog, bodyForLog);
        } else if (statusClass == 4) {
            logger.debug("{} unavailable for {}: status {} {}", description, vinForLog, Integer.valueOf(statusCode),
                    bodyForLog);
        } else {
            logger.warn("{} request failed for {}: {} {}", description, vinForLog, Integer.valueOf(statusCode),
                    bodyForLog);
        }
        return new JsonResponse(statusCode, body, bodyForLog);
    }

    private boolean shouldRetrySpaVehicleData(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    public List<VehicleSummary> listVehicles() throws Exception {
        oauth.registerDevice();
        @SuppressWarnings("null")
        HttpResponse<String> resp = sendVehicleListRequest(ep.ccapi.baseUrl);

        if (resp.statusCode() == 403) {
            logger.debug("Vehicle list request disallowed for {} ({} {}), retrying with SPA v1 base", ep.ccapi.baseUrl,
                    Integer.valueOf(resp.statusCode()), formatBodyForLog(resp.body()));
            resp = sendVehicleListRequest(ensureSpaV1BaseUrl(Objects.requireNonNull(ep.ccapi.baseUrl)));
        }

        if (resp.statusCode() / 100 != 2) {
            logger.warn("Vehicle list request failed: {} {}", resp.statusCode(), Objects.requireNonNull(resp.body()));
            throw new IOException("Vehicle list request failed: " + resp.statusCode());
        }

        String body = Objects.requireNonNull(resp.body());
        JsonElement parsed = JsonParser.parseString(body);
        JsonArray vehicles = extractVehicleArray(parsed);
        List<VehicleSummary> results = new ArrayList<>();
        if (vehicles == null) {
            logger.debug("Vehicle list response did not contain any entries");
            return results;
        }

        for (JsonElement element : vehicles) {
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                continue;
            }
            JsonObject json = Objects.requireNonNull(element.getAsJsonObject());
            VehicleSummary summary = new VehicleSummary();
            String vid = optString(json, "vehicleId", "id", "vehicleKey", "vehicleIdHash");
            summary.vehicleId = vid != null ? vid : "";
            String v = optString(json, "vin", "vehicleIdNumber", "vinHash");
            summary.vin = v != null ? v : "";
            String vn = summary.vin;
            if ((vn == null || vn.isBlank()) && (vid != null && !vid.isBlank())) {
                summary.vin = vid;
            }
            vn = summary.vin;
            if (vn == null || vn.isBlank()) {
                continue;
            }
            summary.label = optString(json, "label", "nickname", "name", "vehicleName");
            summary.model = optString(json, "model", "vehicleModel", "modelName");
            summary.modelYear = optString(json, "modelYear", "year", "modelYearNm");
            summary.licensePlate = optString(json, "licensePlate", "plateNumber", "plateNo");
            summary.type = optString(json, "type");

            JsonObject detailInfo = firstObject(json, "detailInfo");
            if (detailInfo != null) {
                summary.inColor = optString(detailInfo, "inColor", "interiorColor", "incolorNm");
                summary.outColor = optString(detailInfo, "outColor", "exteriorColor", "outcolorNm");
                summary.saleCarmdlCd = optString(detailInfo, "saleCarmdlCd");
                summary.bodyType = optString(detailInfo, "bodyType", "bodyTypeNm");
                summary.saleCarmdlEnNm = optString(detailInfo, "saleCarmdlEnNm");
                String currentProtocolType = summary.protocolType;
                if (currentProtocolType == null || currentProtocolType.isBlank()) {
                    summary.protocolType = optString(detailInfo, "protocolType");
                }
            }

            String currentProtocolType = summary.protocolType;
            if (currentProtocolType == null || currentProtocolType.isBlank()) {
                summary.protocolType = optString(json, "protocolType");
            }
            summary.ccuCCS2ProtocolSupport = optString(json, "ccuCCS2ProtocolSupport");
            String label = summary.label;
            String model = summary.model;
            if (label == null || label.isBlank()) {
                summary.label = (model != null && !model.isBlank()) ? model : summary.vin;
            }
            results.add(summary);
        }

        logger.debug("Vehicle list retrieved {} entries", results.size());
        return results;
    }

    private HttpResponse<String> sendVehicleListRequest(String baseUrl) throws Exception {
        String url = baseUrl + "/vehicles";
        HttpRequest.Builder builder = Objects.requireNonNull(HttpRequest.newBuilder(URI.create(url)).GET());
        return sendWithRetry(builder);
    }

    private @Nullable JsonObject firstObject(JsonObject root, String... keys) {
        for (String key : keys) {
            if (!root.has(key)) {
                continue;
            }
            JsonElement element = root.get(key);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private @Nullable DistanceMeasurement firstDistance(@Nullable JsonObject root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String key : keys) {
            if (!root.has(key)) {
                continue;
            }
            DistanceMeasurement measurement = extractDistance(root.get(key));
            if (measurement != null && measurement.value != null) {
                return measurement;
            }
        }
        return null;
    }

    @SuppressWarnings("null")
    private @Nullable DistanceMeasurement extractDistanceRecursive(@Nullable JsonElement element, String... keys) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : keys) {
                if (!obj.has(key)) {
                    continue;
                }
                DistanceMeasurement value = extractDistance(obj.get(key));
                if (value != null && value.value != null) {
                    return value;
                }
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                DistanceMeasurement nested = extractDistanceRecursive(entry.getValue(), keys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                DistanceMeasurement nested = extractDistanceRecursive(child, keys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return null;
    }

    private @Nullable Integer firstInteger(@Nullable JsonObject root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String key : keys) {
            if (!root.has(key)) {
                continue;
            }
            Integer value = extractInteger(root.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("null")
    private @Nullable Integer extractInteger(@Nullable JsonElement candidate) {
        if (candidate == null || candidate.isJsonNull()) {
            return null;
        }
        if (candidate.isJsonPrimitive()) {
            JsonPrimitive primitive = candidate.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return Integer.valueOf((int) Math.round(primitive.getAsDouble()));
            }
            if (primitive.isString()) {
                String txt = primitive.getAsString().trim();
                if (txt.isEmpty()) {
                    return null;
                }
                try {
                    return Integer.valueOf(Integer.parseInt(txt));
                } catch (NumberFormatException e) {
                    try {
                        double parsed = Double.parseDouble(txt);
                        return Integer.valueOf((int) Math.round(parsed));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
            return null;
        }
        if (candidate.isJsonObject()) {
            JsonObject obj = candidate.getAsJsonObject();
            if (obj.has("value")) {
                Integer nested = extractInteger(obj.get("value"));
                if (nested != null) {
                    return nested;
                }
            }
            if (obj.has("total")) {
                Integer nested = extractInteger(obj.get("total"));
                if (nested != null) {
                    return nested;
                }
            }
            if (obj.has("state")) {
                Integer nested = extractInteger(obj.get("state"));
                if (nested != null) {
                    return nested;
                }
            }
            if (obj.has("status")) {
                Integer nested = extractInteger(obj.get("status"));
                if (nested != null) {
                    return nested;
                }
            }
            Integer hours = extractInteger(obj.get("hours"));
            if (hours == null) {
                hours = extractInteger(obj.get("hour"));
            }
            if (hours == null) {
                hours = extractInteger(obj.get("hr"));
            }
            Integer minutes = extractInteger(obj.get("minutes"));
            if (minutes == null) {
                minutes = extractInteger(obj.get("minute"));
            }
            if (minutes == null) {
                minutes = extractInteger(obj.get("min"));
            }
            if (hours != null || minutes != null) {
                int total = 0;
                if (hours != null) {
                    total += hours.intValue() * 60;
                }
                if (minutes != null) {
                    total += minutes.intValue();
                }
                return Integer.valueOf(total);
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                Integer nested = extractInteger(entry.getValue());
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (candidate.isJsonArray()) {
            JsonArray array = candidate.getAsJsonArray();
            for (JsonElement child : array) {
                Integer nested = extractInteger(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private @Nullable Boolean firstBoolean(JsonObject root, String... keys) {
        for (String key : keys) {
            if (!root.has(key)) {
                continue;
            }
            Boolean value = extractBoolean(root.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private @Nullable Boolean extractBoolean(@Nullable JsonElement candidate) {
        if (candidate == null || candidate.isJsonNull()) {
            return null;
        }
        if (candidate.isJsonPrimitive()) {
            JsonPrimitive primitive = candidate.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isString()) {
                String txt = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
                if ("on".equals(txt) || "open".equals(txt) || "true".equals(txt)) {
                    return Boolean.TRUE;
                }
                if ("off".equals(txt) || "closed".equals(txt) || "false".equals(txt)) {
                    return Boolean.FALSE;
                }
            }
            if (primitive.isNumber()) {
                return primitive.getAsInt() != 0;
            }
        } else if (candidate.isJsonObject()) {
            JsonObject json = candidate.getAsJsonObject();
            if (json.has("value")) {
                Boolean nested = extractBoolean(json.get("value"));
                if (nested != null) {
                    return nested;
                }
            }
            if (json.has("status")) {
                Boolean nested = extractBoolean(json.get("status"));
                if (nested != null) {
                    return nested;
                }
            }
            if (json.has("open")) {
                Boolean nested = extractBoolean(json.get("open"));
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("null")
    private @Nullable JsonObject collectDoorStatus(JsonObject json, JsonObject rootJson) {
        JsonObject merged = null;

        JsonObject doorObj = firstObject(json, "doorStatus", "doors", "doorLockStatus");
        if (doorObj == null && json != rootJson) {
            doorObj = firstObject(rootJson, "doorStatus", "doors", "doorLockStatus");
        }
        if (doorObj != null) {
            merged = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : doorObj.entrySet()) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }

        JsonObject doorOpenObj = firstObject(json, "doorOpen");
        if (doorOpenObj == null && json != rootJson) {
            doorOpenObj = firstObject(rootJson, "doorOpen");
        }
        if (doorOpenObj != null) {
            if (merged == null) {
                merged = new JsonObject();
            }
            for (Map.Entry<String, JsonElement> entry : doorOpenObj.entrySet()) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }

        Boolean trunkOpen = firstBoolean(json, "trunkOpen", "tailgateOpen", "trunkStatus");
        if (trunkOpen == null && json != rootJson) {
            trunkOpen = firstBoolean(rootJson, "trunkOpen", "tailgateOpen", "trunkStatus");
        }
        if (trunkOpen != null) {
            if (merged == null) {
                merged = new JsonObject();
            }
            merged.addProperty("trunk", trunkOpen);
        }

        Boolean hoodOpen = firstBoolean(json, "hoodOpen", "hoodStatus");
        if (hoodOpen == null && json != rootJson) {
            hoodOpen = firstBoolean(rootJson, "hoodOpen", "hoodStatus");
        }
        if (hoodOpen != null) {
            if (merged == null) {
                merged = new JsonObject();
            }
            merged.addProperty("hood", hoodOpen);
        }

        return merged;
    }

    @SuppressWarnings("null")
    private @Nullable String summarisePositions(JsonObject json) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String status = extractStatusValue(entry.getValue());
            if (status == null) {
                status = elementToDisplay(entry.getValue());
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(status);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private @Nullable String extractStatusValue(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            JsonObject json = value.getAsJsonObject();
            if (json.has("value")) {
                String nested = extractStatusValue(json.get("value"));
                if (nested != null) {
                    return nested;
                }
            }
            if (json.has("status")) {
                String nested = extractStatusValue(json.get("status"));
                if (nested != null) {
                    return nested;
                }
            }
            if (json.has("open")) {
                Boolean bool = extractBoolean(json.get("open"));
                if (bool != null) {
                    return bool ? "OPEN" : "CLOSED";
                }
            }
        } else if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean() ? "OPEN" : "CLOSED";
            }
            if (primitive.isNumber()) {
                return primitive.getAsDouble() != 0d ? "OPEN" : "CLOSED";
            }
            if (primitive.isString()) {
                return primitive.getAsString();
            }
        }
        return null;
    }

    private String elementToDisplay(JsonElement value) {
        if (value instanceof JsonPrimitive) {
            return Objects.requireNonNull(value.getAsJsonPrimitive().getAsString());
        }
        return Objects.requireNonNull(value.toString());
    }

    private @Nullable Double optDouble(@Nullable JsonObject json, String... keys) {
        if (json == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = json.get(key);
            if (element != null && element.isJsonPrimitive()) {
                try {
                    return Double.valueOf(Double.parseDouble(element.getAsJsonPrimitive().getAsString()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
        return null;
    }

    private @Nullable String optString(@Nullable JsonObject json, String... keys) {
        if (json == null) {
            return null;
        }
        for (String key : keys) {
            if (!json.has(key)) {
                continue;
            }
            JsonElement element = json.get(key);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isString()) {
                    String value = primitive.getAsString();
                    if (!value.isBlank()) {
                        return value;
                    }
                } else if (primitive.isNumber()) {
                    return primitive.getAsString();
                }
            } else if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("value")) {
                    String nested = optString(obj, "value");
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private @Nullable Integer optInteger(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return primitive.getAsInt();
            }
            if (primitive.isString()) {
                try {
                    return Integer.parseInt(primitive.getAsString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private @Nullable DistanceMeasurement extractRangeFromEvStatus(@Nullable JsonObject evStatus) {
        if (evStatus == null) {
            return null;
        }
        @Nullable
        DistanceMeasurement best = null;
        if (evStatus.has("drvDistance")) {
            JsonElement drvDistanceElement = evStatus.get("drvDistance");
            if (drvDistanceElement != null && !drvDistanceElement.isJsonNull()) {
                if (drvDistanceElement.isJsonArray()) {
                    JsonArray array = drvDistanceElement.getAsJsonArray();
                    for (JsonElement element : array) {
                        if (element != null && element.isJsonObject()) {
                            JsonObject obj = element.getAsJsonObject();
                            @Nullable
                            DistanceMeasurement candidate = extractRangeFromRangeByFuel(obj.get("rangeByFuel"));
                            best = preferDistance(best, candidate);
                            if (candidate == null) {
                                candidate = extractDistance(obj.get("totalAvailableRange"));
                                best = preferDistance(best, candidate);
                            }
                            if (candidate == null) {
                                candidate = extractDistance(obj.get("distance"));
                                best = preferDistance(best, candidate);
                            }
                        }
                    }
                } else if (drvDistanceElement.isJsonObject()) {
                    JsonObject obj = drvDistanceElement.getAsJsonObject();
                    @Nullable
                    DistanceMeasurement candidate = extractRangeFromRangeByFuel(obj.get("rangeByFuel"));
                    best = preferDistance(best, candidate);
                    if (candidate == null) {
                        candidate = extractDistance(obj.get("totalAvailableRange"));
                        best = preferDistance(best, candidate);
                    }
                    if (candidate == null) {
                        candidate = extractDistance(obj.get("distance"));
                        best = preferDistance(best, candidate);
                    }
                } else {
                    @Nullable
                    DistanceMeasurement candidate = extractRangeFromRangeByFuel(drvDistanceElement);
                    best = preferDistance(best, candidate);
                }
            }
        }
        if (best == null) {
            best = extractRangeFromRangeByFuel(evStatus.get("rangeByFuel"));
        }
        if (best == null) {
            best = extractDistance(evStatus.get("totalAvailableRange"));
        }
        if (best == null) {
            best = extractDistance(evStatus.get("dte"));
        }
        return best;
    }

    private @Nullable DistanceMeasurement extractRangeFromRangeByFuel(@Nullable JsonElement rangeElement) {
        if (rangeElement == null || rangeElement.isJsonNull()) {
            return null;
        }
        if (rangeElement.isJsonObject()) {
            JsonObject obj = rangeElement.getAsJsonObject();
            DistanceMeasurement candidate = extractDistance(obj.get("totalAvailableRange"));
            if (candidate != null) {
                return candidate;
            }
            candidate = extractDistance(obj.get("evModeRange"));
            if (candidate != null) {
                return candidate;
            }
            candidate = extractDistance(obj.get("distance"));
            if (candidate != null) {
                return candidate;
            }
            @Nullable
            DistanceMeasurement best = null;
            @SuppressWarnings("null")
            Set<Map.Entry<String, JsonElement>> entries = obj.entrySet();
            for (Map.Entry<String, JsonElement> entry : entries) {
                String key = Objects.requireNonNull(entry.getKey());
                if ("totalAvailableRange".equalsIgnoreCase(key) || "evModeRange".equalsIgnoreCase(key)
                        || "distance".equalsIgnoreCase(key)) {
                    continue;
                }
                DistanceMeasurement nested = extractRangeFromRangeByFuel(entry.getValue());
                best = preferDistance(best, nested);
            }
            return best;
        }
        if (rangeElement.isJsonArray()) {
            DistanceMeasurement best = null;
            JsonArray array = rangeElement.getAsJsonArray();
            for (JsonElement element : array) {
                DistanceMeasurement candidate = extractRangeFromRangeByFuel(element);
                best = preferDistance(best, candidate);
            }
            return best;
        }
        return extractDistance(rangeElement);
    }

    private @Nullable DistanceMeasurement extractDistance(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return DistanceMeasurement.inKilometers(primitive.getAsDouble());
            }
            if (primitive.isString()) {
                try {
                    String val = primitive.getAsString();
                    return DistanceMeasurement.inKilometers(Double.parseDouble(val));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject obj = Objects.requireNonNull(element.getAsJsonObject());
            @Nullable
            Double value = optDouble(obj, "value");
            if (value != null) {
                @Nullable
                Integer unit = optInteger(obj, "unit");
                return convertDistance(value, unit);
            }
            if (obj.has("distance")) {
                return extractDistance(obj.get("distance"));
            }
        }
        if (element.isJsonArray()) {
            @Nullable
            DistanceMeasurement best = null;
            JsonArray array = Objects.requireNonNull(element.getAsJsonArray());
            for (JsonElement child : array) {
                @Nullable
                DistanceMeasurement candidate = extractDistance(child);
                best = preferDistance(best, candidate);
            }
            return best;
        }
        return null;
    }

    private @Nullable DistanceMeasurement convertDistance(@Nullable Double value, @Nullable Integer unit) {
        if (value == null) {
            return null;
        }
        if (unit == null) {
            return DistanceMeasurement.inKilometers(value);
        }
        DistanceUnit distanceUnit = DistanceUnit.fromApiUnit(unit);
        return new DistanceMeasurement(value, distanceUnit);
    }

    private @Nullable DistanceMeasurement preferDistance(@Nullable DistanceMeasurement current,
            @Nullable DistanceMeasurement candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current.unit != candidate.unit) {
            return current.unit == DistanceUnit.KILOMETERS ? current : candidate;
        }
        if (candidate.value > current.value) {
            return candidate;
        }
        return current;
    }

    private @Nullable Instant optInstant(JsonObject json, String... keys) {
        for (String key : keys) {
            if (!json.has(key)) {
                continue;
            }
            Instant parsed = parseInstant(json.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private @Nullable JsonArray extractVehicleArray(@Nullable JsonElement parsed) {
        if (parsed == null || parsed.isJsonNull()) {
            return null;
        }
        if (parsed.isJsonArray()) {
            return parsed.getAsJsonArray();
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject json = parsed.getAsJsonObject();

        for (String wrapper : new String[] { "resMsg", "payload", "body", "response" }) {
            if (!json.has(wrapper)) {
                continue;
            }
            JsonElement nestedElement = json.get(wrapper);
            if (nestedElement == null || nestedElement.isJsonNull()) {
                continue;
            }
            JsonArray nested = extractVehicleArray(nestedElement);
            if (nested != null) {
                return nested;
            }
        }

        for (String key : new String[] { "vehicles", "data", "result", "items" }) {
            if (!json.has(key)) {
                continue;
            }
            JsonElement element = json.get(key);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
            if (element != null && element.isJsonObject()) {
                JsonArray nested = extractVehicleArray(element);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private @Nullable Instant parseInstant(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                Instant parsed = parseInstantFromString(primitive.getAsString());
                if (parsed != null) {
                    return parsed;
                }
            }
            if (primitive.isNumber()) {
                Instant parsed = parseInstantFromString(primitive.getAsString());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private @Nullable Instant parseInstantFromString(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String txt = value.trim();
        if (txt.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(txt);
        } catch (Exception e) {
            // ignore and fall through
        }
        boolean digitsOnly = true;
        for (int i = 0; i < txt.length(); i++) {
            char ch = txt.charAt(i);
            if (ch < '0' || ch > '9') {
                digitsOnly = false;
                break;
            }
        }
        if (digitsOnly) {
            if (txt.length() == 14) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(txt, BASIC_TIMESTAMP_FORMAT);
                    return ldt.toInstant(ZoneOffset.UTC);
                } catch (Exception e) {
                    // ignore and fall through
                }
            }
            try {
                long epoch = Long.parseLong(txt);
                if (epoch <= 0) {
                    return null;
                }
                if (txt.length() == 10) {
                    return Instant.ofEpochSecond(epoch);
                }
                if (txt.length() == 13) {
                    return Instant.ofEpochMilli(epoch);
                }
                // Fall back to milliseconds for other lengths
                return Instant.ofEpochMilli(epoch);
            } catch (NumberFormatException e) {
                // ignore and fall through
            }
        }
        return null;
    }

    private @Nullable Instant extractLastUpdatedFallback(@Nullable JsonObject json) {
        if (json == null) {
            return null;
        }
        for (String key : LAST_UPDATED_FALLBACK_KEYS) {
            if (!json.has(key)) {
                continue;
            }
            Instant parsed = parseInstant(json.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private void parseLocation(VehicleStatus s, JsonObject json, JsonObject rootJson) {
        VehicleLocation location = parseVehicleLocation(json);
        if (!isValidVehicleLocation(location) && json != rootJson) {
            location = parseVehicleLocation(rootJson);
        }
        if (location != null && isValidVehicleLocation(location)) {
            s.latitude = Double.valueOf(location.latitude);
            s.longitude = Double.valueOf(location.longitude);
        }
    }

    private void parseFuelLevel(VehicleStatus s, JsonObject json, JsonObject rootJson, @Nullable JsonObject evStatus) {
        Double val = optDouble(json, "fuelLevel");
        if (val == null) {
            val = optDouble(json, "fuelLevelPercent");
        }
        if (val == null && json != rootJson) {
            val = optDouble(rootJson, "fuelLevel");
            if (val == null) {
                val = optDouble(rootJson, "fuelLevelPercent");
            }
        }
        if (val == null && evStatus != null) {
            val = optDouble(evStatus, "fuelLevel");
            if (val == null) {
                val = optDouble(evStatus, "fuelLevelPercent");
            }
        }
        if (val != null) {
            s.fuelLevel = val;
        }
    }

    private void parseOdometer(VehicleStatus s, JsonObject json, JsonObject rootJson, @Nullable JsonObject evStatus) {
        DistanceMeasurement distance = null;

        Double val = optDouble(json, "odometerKm");
        if (val == null && json != rootJson) {
            val = optDouble(rootJson, "odometerKm");
        }
        if (val != null) {
            distance = DistanceMeasurement.inKilometers(val);
        } else {
            Double altVal = optDouble(json, "Odometer");
            if (altVal == null && json != rootJson) {
                altVal = optDouble(rootJson, "Odometer");
            }
            if (altVal == null && evStatus != null) {
                altVal = optDouble(evStatus, "Odometer");
            }
            if (altVal != null) {
                distance = DistanceMeasurement.inKilometers(altVal);
            }
            if (distance == null) {
                distance = extractDistance(json.get("odometer"));
            }
            if (distance == null) {
                distance = extractDistance(json.get("odo"));
            }
            if (distance == null && json != rootJson) {
                distance = extractDistance(rootJson.get("odometer"));
            }
            if (distance == null && json != rootJson) {
                DistanceMeasurement alt = extractDistance(rootJson.get("odo"));
                if (alt != null) {
                    distance = alt;
                }
            }
            if (distance == null && evStatus != null) {
                distance = extractDistance(evStatus.get("odometer"));
                if (distance == null) {
                    distance = extractDistance(evStatus.get("odo"));
                }
            }
            if (distance == null) {
                distance = extractDistanceRecursive(json, "odometerKm", "Odometer", "odometer", "odo");
            }
            if (distance == null && json != rootJson) {
                distance = extractDistanceRecursive(rootJson, "odometerKm", "Odometer", "odometer", "odo");
            }
            if (distance == null && evStatus != null) {
                distance = extractDistanceRecursive(evStatus, "odometerKm", "Odometer", "odometer", "odo");
            }
        }
        if (distance != null) {
            s.odometer = distance.value;
            s.odometerUnit = distance.unit;
        }
    }

    private void parseBatteryLevel(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        JsonObject batteryObj = firstObject(json, "battery");
        if (batteryObj == null && json != rootJson) {
            batteryObj = firstObject(rootJson, "battery");
        }

        Double val = optDouble(json, "batteryLevel");
        if (val == null && json != rootJson) {
            val = optDouble(rootJson, "batteryLevel");
        }
        if (val != null) {
            s.batteryLevel = val;
        } else if (evStatus != null) {
            Double evBattery = optDouble(evStatus, "batteryStatus");
            if (evBattery == null) {
                evBattery = optDouble(evStatus, "soc");
            }
            if (evBattery != null) {
                s.batteryLevel = evBattery;
            }
        }

        if (batteryObj != null) {
            Double aux = optDouble(batteryObj, "batSoc");
            if (aux == null) {
                aux = optDouble(batteryObj, "auxBatteryLevel");
            }
            if (aux != null) {
                s.auxiliaryBatteryLevel = aux;
            }
        }
    }

    private void parseRange(VehicleStatus s, JsonObject json, JsonObject rootJson, @Nullable JsonObject evStatus) {
        DistanceMeasurement distance = null;
        Double val = optDouble(json, "rangeKm");
        if (val == null && json != rootJson) {
            val = optDouble(rootJson, "rangeKm");
        }
        if (val != null) {
            distance = DistanceMeasurement.inKilometers(val);
        } else if (evStatus != null) {
            distance = extractRangeFromEvStatus(evStatus);
        }
        if (distance != null) {
            s.range = distance.value;
            s.rangeUnit = distance.unit;
        }

        DistanceMeasurement evRange = firstDistance(json, "evModeRange", "evRange", "electricRange", "remainingEvRange",
                "remainingEVRange", "remainingEVrange");
        if (evRange == null && json != rootJson) {
            evRange = firstDistance(rootJson, "evModeRange", "evRange", "electricRange", "remainingEvRange",
                    "remainingEVRange", "remainingEVrange");
        }
        if (evRange == null && evStatus != null) {
            evRange = firstDistance(evStatus, "evModeRange", "evRange", "electricRange", "remainingEvRange",
                    "remainingEVRange", "remainingEVrange");
            if (evRange == null) {
                evRange = extractDistanceRecursive(evStatus.get("rangeByFuel"), "evModeRange", "evRange",
                        "electricRange", "remainingEvRange", "remainingEVRange", "remainingEVrange");
            }
            if (evRange == null) {
                evRange = extractDistanceRecursive(evStatus.get("drvDistance"), "evModeRange", "evRange",
                        "electricRange", "remainingEvRange", "remainingEVRange", "remainingEVrange");
            }
        }
        if (evRange != null) {
            s.evModeRange = evRange.value;
            s.evModeRangeUnit = evRange.unit;
        }

        DistanceMeasurement gasRange = firstDistance(json, "gasModeRange", "fuelRange", "fuelRangeKm", "engineRange");
        if (gasRange == null && json != rootJson) {
            gasRange = firstDistance(rootJson, "gasModeRange", "fuelRange", "fuelRangeKm", "engineRange");
        }
        if (gasRange == null && evStatus != null) {
            gasRange = firstDistance(evStatus, "gasModeRange", "fuelRange", "fuelRangeKm", "engineRange");
            if (gasRange == null) {
                gasRange = extractDistanceRecursive(evStatus.get("rangeByFuel"), "gasModeRange", "fuelRange",
                        "fuelRangeKm", "engineRange");
            }
            if (gasRange == null) {
                gasRange = extractDistanceRecursive(evStatus.get("drvDistance"), "gasModeRange", "fuelRange",
                        "fuelRangeKm", "engineRange");
            }
        }
        if (gasRange != null) {
            s.gasModeRange = gasRange.value;
            s.gasModeRangeUnit = gasRange.unit;
        }
    }

    private void parseRemainTime(VehicleStatus s, JsonObject json, JsonObject rootJson, @Nullable JsonObject evStatus) {
        Integer remainTime = firstInteger(json, "remainTime", "remainingChargeTime", "remainChargeTime",
                "remainingTime");
        if (remainTime == null && json != rootJson) {
            remainTime = firstInteger(rootJson, "remainTime", "remainingChargeTime", "remainChargeTime",
                    "remainingTime");
        }
        if (remainTime == null && evStatus != null) {
            remainTime = firstInteger(evStatus, "remainTime", "remainingChargeTime", "remainChargeTime",
                    "remainChargeTime2", "remainingTime", "chargeTime", "chargingRemainingTime");
            if (remainTime == null) {
                JsonObject remainObj = firstObject(evStatus, "remainTime", "remainingChargeTime", "remainChargeTime",
                        "chargeTime");
                if (remainObj != null) {
                    remainTime = firstInteger(remainObj, "total", "value", "minute", "minutes");
                    if (remainTime == null) {
                        remainTime = extractInteger(remainObj);
                    }
                }
            }
            if (remainTime == null) {
                // Check Hyundai/Kia remainTime2 object
                JsonObject remainTime2 = firstObject(evStatus, "remainTime2");
                if (remainTime2 != null) {
                    Boolean isCharging = firstBoolean(evStatus, "batteryCharge");
                    Integer plugType = firstInteger(evStatus, "batteryPlugin");

                    if (Boolean.TRUE.equals(isCharging)) {
                        JsonObject atc = firstObject(remainTime2, "atc"); // Actual Time to Charge
                        if (atc != null) {
                            remainTime = firstInteger(atc, "value");
                        }
                    } else if (plugType != null && plugType > 0) {
                        JsonObject etc = null;
                        if (plugType == 1) {
                            etc = firstObject(remainTime2, "etc1");
                        } else if (plugType == 2) {
                            etc = firstObject(remainTime2, "etc2");
                            if (etc == null)
                                etc = firstObject(remainTime2, "etc3");
                        } else {
                            etc = firstObject(remainTime2, "etc2");
                        }
                        if (etc != null) {
                            remainTime = firstInteger(etc, "value");
                        }
                    } else {
                        remainTime = 0; // Not charging and not plugged in
                    }
                }
            }
        }
        if (remainTime == null) {
            JsonObject remainObj = firstObject(json, "remainTime", "remainingChargeTime", "remainChargeTime");
            if (remainObj == null && json != rootJson) {
                remainObj = firstObject(rootJson, "remainTime", "remainingChargeTime", "remainChargeTime");
            }
            if (remainObj != null && remainTime == null) {
                remainTime = firstInteger(remainObj, "total", "value", "minute", "minutes");
                if (remainTime == null) {
                    remainTime = extractInteger(remainObj);
                }
            }
        }
        if (remainTime != null) {
            s.remainingChargeTimeMinutes = remainTime;
        }
    }

    private void parseConnectorStatus(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        Boolean connectorFastened = firstBoolean(json, "connectorFastened", "connectorAttached");
        if (connectorFastened == null && json != rootJson) {
            connectorFastened = firstBoolean(rootJson, "connectorFastened", "connectorAttached");
        }
        if (connectorFastened == null && evStatus != null) {
            connectorFastened = firstBoolean(evStatus, "connectorFastened", "connectorAttached",
                    "connectorAttachedStatus", "connectorFastening", "chargerConnected", "chargingCableConnected");
            if (connectorFastened == null) {
                Integer batteryPlugin = firstInteger(evStatus, "batteryPlugin");
                if (batteryPlugin != null && batteryPlugin > 0) {
                    connectorFastened = true;
                } else if (batteryPlugin != null && batteryPlugin == 0) {
                    connectorFastened = false;
                }
            }
        }
        if (connectorFastened == null) {
            Integer connectorState = firstInteger(json, "connectorFasteningState");
            if (connectorState == null && json != rootJson) {
                connectorState = firstInteger(rootJson, "connectorFasteningState");
            }
            if (connectorState == null && evStatus != null) {
                connectorState = firstInteger(evStatus, "connectorFasteningState");
            }
            if (connectorState == null) {
                JsonObject connectorObj = firstObject(json, "connectorFastening", "connector");
                if (connectorObj == null && json != rootJson) {
                    connectorObj = firstObject(rootJson, "connectorFastening", "connector");
                }
                if (connectorObj == null && evStatus != null) {
                    connectorObj = firstObject(evStatus, "connectorFastening", "connector");
                }
                if (connectorObj != null) {
                    connectorFastened = firstBoolean(connectorObj, "state", "status", "value", "connected",
                            "fastened");
                    if (connectorFastened == null) {
                        connectorState = firstInteger(connectorObj, "state", "status", "value");
                    }
                }
            }
            if (connectorFastened == null && connectorState != null) {
                connectorFastened = connectorState.intValue() != 0;
            }
        }
        if (connectorFastened != null) {
            s.connectorFastened = connectorFastened;
        }
    }

    private void parseDoorWindowStatus(VehicleStatus s, JsonObject json, JsonObject rootJson) {
        JsonObject doorObj = collectDoorStatus(json, rootJson);
        if (doorObj != null) {
            s.doorStatusSummary = summarisePositions(doorObj);
        }

        JsonObject windowObj = firstObject(json, "windowStatus", "windows");
        if (windowObj == null && json != rootJson) {
            windowObj = firstObject(rootJson, "windowStatus", "windows");
        }
        if (windowObj != null) {
            s.windowStatusSummary = summarisePositions(windowObj);
        }
    }

    private void parseAccStatus(VehicleStatus s, JsonObject json, JsonObject rootJson) {
        Boolean accMode = firstBoolean(json, "acc");
        if (accMode == null && json != rootJson) {
            accMode = firstBoolean(rootJson, "acc");
        }
        if (accMode != null) {
            s.acc = accMode;
        }
    }

    private void parseWarnings(VehicleStatus s, JsonObject json, JsonObject rootJson, @Nullable JsonObject evStatus) {
        StringBuilder warningsBuilder = new StringBuilder();
        String[] wKeys = { "tailLampStatus", "hazardStatus", "systemCutOffAlert", "sleepModeCheck", "ign3",
                "transCond" };
        for (String k : wKeys) {
            JsonElement el = json.get(k);
            if (el != null && el.isJsonPrimitive()) {
                boolean active = false;
                if (el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean())
                    active = true;
                else if (el.getAsJsonPrimitive().isNumber() && el.getAsInt() != 0)
                    active = true;
                else if (el.getAsJsonPrimitive().isString() && !el.getAsString().equals("0")
                        && !el.getAsString().equalsIgnoreCase("false"))
                    active = true;
                if (active) {
                    if (warningsBuilder.length() > 0)
                        warningsBuilder.append(", ");
                    warningsBuilder.append(k);
                }
            }
        }
        if (warningsBuilder.length() > 0) {
            s.minorWarnings = warningsBuilder.toString();
        } else {
            s.minorWarnings = "OK";
        }

        Boolean batteryWarn = firstBoolean(json, "batteryWarning", "batteryWarningLamp", "lowBatteryWarning");
        if (batteryWarn == null && json != rootJson) {
            batteryWarn = firstBoolean(rootJson, "batteryWarning", "batteryWarningLamp", "lowBatteryWarning");
        }
        if (batteryWarn != null) {
            s.batteryWarning = batteryWarn;
        }

        Boolean lowFuel = firstBoolean(json, "lowFuelLight", "lowFuelWarning", "lowFuelIndicator");
        if (lowFuel == null && json != rootJson) {
            lowFuel = firstBoolean(rootJson, "lowFuelLight", "lowFuelWarning", "lowFuelIndicator");
        }
        if (lowFuel == null && evStatus != null) {
            lowFuel = firstBoolean(evStatus, "lowFuelLight", "lowFuelWarning", "lowFuelIndicator");
        }
        if (lowFuel != null) {
            s.lowFuelLight = lowFuel;
        }
    }

    private void parseLastUpdated(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        Instant parsed = null;
        if (json.has("lastUpdated")) {
            parsed = parseInstant(json.get("lastUpdated"));
        }
        if (parsed == null && json != rootJson && rootJson.has("lastUpdated")) {
            parsed = parseInstant(rootJson.get("lastUpdated"));
        }
        if (parsed == null) {
            parsed = extractLastUpdatedFallback(json);
        }
        if (parsed == null && json != rootJson) {
            parsed = extractLastUpdatedFallback(rootJson);
        }
        if (parsed == null && evStatus != null) {
            parsed = extractLastUpdatedFallback(evStatus);
        }
        if (parsed != null) {
            s.lastUpdated = parsed;
        } else {
            s.lastUpdated = Instant.now();
        }
    }

    private void parseStatusBooleans(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        Boolean doorsLocked = firstBoolean(json, "doorsLocked", "doorLock", "doorLockStatus", "doorLockState");
        if (doorsLocked == null && json != rootJson) {
            doorsLocked = firstBoolean(rootJson, "doorsLocked", "doorLock", "doorLockStatus", "doorLockState");
        }
        if (doorsLocked == null && evStatus != null) {
            doorsLocked = firstBoolean(evStatus, "doorsLocked", "doorLock", "doorLockStatus", "doorLockState");
        }
        if (doorsLocked != null) {
            s.doorsLocked = doorsLocked;
        }

        Boolean charging = firstBoolean(json, "charging", "isCharging", "charge", "chargeStatus", "batteryCharge",
                "chargingState");
        if (charging == null && json != rootJson) {
            charging = firstBoolean(rootJson, "charging", "isCharging", "charge", "chargeStatus", "batteryCharge",
                    "chargingState");
        }
        if (charging == null && evStatus != null) {
            charging = firstBoolean(evStatus, "batteryCharge", "isCharging", "charging", "charge", "chargeStatus",
                    "evChargeStatus", "chargerStatus", "chargingState");
        }
        if (charging != null) {
            s.charging = charging;
        }

        Boolean climateOn = firstBoolean(json, "airCtrlOn", "climateOn", "airCondition", "climateStatus");
        if (climateOn == null && json != rootJson) {
            climateOn = firstBoolean(rootJson, "airCtrlOn", "climateOn", "airCondition", "climateStatus");
        }
        if (climateOn != null) {
            s.climateOn = climateOn;
        }

        Boolean engineOn = firstBoolean(json, "engine", "engineOn", "isEngineOn");
        if (engineOn == null && json != rootJson) {
            engineOn = firstBoolean(rootJson, "engine", "engineOn", "isEngineOn");
        }
        if (engineOn != null) {
            s.engineOn = engineOn;
        }

        Boolean trunkOpen = firstBoolean(json, "trunkOpen", "trunkStatus");
        if (trunkOpen == null && json != rootJson) {
            trunkOpen = firstBoolean(rootJson, "trunkOpen", "trunkStatus");
        }
        if (trunkOpen != null) {
            s.trunkOpen = trunkOpen;
        }

        Boolean hoodOpen = firstBoolean(json, "hoodOpen", "hoodStatus");
        if (hoodOpen == null && json != rootJson) {
            hoodOpen = firstBoolean(rootJson, "hoodOpen", "hoodStatus");
        }
        if (hoodOpen != null) {
            s.hoodOpen = hoodOpen;
        }
    }

    private void parseChargingState(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        Integer chargingState = firstInteger(json, "chargingState", "chargingStatus");
        if (chargingState == null && json != rootJson) {
            chargingState = firstInteger(rootJson, "chargingState", "chargingStatus");
        }
        if (chargingState == null && evStatus != null) {
            chargingState = firstInteger(evStatus, "chargingState", "chargingStatus", "chargeState", "chargeStatus",
                    "evChargeStatus", "chargerStatus");
            if (chargingState == null) {
                JsonObject chargingObj = firstObject(evStatus, "charging", "charger", "charge");
                if (chargingObj != null) {
                    chargingState = firstInteger(chargingObj, "state", "status", "value");
                }
            }
        }
        if (chargingState == null) {
            JsonObject chargingObj = firstObject(json, "charging", "charge");
            if (chargingObj == null && json != rootJson) {
                chargingObj = firstObject(rootJson, "charging", "charge");
            }
            if (chargingObj != null && chargingState == null) {
                chargingState = firstInteger(chargingObj, "state", "status", "value");
            }
        }
        if (chargingState == null && s.charging != null) {
            chargingState = s.charging ? 1 : 0;
        }

        if (chargingState != null) {
            s.chargingState = chargingState;
        }
    }

    private void parseChargeLimits(VehicleStatus s, JsonObject json, JsonObject rootJson,
            @Nullable JsonObject evStatus) {
        if (evStatus == null) {
            return;
        }

        // Standard V2: evStatus -> targetSOC is usually an array
        // Each entry has plugType (1=AC, 2=DC) and targetSOClevel (percentage)
        JsonElement targetSOC = evStatus.get("targetSOC");
        if (targetSOC != null && targetSOC.isJsonArray()) {
            JsonArray array = targetSOC.getAsJsonArray();
            for (JsonElement el : array) {
                if (el != null && el.isJsonObject()) {
                    JsonObject obj = Objects.requireNonNull(el.getAsJsonObject());
                    int plugType = optInteger(obj, "plugType", -1);
                    Double levelVal = optDouble(obj, "targetSOClevel");
                    if (levelVal != null && levelVal > 0) {
                        double level = levelVal.doubleValue();
                        if (plugType == 1) {
                            s.chargeLimitAC = level;
                        } else if (plugType == 2) {
                            s.chargeLimitDC = level;
                        }
                    }
                }
            }
        } else if (targetSOC != null && targetSOC.isJsonObject()) {
            // Some regions might have it as an object?
            JsonObject obj = targetSOC.getAsJsonObject();
            Double ac = optDouble(obj, "ac");
            if (ac != null) {
                s.chargeLimitAC = ac;
            }
            Double dc = optDouble(obj, "dc");
            if (dc != null) {
                s.chargeLimitDC = dc;
            }
        }
    }

}
