package org.openhab.binding.hyundaibluelink.internal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleCommandResponse;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleLocation;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleStatus;
import org.openhab.binding.hyundaibluelink.internal.model.VehicleSummary;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;

@NonNullByDefault
@SuppressWarnings("null")
class BlueLinkApiTest {

    private static final String TEST_STAMP = "test-stamp";

    private BlueLinkApi api;
    private FakeStampProvider stampProvider;
    private FakeOAuthClient oauth;
    private Endpoints endpoints;
    private Method parseInstant;
    private Method formatBodyForLog;
    private Method sanitizeHeaders;

    @BeforeEach
    void setUp() throws Exception {
        endpoints = new Endpoints();
        endpoints.oauth.clientId = "client";
        endpoints.ccapi.baseUrl = "http://localhost";
        stampProvider = new FakeStampProvider(TEST_STAMP);
        oauth = new FakeOAuthClient(endpoints, stampProvider);
        oauth.setDeviceId("device-id");
        oauth.setAccessToken("access-token");
        api = new BlueLinkApi(endpoints, oauth, stampProvider, "1234");
        parseInstant = BlueLinkApi.class.getDeclaredMethod("parseInstant", com.google.gson.JsonElement.class);
        parseInstant.setAccessible(true);
        formatBodyForLog = BlueLinkApi.class.getDeclaredMethod("formatBodyForLog", String.class);
        formatBodyForLog.setAccessible(true);
        sanitizeHeaders = BlueLinkApi.class.getDeclaredMethod("sanitizeHeaders", HttpRequest.class);
        sanitizeHeaders.setAccessible(true);
    }

    @Test
    void parseInstantHandlesIso8601Strings() throws Exception {
        Instant instant = (Instant) parseInstant.invoke(api, new JsonPrimitive("2023-10-14T12:34:56Z"));

        assertNotNull(instant);
        assertEquals(Instant.parse("2023-10-14T12:34:56Z"), instant);
    }

    @Test
    void parseInstantHandlesEpochMillisStrings() throws Exception {
        Instant instant = (Instant) parseInstant.invoke(api, new JsonPrimitive("1700000000000"));

        assertNotNull(instant);
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), instant);
    }

    @Test
    void parseInstantHandlesBasicTimestampStrings() throws Exception {
        Instant instant = (Instant) parseInstant.invoke(api, new JsonPrimitive("20200718192140"));

        assertNotNull(instant);
        assertEquals(Instant.parse("2020-07-18T19:21:40Z"), instant);
    }

    @Test
    void listVehiclesAddsStampHeaderAndUsesAccessToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);

        AtomicReference<@Nullable String> stampHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> vehiclesAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> pinHeader = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString()
                    + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/vehicles", exchange -> {
            stampHeader.set(exchange.getRequestHeaders().getFirst("Stamp"));
            vehiclesAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            pinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            var vehicles = ctx.api.listVehicles();
            assertNotNull(vehicles);
            assertEquals(TEST_STAMP, stampHeader.get());
            assertEquals("Bearer access-token", vehiclesAuth.get());
            assertEquals(sha512Hex("1234"), pinHeader.get());
            assertEquals(1, ctx.stampProvider.getInvocationCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listVehiclesHandlesResMsgWrapper() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/vehicles", exchange -> {
            String response = "{\"resMsg\":{\"vehicles\":[{\"vehicleId\":\"11111111-2222-3333-4444-555555555555\",\"vin\":\"VIN-EU\",\"vehicleName\":\"Family Car\",\"modelName\":\"IONIQ 5\",\"year\":\"2023\"}]}}";
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            List<VehicleSummary> vehicles = ctx.api.listVehicles();

            assertEquals(1, vehicles.size());
            VehicleSummary summary = vehicles.get(0);
            assertEquals("11111111-2222-3333-4444-555555555555", summary.vehicleId);
            assertEquals("VIN-EU", summary.vin);
            assertEquals("Family Car", summary.label);
            assertEquals("IONIQ 5", summary.model);
            assertEquals("2023", summary.modelYear);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listVehiclesRetriesWithSpaV1BaseOnForbidden() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger spaV2Calls = new AtomicInteger();
        AtomicInteger spaV1Calls = new AtomicInteger();
        server.createContext("/api/v2/spa/vehicles", exchange -> {
            spaV2Calls.incrementAndGet();
            byte[] payload = "{\"error\":\"disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/vehicles", exchange -> {
            spaV1Calls.incrementAndGet();
            String response = "[{\"vehicleId\":\"11111111-2222-3333-4444-555555555555\",\"vin\":\"VIN\"}]";
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints localEndpoints = new Endpoints();
            localEndpoints.oauth.clientId = "client";
            localEndpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient localOauth = new FakeOAuthClient(localEndpoints, provider);
            localOauth.setDeviceId("device-id");
            localOauth.setAccessToken("access-token");
            BlueLinkApi localApi = new BlueLinkApi(localEndpoints, localOauth, provider, "1234");

            List<VehicleSummary> vehicles = localApi.listVehicles();

            assertEquals(1, vehicles.size());
            assertEquals("VIN", vehicles.get(0).vin);
            assertEquals(1, spaV2Calls.get());
            assertEquals(1, spaV1Calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void securityCommandsRequireConfiguredPin() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "99999999-1111-2222-3333-444444444444";
        AtomicInteger pinCalls = new AtomicInteger();
        server.createContext("/api/v1/user/pin", exchange -> {
            pinCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"batteryLevel\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort(), null);
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();

            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-NO-PIN", false);
            assertEquals(42.0, status.batteryLevel);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ctx.api.lock(vehicleId, "VIN-NO-PIN", false));
            assertTrue(ex.getMessage().contains("configured PIN"));
            assertEquals(0, pinCalls.get(), "Control token exchange should not be attempted without a PIN");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusUnwrapsNestedPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "99999999-8888-7777-6666-555555555555";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String responseBody = """
                {"retCode":"0","resMsg":{"payload":{"vehicleStatus":{"batteryLevel":87,"rangeKm":"320.5","evModeRange":{"value":312.0,"unit":1},"gasModeRange":{"value":645.7,"unit":0},"fuelLevel":73,"odometerKm":"12345","doorsLocked":true,"charging":false,"doorStatus":{"frontLeft":{"open":false},"frontRight":{"open":true}},"windowStatus":{"rearLeft":{"open":false}},"climateOn":true,"batteryWarning":false,"lowFuelLight":false,"lastUpdated":"2024-01-01T01:02:03Z"}}}}
                """;
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-EU", false);

            assertNotNull(status);
            assertEquals("VIN-EU", status.vin);
            assertEquals(87.0, status.batteryLevel);
            assertEquals(320.5, status.range);
            assertEquals(DistanceUnit.KILOMETERS, status.rangeUnit);
            assertEquals(312.0, status.evModeRange);
            assertEquals(DistanceUnit.KILOMETERS, status.evModeRangeUnit);
            assertEquals(645.7, status.gasModeRange);
            assertEquals(DistanceUnit.MILES, status.gasModeRangeUnit);
            assertEquals(73.0, status.fuelLevel);
            assertEquals(12345.0, status.odometer);
            assertEquals(Boolean.TRUE, status.doorsLocked);
            assertEquals(Boolean.FALSE, status.charging);
            assertEquals(Boolean.TRUE, status.climateOn);
            assertEquals(Boolean.FALSE, status.batteryWarning);
            assertEquals(Boolean.FALSE, status.lowFuelLight);
            assertEquals("frontLeft=CLOSED, frontRight=OPEN", status.doorStatusSummary);
            assertEquals("rearLeft=CLOSED", status.windowStatusSummary);
            assertNotNull(status.lastUpdated);
            assertEquals(Instant.parse("2024-01-01T01:02:03Z"), status.lastUpdated);

        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusReadsCapitalizedOdometer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "88888888-7777-6666-5555-444444444444";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String responseBody = "{\"resMsg\":{\"payload\":{\"vehicleStatus\":{\"Odometer\":54413.3}}}}";
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-CAPITAL", false);

            assertNotNull(status);
            assertEquals(54413.3, status.odometer);
            assertEquals(DistanceUnit.KILOMETERS, status.odometerUnit);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusPrefersCcs2CarStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "77777777-6666-5555-4444-333333333333";
        AtomicInteger ccs2Requests = new AtomicInteger();
        AtomicInteger legacyRequests = new AtomicInteger();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String ccs2Body = "{\"resMsg\":{\"state\":{\"Vehicle\":{\"Drivetrain\":{\"Odometer\":54455.7},\"Cabin\":{\"Door\":{\"Row1\":{\"Driver\":{\"Lock\":true},\"Passenger\":{\"Lock\":true}},\"Row2\":{\"Left\":{\"Lock\":true},\"Right\":{\"Lock\":true}}}},\"Green\":{\"ChargingInformation\":{\"Charging\":{\"RemainTime\":0}}},\"lastUpdated\":\"2025-10-08T15:06:16Z\"}}}}";
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/carstatus/latest", exchange -> {
            ccs2Requests.incrementAndGet();
            byte[] payload = ccs2Body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            legacyRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-CCS2", true);

            assertNotNull(status);
            assertEquals(54455.7, status.odometer);
            assertEquals(Boolean.TRUE, status.doorsLocked);
            assertEquals(Boolean.FALSE, status.charging);
            assertEquals(0, legacyRequests.get());
            assertEquals(1, ccs2Requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusExtractsEvStatusFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        Path payloadPath = Path.of("src", "test", "resources", "org", "openhab", "binding", "hyundaibluelink",
                "internal", "api", "eu-vehicle-status.json");
        String responseBody = Files.readString(payloadPath);
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-EU-FALLBACK", false);

            assertNotNull(status);
            assertEquals(54.0, status.batteryLevel);
            assertEquals(235.0, status.range);
            assertEquals(DistanceUnit.KILOMETERS, status.rangeUnit);
            assertEquals(235.0, status.evModeRange);
            assertEquals(DistanceUnit.KILOMETERS, status.evModeRangeUnit);
            assertEquals(12345.0, status.odometer);
            assertEquals(91.0, status.auxiliaryBatteryLevel);
            assertEquals(Boolean.TRUE, status.doorsLocked);
            assertEquals(Boolean.FALSE, status.charging);
            assertEquals(Boolean.TRUE, status.climateOn);
            assertEquals(Instant.parse("2020-07-18T19:21:40Z"), status.lastUpdated);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusDetectsRangeUnitFromDrvDistance() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "cccccccc-dddd-eeee-ffff-000000000000";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        String responseBody = """
                {"resMsg":{"payload":{"vehicleStatus":{"batteryLevel":80,
                "evStatus":{"drvDistance":[{"rangeByFuel":{"totalAvailableRange":{"value":180.5,"unit":0}}}]}}}}}
                """;
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-RANGE-MI", false);

            assertNotNull(status);
            assertEquals(180.5, status.range);
            assertEquals(DistanceUnit.MILES, status.rangeUnit);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusExtractsVehicleLocationFromPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString()
                    + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String responseBody = "{\"vehicleStatus\":{\"vehicleLocation\":{\"coord\":{\"lat\":48.099894,\"lon\":16.311561}}}}";
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-LOCATION", false);

            assertNotNull(status);
            assertEquals(48.099894, status.latitude);
            assertEquals(16.311561, status.longitude);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusPost400DoesNotDisablePost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "11111111-aaaa-bbbb-cccc-222222222222";
        AtomicInteger callCounter = new AtomicInteger();
        AtomicReference<@Nullable String> firstMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> secondMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> thirdMethod = new AtomicReference<>();
        AtomicReference<JsonObject> firstPayload = new AtomicReference<>();
        AtomicReference<JsonObject> thirdPayload = new AtomicReference<>();
        AtomicInteger secondBodyLength = new AtomicInteger();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String okResponse = "{\"resMsg\":{\"vehicleStatus\":{}}}";
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            int call = callCounter.incrementAndGet();
            String method = exchange.getRequestMethod();
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            if (call == 1) {
                firstMethod.set(method);
                if (bodyBytes.length > 0) {
                    firstPayload.set(JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8))
                            .getAsJsonObject());
                }
                byte[] payload = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, payload.length);
                exchange.getResponseBody().write(payload);
            } else if (call == 2) {
                secondMethod.set(method);
                secondBodyLength.set(bodyBytes.length);
                byte[] payload = okResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else if (call == 3) {
                thirdMethod.set(method);
                if (bodyBytes.length > 0) {
                    thirdPayload.set(JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8))
                            .getAsJsonObject());
                }
                byte[] payload = okResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            ctx.api.getVehicleStatus(vehicleId, "VIN-TEST", false);

            ctx.oauth.setDeviceId("device-id-2");
            ctx.api.getVehicleStatus(vehicleId, "VIN-TEST", false);

            assertEquals(3, callCounter.get());
            assertEquals("POST", firstMethod.get());
            assertEquals("GET", secondMethod.get());
            assertEquals("POST", thirdMethod.get());
            assertNotNull(firstPayload.get());
            assertEquals("device-id", firstPayload.get().get("deviceId").getAsString());
            assertNotNull(thirdPayload.get());
            assertEquals("device-id-2", thirdPayload.get().get("deviceId").getAsString());
            assertEquals(0, secondBodyLength.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusPost404DisablesPostAfterSuccessfulGet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "33333333-bbbb-cccc-dddd-444444444444";
        AtomicInteger callCounter = new AtomicInteger();
        AtomicReference<@Nullable String> firstMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> secondMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> thirdMethod = new AtomicReference<>();
        AtomicReference<JsonObject> firstPayload = new AtomicReference<>();
        AtomicInteger thirdBodyLength = new AtomicInteger();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String okResponse = "{\"resMsg\":{\"vehicleStatus\":{}}}";
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            int call = callCounter.incrementAndGet();
            String method = exchange.getRequestMethod();
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            if (call == 1) {
                firstMethod.set(method);
                if (bodyBytes.length > 0) {
                    firstPayload.set(JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8))
                            .getAsJsonObject());
                }
                byte[] payload = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, payload.length);
                exchange.getResponseBody().write(payload);
            } else if (call == 2) {
                secondMethod.set(method);
                byte[] payload = okResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else if (call == 3) {
                thirdMethod.set(method);
                thirdBodyLength.set(bodyBytes.length);
                byte[] payload = okResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            ctx.api.getVehicleStatus(vehicleId, "VIN-TEST", false);

            ctx.oauth.setDeviceId("device-id-2");
            ctx.api.getVehicleStatus(vehicleId, "VIN-TEST", false);

            assertEquals(3, callCounter.get());
            assertEquals("POST", firstMethod.get());
            assertEquals("GET", secondMethod.get());
            assertEquals("GET", thirdMethod.get());
            assertNotNull(firstPayload.get());
            assertEquals("device-id", firstPayload.get().get("deviceId").getAsString());
            assertEquals(0, thirdBodyLength.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusSkipsPostAfter405Response() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        String responseBody = "{\"batteryLevel\":64}";
        AtomicInteger postCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("POST".equals(exchange.getRequestMethod())) {
                postCalls.incrementAndGet();
                exchange.sendResponseHeaders(405, 0);
            } else {
                getCalls.incrementAndGet();
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            VehicleStatus first = ctx.api.getVehicleStatus(vehicleId, "VIN", false);
            assertEquals(1, postCalls.get(), "Initial call should attempt POST");
            assertEquals(1, getCalls.get(), "Initial fallback should perform GET");
            assertEquals(64.0, first.batteryLevel);

            VehicleStatus second = ctx.api.getVehicleStatus(vehicleId, "VIN", false);
            assertEquals(1, postCalls.get(), "Subsequent calls should skip POST");
            assertEquals(2, getCalls.get(), "Subsequent calls should use GET directly");
            assertEquals(64.0, second.batteryLevel);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusFallsBackToStatusLatestWhenAccessDisallowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "dddddddd-eeee-ffff-0000-111111111111";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger postCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("POST".equals(exchange.getRequestMethod())) {
                postCalls.incrementAndGet();
            } else {
                getCalls.incrementAndGet();
            }
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger fallbackCalls = new AtomicInteger();
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            fallbackCalls.incrementAndGet();
            byte[] payload = "{\"batteryLevel\":54}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN", false);

            assertEquals(1, postCalls.get(), "Expected a single POST attempt");
            assertEquals(1, getCalls.get(), "Expected SPA v2 GET then SPA v1 GET");
            assertEquals(1, fallbackCalls.get(), "Expected a single /status/latest fallback call");
            assertEquals(54.0, status.batteryLevel);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusSpaV2FallbackPopulatesStatusFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "aaaa1111-bbbb-cccc-dddd-eeeeeeeeeeee";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger legacyCalls = new AtomicInteger();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/status", exchange -> {
            legacyCalls.incrementAndGet();
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger spaV2FallbackCalls = new AtomicInteger();
        String spaV2FallbackBody = """
                {"resMsg":{"vehicleStatusInfo":{"vehicleStatus":{"doorsLocked":false,"charging":true,
                "connectorFastened":true,"climateOn":false,"evStatus":{"soc":83,"chargingState":2,
                "remainTime":{"total":37}}},"odometer":{"value":51234.5,"unit":1}}}}
                """;
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            spaV2FallbackCalls.incrementAndGet();
            byte[] payload = spaV2FallbackBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");

            VehicleStatus status = api.getVehicleStatus(vehicleId, "VIN-SPA-V2", false);

            assertEquals(1, legacyCalls.get(), "Expected a single SPA v2 /status attempt");
            assertEquals(1, spaV2FallbackCalls.get(), "Expected a single SPA v2 status/latest fallback call");
            assertEquals(Boolean.TRUE, status.charging);
            assertEquals(2, status.chargingState);
            assertEquals(Integer.valueOf(37), status.remainingChargeTimeMinutes);
            assertEquals(Boolean.TRUE, status.connectorFastened);
            assertEquals(Boolean.FALSE, status.climateOn);
            assertEquals(83.0, status.batteryLevel);
            assertEquals(51234.5, status.odometer);
            assertEquals(DistanceUnit.KILOMETERS, status.odometerUnit);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusSpaV1FallbackPopulatesStatusFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "ffff2222-3333-4444-5555-666666666666";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger legacyCalls = new AtomicInteger();
        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            legacyCalls.incrementAndGet();
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger spaV1FallbackCalls = new AtomicInteger();
        String spaV1FallbackBody = """
                {"resMsg":{"payload":{"vehicleStatus":{"doorsLocked":true,"charging":false,"chargingState":1,
                "connectorFastening":{"state":1},"airCtrlOn":true,"evStatus":{"soc":67,"chargerStatus":1,
                "chargingRemainingTime":{"value":22}}},"odometer":{"value":44567.8,"unit":0}}}}
                """;
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            spaV1FallbackCalls.incrementAndGet();
            byte[] payload = spaV1FallbackBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN-SPA-V1", false);

            assertEquals(2, legacyCalls.get(), "Expected POST + GET attempts before SPA fallback");
            assertEquals(1, spaV1FallbackCalls.get(), "Expected a single SPA v1 status/latest fallback call");
            assertEquals(Boolean.FALSE, status.charging);
            assertEquals(1, status.chargingState);
            assertEquals(Integer.valueOf(22), status.remainingChargeTimeMinutes);
            assertEquals(Boolean.TRUE, status.connectorFastened);
            assertEquals(Boolean.TRUE, status.climateOn);
            assertEquals(67.0, status.batteryLevel);
            assertEquals(Boolean.TRUE, status.doorsLocked);
            assertEquals(44567.8, status.odometer);
            assertEquals(DistanceUnit.MILES, status.odometerUnit);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusLatestRawFallsBackBetweenSpaBases() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "ffffffff-0000-1111-2222-333333333333";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger spaV2Calls = new AtomicInteger();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            spaV2Calls.incrementAndGet();
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger spaV1Calls = new AtomicInteger();
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            spaV1Calls.incrementAndGet();
            byte[] payload = "{\"batteryLevel\":58}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");

            JsonResponse response = api.getVehicleStatusLatestRaw(vehicleId, "VIN-RAW-FALLBACK", true, false);

            assertTrue(spaV2Calls.get() >= 1, "Expected at least one SPA v2 status/latest request");
            assertEquals(1, spaV1Calls.get(), "Expected SPA v1 fallback request");
            assertNotNull(response);
            assertTrue(response.isSuccessful(), "Fallback response should indicate success");
            assertEquals(200, response.getStatusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusParsesOdometerFromVehicleStatusInfoFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "eeeeeeee-ffff-0000-1111-222222222222";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        AtomicInteger fallbackCalls = new AtomicInteger();
        String fallbackBody = "{\"resMsg\":{\"vehicleStatusInfo\":{\"vehicleStatus\":{\"doorOpen\":{\"frontLeft\":0}},\"odometer\":{\"value\":54507.1,\"unit\":1}}}}";
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            fallbackCalls.incrementAndGet();
            byte[] payload = fallbackBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());

            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN", false);

            assertEquals(1, fallbackCalls.get(), "Expected a single /status/latest fallback call");
            assertEquals(54507.1, status.odometer);
            assertEquals(DistanceUnit.KILOMETERS, status.odometerUnit);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleStatusUsesGetWhenSpaV2BaseUrlConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "cccccccc-dddd-eeee-ffff-000000000000";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        String responseBody = "{\"batteryLevel\":70}";
        AtomicInteger postCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("POST".equals(exchange.getRequestMethod())) {
                postCalls.incrementAndGet();
                exchange.sendResponseHeaders(405, 0);
            } else {
                getCalls.incrementAndGet();
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints localEndpoints = new Endpoints();
            localEndpoints.oauth.clientId = "client";
            localEndpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient localOauth = new FakeOAuthClient(localEndpoints, provider);
            localOauth.setDeviceId("device-id");
            localOauth.setAccessToken("access-token");
            BlueLinkApi apiWithSpaBase = new BlueLinkApi(localEndpoints, localOauth, provider, "1234");

            VehicleStatus status = apiWithSpaBase.getVehicleStatus(vehicleId, "VIN", false);
            assertEquals(0, postCalls.get(), "POST should not be attempted for SPA v2 base URLs");
            assertEquals(1, getCalls.get(), "GET should be used immediately");
            assertEquals(70.0, status.batteryLevel);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleLocationUnwrapsNestedPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "55555555-6666-7777-8888-999999999999";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String locationResponse = "{\"resMsg\":{\"gpsDetail\":{\"coord\":{\"lat\":\"52.5200\",\"lon\":\"13.4050\"}}}}";
        AtomicReference<@Nullable String> ccs2Query = new AtomicReference<>();
        AtomicReference<@Nullable String> ccs2PinHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> ccs2ControlTokenHeader = new AtomicReference<>();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/location/latest", exchange -> {
            ccs2Query.set(exchange.getRequestURI().getRawQuery());
            ccs2PinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            ccs2ControlTokenHeader.set(exchange.getRequestHeaders().getFirst("ccsp-control-token"));
            byte[] payload = locationResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            VehicleLocation location = ctx.api.getVehicleLocation(vehicleId, "VIN-EU", false);

            assertNotNull(location);
            assertEquals(52.5200, location.latitude, 0.0001);
            assertEquals(13.4050, location.longitude, 0.0001);
            assertNull(ccs2Query.get(), "Location request must not include query parameters such as the PIN");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleLocationQueriesLocationApisInOrderAndFallsBackToStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString()
                    + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        AtomicInteger callOrder = new AtomicInteger();
        AtomicInteger ccs2LocationOrder = new AtomicInteger();
        AtomicInteger spaV2LocationOrder = new AtomicInteger();
        AtomicInteger spaV1LocationOrder = new AtomicInteger();
        AtomicInteger statusOrder = new AtomicInteger();
        AtomicReference<@Nullable String> ccs2LocationPin = new AtomicReference<>();
        AtomicReference<@Nullable String> ccs2LocationControlToken = new AtomicReference<>();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/location/latest", exchange -> {
            ccs2LocationOrder.set(callOrder.incrementAndGet());
            ccs2LocationPin.set(exchange.getRequestHeaders().getFirst("pin"));
            ccs2LocationControlToken.set(exchange.getRequestHeaders().getFirst("ccsp-control-token"));
            byte[] payload = "{\"error\":\"notfound\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/location", exchange -> {
            spaV2LocationOrder.set(callOrder.incrementAndGet());
            byte[] payload = "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/location", exchange -> {
            spaV1LocationOrder.set(callOrder.incrementAndGet());
            byte[] payload = "{\"error\":\"notfound\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/carstatus/latest", exchange -> {
            statusOrder.set(callOrder.incrementAndGet());
            String body = "{\"vehicleLocation\":{\"coord\":{\"lat\":48.2082,\"lon\":16.3738}}}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");

            VehicleLocation location = api.getVehicleLocation(vehicleId, "VIN-STATUS-FALLBACK", false);

            assertNotNull(location);
            assertEquals(48.2082, location.latitude, 0.000001);
            assertEquals(16.3738, location.longitude, 0.000001);
            assertEquals(1, spaV2LocationOrder.get());
            assertEquals(0, spaV1LocationOrder.get());
            assertEquals(2, ccs2LocationOrder.get());
            assertEquals(3, statusOrder.get());
            assertNull(ccs2LocationPin.get(), "CCS2 GET requests must not include the PIN header");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getVehicleLocationFallsBackToLegacyStatusWhenCcs2Unavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString()
                    + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        AtomicReference<@Nullable String> ccs2Query = new AtomicReference<>();
        AtomicReference<@Nullable String> ccs2PinHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> ccs2ControlTokenHeader = new AtomicReference<>();
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/carstatus/latest", exchange -> {
            ccs2Query.set(exchange.getRequestURI().getRawQuery());
            ccs2PinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            ccs2ControlTokenHeader.set(exchange.getRequestHeaders().getFirst("ccsp-control-token"));
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        String statusBody = "{\"vehicleLocation\":{\"coord\":{\"lat\":48.099894,\"lon\":16.311561,\"alt\":0,\"type\":0}}}";
        AtomicReference<@Nullable String> legacyQuery = new AtomicReference<>();
        AtomicReference<@Nullable String> legacyPinHeader = new AtomicReference<>();
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/status/latest", exchange -> {
            legacyQuery.set(exchange.getRequestURI().getRawQuery());
            legacyPinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            byte[] payload = statusBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");

            VehicleLocation location = api.getVehicleLocation(vehicleId, "VIN-STATUS-FALLBACK", false);

            assertNotNull(location);
            assertEquals(48.099894, location.latitude, 0.000001);
            assertEquals(16.311561, location.longitude, 0.000001);
            assertEquals(null, ccs2Query.get());
            String pinHeaderVal = legacyPinHeader.get();
            assertEquals(sha512Hex("1234"), pinHeaderVal);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void controlTokenIsAcquiredAndUsedForVehicleRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "22222222-3333-4444-5555-666666666666";
        AtomicReference<@Nullable String> pinAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> firstStatusAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> secondStatusAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> doorAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> doorContentType = new AtomicReference<>();
        AtomicReference<@Nullable String> doorServiceId = new AtomicReference<>();
        AtomicReference<@Nullable String> doorControlTokenHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> doorPinHeader = new AtomicReference<>();
        AtomicReference<JsonObject> doorPayloadJson = new AtomicReference<>();
        AtomicReference<@Nullable String> listAuth = new AtomicReference<>();
        AtomicReference<@Nullable String> listPinHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> firstStatusMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> secondStatusMethod = new AtomicReference<>();
        AtomicReference<@Nullable String> firstStatusBody = new AtomicReference<>();
        AtomicReference<@Nullable String> secondStatusBody = new AtomicReference<>();
        AtomicReference<@Nullable String> controlTokenPinHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> firstStatusPinHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> secondStatusPinHeader = new AtomicReference<>();
        AtomicInteger pinCalls = new AtomicInteger();
        AtomicInteger statusCalls = new AtomicInteger();

        server.createContext("/api/v1/user/pin", exchange -> {
            pinCalls.incrementAndGet();
            pinAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            controlTokenPinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            int call = statusCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String pinHeader = exchange.getRequestHeaders().getFirst("pin");
            if (call == 1) {
                firstStatusMethod.set(exchange.getRequestMethod());
                firstStatusAuth.set(authHeader);
                firstStatusBody.set(body);
                firstStatusPinHeader.set(pinHeader);
                byte[] payload = "{\"resCode\":4002}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, payload.length);
                exchange.getResponseBody().write(payload);
            } else {
                secondStatusMethod.set(exchange.getRequestMethod());
                secondStatusAuth.set(authHeader);
                secondStatusBody.set(body);
                secondStatusPinHeader.set(pinHeader);
                byte[] payload = "{\"batteryLevel\":50}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            doorAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            doorContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            doorServiceId.set(exchange.getRequestHeaders().getFirst("ccsp-service-id"));
            doorControlTokenHeader.set(exchange.getRequestHeaders().getFirst("AuthorizationCCSP"));
            doorPinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject payloadJson = JsonParser.parseString(body).getAsJsonObject();
            doorPayloadJson.set(payloadJson);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        server.createContext("/vehicles", exchange -> {
            listAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            listPinHeader.set(exchange.getRequestHeaders().getFirst("pin"));
            byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            assertEquals("Bearer refreshed-access-token", pinAuth.get());
            assertEquals(sha512Hex("1234"), controlTokenPinHeader.get());
            assertEquals(1, pinCalls.get());

            String expectedId = ctx.oauth.getDeviceId();
            VehicleStatus status = ctx.api.getVehicleStatus(vehicleId, "VIN", false);
            assertEquals(2, statusCalls.get(), "Expected POST followed by GET fallback");
            assertEquals("Bearer refreshed-access-token", firstStatusAuth.get());
            assertEquals("Bearer refreshed-access-token", secondStatusAuth.get());
            assertEquals("POST", firstStatusMethod.get());
            assertEquals("GET", secondStatusMethod.get());
            assertEquals("{\"deviceId\":\"" + expectedId + "\"}", firstStatusBody.get());
            assertEquals("", secondStatusBody.get());
            assertEquals(sha512Hex("1234"), firstStatusPinHeader.get());
            assertEquals(sha512Hex("1234"), secondStatusPinHeader.get());
            assertEquals(50.0, status.batteryLevel);

            ctx.api.lock(vehicleId, "VIN", false);
            assertEquals("Bearer control-token", doorAuth.get());
            assertEquals("application/json", doorContentType.get());
            assertEquals("client", doorServiceId.get());
            assertEquals("Bearer control-token", doorControlTokenHeader.get());
            assertNull(doorPinHeader.get());
            JsonObject doorPayload = doorPayloadJson.get();
            assertNotNull(doorPayload, "Door payload should be captured");
            assertEquals("close", doorPayload.get("command").getAsString());
            assertFalse(doorPayload.has("pin"));
            assertFalse(doorPayload.has("controlToken"));
            assertFalse(doorPayload.has("deviceId"));

            ctx.api.listVehicles();
            assertEquals("Bearer refreshed-access-token", listAuth.get());
            assertEquals(sha512Hex("1234"), listPinHeader.get());
            assertEquals(1, pinCalls.get(), "control token should be reused");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void commandsTriggerDeviceIdRotationAfterPolling() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "55555555-6666-7777-8888-999999999999";
        List<String> commandHeaderIds = new ArrayList<>();
        List<String> notificationHeaderIds = new ArrayList<>();

        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(300);
            String payload = "{\"controlToken\":\"token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            commandHeaderIds.add(exchange.getRequestHeaders().getFirst("ccsp-device-id"));
            byte[] response = "{\"msgId\":\"door-lock\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            notificationHeaderIds.add(exchange.getRequestHeaders().getFirst("ccsp-device-id"));
            String responseBody = "{\"resMsg\":[{\"recordId\":\"door-lock\",\"result\":\"success\"}]}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.createContext("/vehicles/" + vehicleId + "/status", exchange -> {
            byte[] response = "{\"batteryLevel\":50}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.api.login(); // Rotates once from "device-id" to UUID1

            String idAfterLogin = ctx.oauth.getDeviceId();
            assertNotEquals("device-id", idAfterLogin);

            // First command: should use idAfterLogin
            ctx.api.lock(vehicleId, "VIN", false);
            assertEquals(1, commandHeaderIds.size());
            assertEquals(idAfterLogin, commandHeaderIds.get(0));

            // Polling: should use idAfterLogin
            boolean ready = ctx.api.pollVehicleCommandResult(vehicleId, "VIN", "door-lock");
            assertTrue(ready);
            assertEquals(1, notificationHeaderIds.size());
            assertEquals(idAfterLogin, notificationHeaderIds.get(0));

            // After successful poll, it should have rotated
            String idAfterFirstPoll = ctx.oauth.getDeviceId();
            assertNotEquals(idAfterLogin, idAfterFirstPoll);

            // Second command: should use idAfterFirstPoll
            ctx.api.lock(vehicleId, "VIN", false);
            assertEquals(2, commandHeaderIds.size());
            assertEquals(idAfterFirstPoll, commandHeaderIds.get(1));

        } finally {
            server.stop(0);
        }
    }

    @Test
    void formatBodyForLogRedactsSensitiveFields() throws Exception {
        String sanitized = (String) formatBodyForLog.invoke(api,
                "{\"pin\":\"1234\",\"deviceId\":\"device-id\",\"controlToken\":\"token\"}");

        JsonObject obj = JsonParser.parseString(sanitized).getAsJsonObject();
        assertEquals("***REDACTED***", obj.get("pin").getAsString());
        assertEquals("***REDACTED***", obj.get("controlToken").getAsString());
        assertEquals("device-id", obj.get("deviceId").getAsString());
    }

    @Test
    void sanitizeHeadersRedactsControlTokenHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/test"))
                .GET()
                .setHeader("ccsp-control-token", "real-control-token")
                .setHeader("Stamp", "some-stamp")
                .setHeader("pin", "hashed-pin")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, List<String>> sanitized = (Map<String, List<String>>) sanitizeHeaders.invoke(api, request);

        assertEquals(List.of("Token [****REDACTED****]"), sanitized.get("ccsp-control-token"));
        assertEquals(List.of("***REDACTED***"), sanitized.get("Stamp"));
        assertEquals(List.of("***REDACTED***"), sanitized.get("pin"));
    }

    @Test
    void vehicleCommandFailureIsReported() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "33333333-4444-5555-6666-777777777777";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"error\":\"pin invalid\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/control/door", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"error\":\"pin invalid\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            IOException ex = assertThrows(IOException.class, () -> ctx.api.lock(vehicleId, "VIN", false));
            System.err.println("EX MESSAGE WAS: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("failed"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vehicleCommandResponseIncludesMessageId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "44444444-5555-6666-7777-888888888888";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"msgId\":\"job-123\",\"resCode\":\"0000\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            VehicleCommandResponse response = ctx.api.lock(vehicleId, "VIN", false);

            assertNotNull(response);
            assertEquals("job-123", response.getMessageId());
            assertEquals("ccs2/remote/door", response.getControlSegment());
            assertEquals("lock", response.getAction());
            assertNotNull(response.getResponseBody());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollVehicleCommandResultUsesNotificationsEndpointWithGet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "55555555-6666-7777-8888-999999999999";
        AtomicInteger pollCalls = new AtomicInteger();
        AtomicReference<@Nullable String> methodRef = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            pollCalls.incrementAndGet();
            methodRef.set(exchange.getRequestMethod());
            byte[] payload = "{\"resMsg\":[{\"messageId\":\"job-42\",\"result\":\"success\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            boolean ready = ctx.api.pollVehicleCommandResult(vehicleId, "VIN", "job-42");

            assertTrue(ready);
            assertEquals(1, pollCalls.get());
            assertEquals("GET", methodRef.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollVehicleCommandResultReturnsFalseWhenNotFoundOrPending() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "77777777-8888-9999-aaaa-bbbbbbbbbbbb";
        String jobId = "job-200";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            byte[] payload = "{\"resMsg\":[{\"messageId\":\"other-job\",\"result\":\"success\"},{\"messageId\":\"job-200\",\"result\":\"pending\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            boolean ready = ctx.api.pollVehicleCommandResult(vehicleId, "VIN", jobId);

            assertFalse(ready);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollVehicleCommandResultThrowsExceptionOnExplicitFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String jobId = "job-201";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            byte[] payload = "{\"resMsg\":[{\"messageId\":\"job-201\",\"result\":\"fail\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            IOException exception = assertThrows(
                    IOException.class,
                    () -> ctx.api.pollVehicleCommandResult(vehicleId, "VIN", jobId));

            assertTrue(exception.getMessage().contains("fail"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollVehicleCommandResultAssumesSuccessOnHttpError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "ffffffff-1111-2222-3333-444444444444";
        String jobId = "job-202";
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            byte[] payload = "{\"error\":\"Not authorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.oauth.setRefreshToken(null);

            ctx.api.login();
            boolean ready = ctx.api.pollVehicleCommandResult(vehicleId, "VIN", jobId);

            // New logic assumes success to prevent breaking item state on WAF block
            assertTrue(ready);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doorCommandsUseControlEndpointAndToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "44444444-5555-6666-7777-888888888888";
        AtomicInteger callCounter = new AtomicInteger();
        AtomicReference<@Nullable String> firstPath = new AtomicReference<>();
        AtomicReference<@Nullable String> secondPath = new AtomicReference<>();
        AtomicReference<JsonObject> firstPayload = new AtomicReference<>();
        AtomicReference<JsonObject> secondPayload = new AtomicReference<>();
        AtomicReference<@Nullable String> firstControlTokenHeader = new AtomicReference<>();
        AtomicReference<@Nullable String> secondControlTokenHeader = new AtomicReference<>();

        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            int call = callCounter.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String controlTokenHeader = exchange.getRequestHeaders().getFirst("AuthorizationCCSP");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (call == 1) {
                firstPath.set(path);
                firstPayload.set(json);
                firstControlTokenHeader.set(controlTokenHeader);
            } else {
                secondPath.set(path);
                secondPayload.set(json);
                secondControlTokenHeader.set(controlTokenHeader);
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.api.login();

            ctx.api.lock(vehicleId, "VIN", false);
            ctx.api.unlock(vehicleId, "VIN", false);

            assertEquals("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", firstPath.get());
            assertEquals("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", secondPath.get());
            assertEquals("close", firstPayload.get().get("command").getAsString());
            assertEquals("open", secondPayload.get().get("command").getAsString());
            assertFalse(firstPayload.get().has("pin"));
            assertFalse(secondPayload.get().has("pin"));
            assertEquals("Bearer control-token", firstControlTokenHeader.get());
            assertEquals("Bearer control-token", secondControlTokenHeader.get());
        } finally {
            server.stop(0);
        }
    }

    private static String sha512Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                int v = b & 0xFF;
                if (v < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(v));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }

    @Test
    void climateCommandsIncludeTemperaturePayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "55555555-6666-7777-8888-999999999999";
        AtomicInteger callCounter = new AtomicInteger();
        AtomicReference<JsonObject> startPayload = new AtomicReference<>();
        AtomicReference<JsonObject> stopPayload = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/control/temperature", exchange -> {
            int call = callCounter.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (call == 1) {
                startPayload.set(json);
            } else {
                stopPayload.set(json);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            String responseBody = "{\"resMsg\":[{\"recordId\":\"job-1\",\"result\":\"success\"},{\"recordId\":\"job-2\",\"result\":\"success\"}]}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v1/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");
            TestContext ctx = new TestContext();
            ctx.api = api;
            ctx.oauth = oauth;
            ctx.stampProvider = provider;
            ctx.api.login();

            String idForStart = ctx.oauth.getDeviceId();
            ctx.api.start(vehicleId, "VIN", false);

            String idForStop = ctx.oauth.getDeviceId();
            ctx.api.stop(vehicleId, "VIN", false);

            JsonObject start = startPayload.get();
            assertEquals("start", start.get("action").getAsString());
            assertEquals("control-token", start.get("controlToken").getAsString());
            assertEquals(0, start.get("hvacType").getAsInt());
            assertEquals("0CH", start.get("tempCode").getAsString());
            assertEquals("C", start.get("unit").getAsString());
            assertEquals(idForStart, start.get("deviceId").getAsString());
            JsonObject startOptions = start.getAsJsonObject("options");
            assertEquals(false, startOptions.get("defrost").getAsBoolean());
            assertEquals(0, startOptions.get("heating1").getAsInt());
            assertFalse(start.has("pin"));

            JsonObject stop = stopPayload.get();
            assertEquals("stop", stop.get("action").getAsString());
            assertEquals("control-token", stop.get("controlToken").getAsString());
            assertEquals(0, stop.get("hvacType").getAsInt());
            assertEquals("0CH", stop.get("tempCode").getAsString());
            assertEquals("C", stop.get("unit").getAsString());
            assertEquals(idForStop, stop.get("deviceId").getAsString());
            JsonObject stopOptions = stop.getAsJsonObject("options");
            assertEquals(false, stopOptions.get("defrost").getAsBoolean());
            assertEquals(0, stopOptions.get("heating1").getAsInt());
            assertFalse(stop.has("pin"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chargeCommandsIncludeDeviceIdAndHandleDifferentSuccessCodes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "66666666-7777-8888-9999-000000000000";
        AtomicInteger callCounter = new AtomicInteger();
        AtomicReference<JsonObject> startPayload = new AtomicReference<>();
        AtomicReference<JsonObject> stopPayload = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/control/charge", exchange -> {
            int call = callCounter.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (call == 1) {
                startPayload.set(json);
                exchange.sendResponseHeaders(200, 0);
            } else {
                stopPayload.set(json);
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v1/spa/notifications/" + vehicleId + "/records", exchange -> {
            String responseBody = "{\"resMsg\":[{\"recordId\":\"c-job-1\",\"result\":\"success\"},{\"recordId\":\"c-job-2\",\"result\":\"success\"}]}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.api.login();

            String idForStart = ctx.oauth.getDeviceId();
            ctx.api.startCharge(vehicleId, "VIN", false);

            String idForStop = ctx.oauth.getDeviceId();
            ctx.api.stopCharge(vehicleId, "VIN", false);

            assertEquals("start", startPayload.get().get("action").getAsString());
            assertEquals("control-token", startPayload.get().get("controlToken").getAsString());
            assertEquals(idForStart, startPayload.get().get("deviceId").getAsString());
            assertFalse(startPayload.get().has("pin"));
            assertEquals("stop", stopPayload.get().get("action").getAsString());
            assertEquals("control-token", stopPayload.get().get("controlToken").getAsString());
            assertEquals(idForStop, stopPayload.get().get("deviceId").getAsString());
            assertFalse(stopPayload.get().has("pin"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startClimateSendsExtendedOptions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "climate-ext-id";
        AtomicReference<@Nullable String> bodyRef = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/control/temperature", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{\"msgId\":\"job-climate-ext\",\"resCode\":\"0000\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v1/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");
            TestContext ctx = new TestContext();
            ctx.api = api;
            ctx.oauth = oauth;
            ctx.stampProvider = provider;
            ctx.api.login();

            // Act: Start climate with all extended options enabled
            ctx.api.start(vehicleId, "VIN", 22.0, true, true, true, true, true, false);

            // Assert
            String body = bodyRef.get();
            assertNotNull(body);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            assertEquals("start", json.get("action").getAsString());

            assertTrue(json.has("options"));
            JsonObject options = json.getAsJsonObject("options");
            assertEquals(1, options.get("defrost").getAsBoolean() ? 1 : 0);
            assertEquals(1, options.get("heating1").getAsInt());
            assertEquals(1, options.get("heatingSteeringWheel").getAsInt());
            assertEquals(1, options.get("heatingSideMirror").getAsInt());
            assertEquals(1, options.get("heatingRearWindow").getAsInt());

        } finally {
            server.stop(0);
        }
    }

    private static class FakeStampProvider extends StampProvider {
        private final String stamp;
        private int invocationCount;

        FakeStampProvider(String stamp) {
            this.stamp = stamp;
        }

        @Override
        public String getStamp() {
            invocationCount++;
            return stamp;
        }

        int getInvocationCount() {
            return invocationCount;
        }
    }

    private TestContext createApiForPort(int port) {
        return createApiForPort(port, "1234");
    }

    private TestContext createApiForPort(int port, String pin) {
        FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
        Endpoints localEndpoints = new Endpoints();
        localEndpoints.oauth.clientId = "client";
        localEndpoints.ccapi.baseUrl = "http://localhost:" + port;
        FakeOAuthClient localOauth = new FakeOAuthClient(localEndpoints, provider);
        localOauth.setDeviceId("device-id");
        localOauth.setAccessToken("access-token");
        TestContext ctx = new TestContext();
        ctx.api = new BlueLinkApi(localEndpoints, localOauth, provider, pin);
        ctx.oauth = localOauth;
        ctx.stampProvider = provider;
        return ctx;
    }

    private static class TestContext {
        BlueLinkApi api;
        FakeOAuthClient oauth;
        FakeStampProvider stampProvider;
    }

    private static class FakeOAuthClient extends OAuthClient {
        private String accessToken = "access-token";
        private String refreshToken;
        private String deviceId = "device-id";

        FakeOAuthClient(Endpoints endpoints, StampProvider stampProvider) {
            super(endpoints, "en", "DE", false, stampProvider);
        }

        @Override
        public void refreshToken() throws Exception {
            accessToken = "refreshed-access-token";
            rotateDevice();
        }

        @Override
        public String getAccessToken() {
            return accessToken;
        }

        @Override
        public String getRefreshToken() {
            return refreshToken;
        }

        @Override
        public String getDeviceId() {
            return deviceId;
        }

        @Override
        public void registerDevice() throws Exception {
            // No-op in fake
        }

        @Override
        public void rotateDevice() throws Exception {
            deviceId = java.util.UUID.randomUUID().toString();
        }

        void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    @Test
    void lockRetriesWithSpaV1BaseOnForbidden() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "11111111-2222-3333-4444-555555555555";
        AtomicInteger v2Calls = new AtomicInteger();
        AtomicInteger v1Calls = new AtomicInteger();

        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/door", exchange -> {
            v2Calls.incrementAndGet();
            byte[] payload = "{\"error\":\"Access to this API has been disallowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/v1/spa/vehicles/" + vehicleId + "/control/door", exchange -> {
            v1Calls.incrementAndGet();
            byte[] payload = "{\"msgId\":\"v1-lock-id\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            TestContext ctx = createApiForPort(server.getAddress().getPort());
            ctx.api.lock(vehicleId, "VIN", false);

            assertEquals(1, v2Calls.get(), "Expected V2 call");
            assertEquals(1, v1Calls.get(), "Expected V1 call");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void climateCommandUsesCcs2Endpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String vehicleId = "ccs2-climate-id";
        AtomicReference<@Nullable String> requestedPath = new AtomicReference<>();
        server.createContext("/api/v1/user/pin", exchange -> {
            Instant expiry = Instant.now().plusSeconds(120);
            String body = "{\"controlToken\":\"control-token\",\"controlTokenExpiry\":\"" + expiry.toString() + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/temperature", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] payload = "{\"msgId\":\"ccs2-job-1\",\"resCode\":\"0000\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            FakeStampProvider provider = new FakeStampProvider(TEST_STAMP);
            Endpoints endpoints = new Endpoints();
            endpoints.oauth.clientId = "client";
            endpoints.ccapi.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v2/spa";
            FakeOAuthClient oauth = new FakeOAuthClient(endpoints, provider);
            oauth.setDeviceId("device-id");
            oauth.setAccessToken("access-token");
            BlueLinkApi api = new BlueLinkApi(endpoints, oauth, provider, "1234");
            api.login();

            // Act: Start climate with CCS2 support enabled
            api.start(vehicleId, "VIN", true);

            // Assert
            assertEquals("/api/v2/spa/vehicles/" + vehicleId + "/ccs2/control/temperature", requestedPath.get());
        } finally {
            server.stop(0);
        }
    }
}
