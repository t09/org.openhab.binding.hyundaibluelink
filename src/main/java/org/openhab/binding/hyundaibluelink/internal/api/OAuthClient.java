package org.openhab.binding.hyundaibluelink.internal.api;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the OAuth based authentication against the BlueLink back end. The
 * implementation performs the so called "stamp" login which is used by the
 * official mobile applications. The required stamp value is provided by the
 * {@link StampProvider}.
 */
public class OAuthClient {

    private final Logger logger = LoggerFactory.getLogger(OAuthClient.class);

    private static final Pattern QUERY_TOKEN_PATTERN = Pattern
            .compile("((?:access|refresh|id)_token=)([^&\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final String REDACTED_VALUE = "***REDACTED***";
    private static final Pattern JSON_TOKEN_FIELD_PATTERN = Pattern.compile(
            "(\"(?:access|refresh|control)_(?:token|url)\"\\s*:\\s*\")([^\"]+)(\")", Pattern.CASE_INSENSITIVE);
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final Endpoints ep;
    private final StampProvider stampProvider;
    private final HttpClient httpClient;
    private String accessToken;
    private String refreshToken;
    private String deviceId;
    private boolean deviceRegistered;

    public OAuthClient(Endpoints ep, String language, String country) {
        this(ep, language, country, true);
    }

    public OAuthClient(Endpoints ep, String language, String country,
            boolean autoUpdateStamp) {
        this(ep, language, country, autoUpdateStamp, new StampProvider());
    }

    public OAuthClient(Endpoints ep, String language, String country,
            boolean autoUpdateStamp, StampProvider stampProvider) {
        this(ep, language, country, autoUpdateStamp, stampProvider,
                HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public OAuthClient(Endpoints ep, String language, String country,
            boolean autoUpdateStamp, StampProvider stampProvider, HttpClient httpClient) {
        this.ep = Objects.requireNonNull(ep, "endpoints");
        this.stampProvider = Objects.requireNonNull(stampProvider, "stampProvider");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setInitialRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Exchanges an already obtained authorization code for tokens, without running
     * the full interactive login flow again. This is intended for the EU/Hyundai
     * two-step flow where the authorization code is captured by the web proxy.
     *
     * @param code        the authorization code received from the IDP
     * @param redirectUri the redirect URI that was used when requesting the code
     */
    public void requestTokenWithPreAuthorizedCode(String code, String redirectUri) throws Exception {
        if (ep.oauth.tokenUrl == null || ep.oauth.tokenUrl.isBlank()) {
            throw new IllegalStateException("OAuth token URL is not configured");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Authorization code must not be empty");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("Redirect URI must not be empty");
        }

        HttpClient client = httpClient;

        AuthorizationGrant grant = new AuthorizationGrant(code, redirectUri);

        rotateDevice();

        URI tokenUri = URI.create(ep.oauth.tokenUrl);
        HttpResponse<String> tokenResp = sendTokenRequest(client, tokenUri, grant);
        logger.trace("Token request to {} (pre-authorized) completed with HTTP {}", describeUri(tokenUri),
                tokenResp.statusCode());

        if (tokenResp.statusCode() == 404 && grant.tokenUrlOverride != null && !grant.tokenUrlOverride.isBlank()) {
            URI fallbackTokenUri = URI.create(grant.tokenUrlOverride);
            if (!fallbackTokenUri.equals(tokenUri)) {
                logger.info("Token request to {} returned HTTP 404, retrying with {}", describeUri(tokenUri),
                        describeUri(fallbackTokenUri));
                tokenResp = sendTokenRequest(client, fallbackTokenUri, grant);
                tokenUri = fallbackTokenUri;
            }
        }

        if (tokenResp.statusCode() >= 400) {
            throw logAndCreateException("Token request failed", tokenResp);
        }

        JsonObject tokenJson = JsonParser.parseString(tokenResp.body()).getAsJsonObject();
        TokenPair tokens = resolveTokens(tokenJson);
        if (tokens == null || isBlank(tokens.refreshToken)) {
            throw logAndCreateException("Token response did not contain tokens", tokenResp);
        }

        accessToken = tokens.accessToken;
        refreshToken = tokens.refreshToken;
    }

    /**
     * Rotates the device ID by generating a new UUID and re-registering it.
     * This follows the official APK behavior where the device identity is
     * periodically refreshed.
     */
    public synchronized void rotateDevice() throws Exception {
        logger.debug("Rotating device identifier...");
        deviceId = generateDeviceId();
        deviceRegistered = false;
        registerDevice();
    }

    public synchronized void registerDevice() throws Exception {
        if (deviceRegistered) {
            logger.debug("Skipping device registration because device {} is already registered", deviceId);
            return;
        }

        String currentDeviceId = ensureDeviceId();
        if (isBlank(currentDeviceId)) {
            logger.debug("Skipping device registration because no device ID is available");
            return;
        }

        if (ep.ccapi == null || isBlank(ep.ccapi.baseUrl)) {
            logger.debug("Skipping device registration because CCAPI base URL is not configured");
            return;
        }

        URI registerUri = URI.create(ep.ccapi.baseUrl).resolve("/api/v1/spa/notifications/register");

        JsonObject body = new JsonObject();
        body.addProperty("pushRegId", randomHex(64));
        body.addProperty("pushType", "APNS");
        body.addProperty("uuid", currentDeviceId);

        String requestBody = body.toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(registerUri)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("User-Agent", "okhttp/3.12.1");

        if (!isBlank(ep.oauth.clientId)) {
            builder.header("ccsp-service-id", ep.oauth.clientId);
        }

        String applicationId = getApplicationId();
        if (!isBlank(applicationId)) {
            builder.header("ccsp-application-id", applicationId);
        }

        String stamp = stampProvider.getStamp();
        if (!isBlank(stamp)) {
            builder.header("Stamp", stamp);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();

        logger.trace("Sending device registration request: {}", sanitizeRequest(request));
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logger.trace("Device registration response received: {}", sanitizeResponse(response));

        int status = response.statusCode();
        if (status >= 400) {
            if (status == 404) {
                logger.info("Device registration endpoint {} returned HTTP 404, continuing without registration",
                        describeUri(registerUri));
                return;
            }
            throw logAndCreateException("Device registration failed", response);
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject resMsg = null;
        if (json.has("resMsg") && json.get("resMsg").isJsonObject()) {
            resMsg = json.getAsJsonObject("resMsg");
        }

        String registeredId = optString(resMsg, "deviceId");
        if (isBlank(registeredId)) {
            registeredId = optString(resMsg, "deviceID");
        }
        if (isBlank(registeredId)) {
            registeredId = optString(json, "deviceId");
        }
        if (isBlank(registeredId)) {
            registeredId = optString(json, "deviceID");
        }

        if (isBlank(registeredId)) {
            logger.warn("Device registration response from {} did not contain a deviceId", describeUri(registerUri));
            return;
        }

        deviceId = registeredId;
        deviceRegistered = true;
        logger.debug("Device registered with identifier {}", registeredId);
    }

    private String randomHex(int length) {
        char[] chars = new char[length];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            int value = random.nextInt(HEX_DIGITS.length);
            chars[i] = HEX_DIGITS[value];
        }
        return new String(chars);
    }

    private String getApplicationId() {
        String applicationId = ep.oauth.applicationId;
        if (isBlank(applicationId)) {
            applicationId = ep.oauth.clientId;
        }
        return applicationId;
    }

    private String generateDeviceId() {
        return UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    }

    private synchronized String ensureDeviceId() {
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = generateDeviceId();
            deviceRegistered = false;
        }
        return deviceId;
    }

    private HttpRequest buildTokenRequest(URI tokenUri, AuthorizationGrant grant) {
        Objects.requireNonNull(grant, "grant");
        HttpRequest.Builder builder = HttpRequest.newBuilder(tokenUri);
        if (requiresFormTokenRequest(tokenUri)) {
            String[][] fields = new String[][] {
                    { "grant_type", "authorization_code" },
                    { "code", grant.code },
                    { "client_id", ep.oauth.clientId },
                    { "client_secret", ep.oauth.clientSecret },
                    { "redirect_uri", grant.redirectUri },
                    isBlank(grant.codeVerifier) ? null : new String[] { "code_verifier", grant.codeVerifier }
            };
            String body = buildFormBody(fields);
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            JsonObject tokenBody = new JsonObject();
            tokenBody.addProperty("grant_type", "authorization_code");
            tokenBody.addProperty("code", grant.code);
            tokenBody.addProperty("client_id", ep.oauth.clientId);
            tokenBody.addProperty("client_secret", ep.oauth.clientSecret);
            if (grant.redirectUri != null && !grant.redirectUri.isBlank()) {
                tokenBody.addProperty("redirect_uri", grant.redirectUri);
            }
            if (!isBlank(grant.codeVerifier)) {
                tokenBody.addProperty("code_verifier", grant.codeVerifier);
            }
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody.toString()));
        }
        return builder.build();
    }

    private void appendFormField(StringBuilder sb, String key, String value) {
        sb.append(urlEncode(key)).append('=').append(urlEncode(value == null ? "" : value)).append('&');
    }

    private String buildFormBody(String[][] fields) {
        StringBuilder sb = new StringBuilder();
        for (String[] field : fields) {
            if (field != null && field.length >= 2 && field[1] != null) {
                appendFormField(sb, field[0], field[1]);
            }
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private HttpRequest buildRefreshRequest(URI tokenUri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(tokenUri);
        if (requiresFormTokenRequest(tokenUri)) {
            String body = buildFormBody(new String[][] {
                    { "grant_type", "refresh_token" },
                    { "refresh_token", refreshToken },
                    { "client_id", ep.oauth.clientId },
                    { "client_secret", ep.oauth.clientSecret }
            });
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            JsonObject tokenBody = new JsonObject();
            tokenBody.addProperty("grant_type", "refresh_token");
            tokenBody.addProperty("refresh_token", refreshToken);
            tokenBody.addProperty("client_id", ep.oauth.clientId);
            tokenBody.addProperty("client_secret", ep.oauth.clientSecret);
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody.toString()));
        }
        return builder.build();
    }

    private boolean requiresFormTokenRequest(URI tokenUri) {
        if (tokenUri == null) {
            return false;
        }

        String host = tokenUri.getHost();
        if (requiresFormForHost(host)) {
            return true;
        }

        String authority = tokenUri.getAuthority();
        return requiresFormForHost(authority);
    }

    private boolean requiresFormForHost(String hostOrAuthority) {
        if (hostOrAuthority == null || hostOrAuthority.isBlank()) {
            return false;
        }
        String normalized = hostOrAuthority.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "idpconnect-", "-ccapi", "apigw.ccs");
    }

    private HttpResponse<String> sendTokenRequest(HttpClient client, URI tokenUri, AuthorizationGrant grant)
            throws IOException, InterruptedException {
        HttpRequest tokenReq = buildTokenRequest(tokenUri, grant);
        logger.trace("Sending token request: {}", sanitizeRequest(tokenReq));
        HttpResponse<String> response = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        logger.trace("Token response received: {}", sanitizeResponse(response));
        return response;
    }

    static class AuthorizationGrant {
        final String code;
        final String redirectUri;
        final String tokenUrlOverride;
        final String codeVerifier;

        AuthorizationGrant(String code, String redirectUri) {
            this(code, redirectUri, null, null);
        }

        AuthorizationGrant(String code, String redirectUri, String tokenUrlOverride) {
            this(code, redirectUri, tokenUrlOverride, null);
        }

        AuthorizationGrant(String code, String redirectUri, String tokenUrlOverride, String codeVerifier) {
            this.code = code;
            this.redirectUri = redirectUri;
            this.tokenUrlOverride = tokenUrlOverride;
            this.codeVerifier = codeVerifier;
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Refreshes the current access token using the stored refresh token.
     */
    public void refreshToken() throws Exception {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("No refresh token available");
        }

        if (ep.oauth.tokenUrl == null || ep.oauth.tokenUrl.isBlank()) {
            throw new IllegalStateException("OAuth token URL is not configured");
        }

        rotateDevice();

        HttpClient client = httpClient;

        URI tokenUri = URI.create(ep.oauth.tokenUrl);
        HttpRequest tokenReq = buildRefreshRequest(tokenUri);

        logger.trace("Sending token refresh request: {}", sanitizeRequest(tokenReq));
        HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        logger.info("Token refresh response received: {}", sanitizeResponse(tokenResp));
        if (tokenResp.statusCode() >= 400) {
            throw logAndCreateException("Token refresh failed", tokenResp);
        }

        JsonObject tokenJson = JsonParser.parseString(tokenResp.body()).getAsJsonObject();
        TokenPair tokens = resolveTokens(tokenJson);
        if (tokens == null) {
            throw logAndCreateException("Token refresh response did not contain tokens", tokenResp);
        }

        accessToken = tokens.accessToken;
        if (isBlank(tokens.refreshToken)) {
            logger.info("Token refresh response omitted refresh_token; retaining existing refresh token");
        } else {
            refreshToken = tokens.refreshToken;
        }
    }

    private TokenPair resolveTokens(JsonObject tokenJson) {
        if (tokenJson == null) {
            return null;
        }

        TokenPair connectorTokens = extractConnectorTokens(tokenJson);
        if (connectorTokens != null) {
            return connectorTokens;
        }

        String access = optString(tokenJson, "access_token");
        String refresh = optString(tokenJson, "refresh_token");
        if (isBlank(access)) {
            return null;
        }
        return new TokenPair(access, refresh);
    }

    private TokenPair extractConnectorTokens(JsonObject tokenJson) {
        if (tokenJson == null || !tokenJson.has("connector")) {
            return null;
        }

        JsonObject connectors;
        try {
            connectors = tokenJson.getAsJsonObject("connector");
        } catch (ClassCastException e) {
            return null;
        }

        if (connectors == null) {
            return null;
        }

        for (Map.Entry<String, JsonElement> entry : connectors.entrySet()) {
            JsonElement connectorElement = entry.getValue();
            if (connectorElement == null || !connectorElement.isJsonObject()) {
                continue;
            }
            JsonObject connectorTokens = connectorElement.getAsJsonObject();
            String connectorAccess = optString(connectorTokens, "access_token");
            String connectorRefresh = optString(connectorTokens, "refresh_token");
            if (!isBlank(connectorAccess)) {
                return new TokenPair(connectorAccess, connectorRefresh);
            }
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonPrimitive primitive;
        try {
            primitive = obj.getAsJsonPrimitive(key);
        } catch (ClassCastException e) {
            return null;
        }
        if (primitive == null || primitive.isJsonNull()) {
            return null;
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        if (primitive.isNumber() || primitive.isBoolean()) {
            return primitive.getAsString();
        }
        return null;
    }

    private static final class TokenPair {
        final String accessToken;
        final String refreshToken;

        TokenPair(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    private IOException logAndCreateException(String context, HttpResponse<String> response) {
        String sanitizedBody = sanitizeResponseBody(response.body());
        String message = String.format("%s (HTTP %d)", context, response.statusCode());
        if (!sanitizedBody.isEmpty()) {
            logger.warn("{} - response body: {}", message, sanitizedBody);
            return new IOException(message + ": " + sanitizedBody);
        }
        logger.warn("{} - empty response body", message);
        return new IOException(message);
    }

    private String sanitizeRequest(HttpRequest request) {
        if (request == null) {
            return "null request";
        }
        return request.method() + " " + describeUri(request.uri());
    }

    private String sanitizeResponse(HttpResponse<String> response) {
        if (response == null) {
            return "null response";
        }
        String sanitizedBody = sanitizeResponseBody(response.body());
        String target = describeUri(response.uri());
        if (sanitizedBody.isEmpty()) {
            return "HTTP " + response.statusCode() + " from " + target + " with empty body";
        }
        return "HTTP " + response.statusCode() + " from " + target + " with body: " + sanitizedBody;
    }

    private String sanitizeResponseBody(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        String sanitized = JSON_TOKEN_FIELD_PATTERN.matcher(trimmed).replaceAll("$1" + REDACTED_VALUE + "$3");

        if (sanitized.length() > 512) {
            sanitized = sanitized.substring(0, 512) + "...";
        }
        return sanitized;
    }

    private String describeUri(URI uri) {
        if (uri == null) {
            return "null";
        }
        String sanitized = sanitizeUriText(uri.toString());
        String region = detectRegion(uri);
        if (region == null) {
            return sanitized;
        }
        return sanitized + " [region=" + region + "]";
    }

    private String sanitizeUriText(String uriText) {
        if (uriText == null) {
            return "null";
        }
        String sanitized = uriText;
        // sanitized = AUTH_CODE_PATTERN.matcher(uriText).replaceAll("code=" +
        // REDACTED_VALUE);
        sanitized = QUERY_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1" + REDACTED_VALUE);
        return sanitized;
    }

    private String detectRegion(URI uri) {
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "cn-ccapi", ".cn")) {
            return "CN";
        }
        if (containsAny(normalized, "hyundaiusa.com", "owners.kia.com", "kiausa.com", "genesis.com")) {
            return "US";
        }
        if (containsAny(normalized, "eu-ccapi", "idpconnect-eu", ".eu")) {
            return "EU";
        }
        if (containsAny(normalized, "ca-ccapi", ".ca", "mybluelink.ca", "kiaconnect.ca")) {
            return "CA";
        }
        if (containsAny(normalized, "au-ccapi", ".com.au", ".au")) {
            return "AU";
        }
        return null;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}