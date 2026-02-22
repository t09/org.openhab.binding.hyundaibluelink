package org.openhab.binding.hyundaibluelink.internal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints.OAuth;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests for {@link OAuthClient}.
 */
public @NonNullByDefault @SuppressWarnings("null") class OAuthClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private final AtomicReference<String> loginBodyRef = new AtomicReference<>();
    private final AtomicReference<Headers> loginHeadersRef = new AtomicReference<>();
    private final AtomicReference<HttpHandler> loginHandlerRef = new AtomicReference<>();
    private final AtomicReference<String> tokenResponseRef = new AtomicReference<>();
    private final AtomicReference<String> registerBodyRef = new AtomicReference<>();
    private final AtomicReference<Headers> registerHeadersRef = new AtomicReference<>();
    private final AtomicInteger registerCallCount = new AtomicInteger();
    private final AtomicReference<String> registerResponseRef = new AtomicReference<>();
    private final AtomicInteger callSequence = new AtomicInteger();
    private final AtomicInteger registerSequence = new AtomicInteger();
    private final AtomicInteger tokenSequence = new AtomicInteger();

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        loginHandlerRef.set(this::respondWithLoginSuccess);
        server.createContext("/login", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                loginHeadersRef.set(exchange.getRequestHeaders());
                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                loginBodyRef.set(new String(bodyBytes, StandardCharsets.UTF_8));
                HttpHandler handler = loginHandlerRef.get();
                if (handler != null) {
                    handler.handle(exchange);
                }
            }
        });

        tokenResponseRef.set("{\"access_token\":\"ACCESS\",\"refresh_token\":\"REFRESH\"}");
        registerCallCount.set(0);
        registerBodyRef.set(null);
        registerHeadersRef.set(null);
        registerResponseRef.set("{\"resMsg\":{\"deviceId\":\"REGISTERED-DEVICE\"}}");
        callSequence.set(0);
        registerSequence.set(0);
        tokenSequence.set(0);

        server.createContext("/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                tokenSequence.set(callSequence.incrementAndGet());
                byte[] response = tokenResponseRef.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });

        server.createContext("/api/v1/spa/notifications/register", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                registerSequence.set(callSequence.incrementAndGet());
                registerCallCount.incrementAndGet();
                registerHeadersRef.set(exchange.getRequestHeaders());
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                registerBodyRef.set(new String(requestBytes, StandardCharsets.UTF_8));
                byte[] response = registerResponseRef.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void refreshTokenShouldPersistConnectorTokens() throws Exception {
        Endpoints endpoints = new Endpoints();
        endpoints.oauth = new OAuth();
        endpoints.oauth.loginUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/login";
        endpoints.oauth.tokenUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/token";
        endpoints.oauth.clientId = "CLIENT";
        endpoints.oauth.clientSecret = "SECRET";
        endpoints.ccapi = new Endpoints.CCAPI();
        endpoints.ccapi.baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/api/v2/spa";

        OAuthClient client = new OAuthClient(endpoints, "en", "DE", true,
                new StampProvider() {
                    @Override
                    public String getStamp() {
                        return "STAMP";
                    }

                    @Override
                    protected void download(java.nio.file.Path target, URI source)
                            throws IOException, InterruptedException {
                        throw new UnsupportedOperationException();
                    }
                }, HttpClient.newHttpClient());

        client.setInitialRefreshToken("INITIAL_REFRESH");

        tokenResponseRef.set(
                "{\"connector\":{\"hmgid1.0\":{\"access_token\":\"ACCESS2\",\"refresh_token\":\"REFRESH2\"}}}");

        client.refreshToken();

        assertEquals("ACCESS2", client.getAccessToken());
        assertEquals("REFRESH2", client.getRefreshToken());
    }

    @Test
    public void refreshTokenShouldRetainRefreshTokenWhenOmittedFromResponse() throws Exception {
        Endpoints endpoints = new Endpoints();
        endpoints.oauth = new OAuth();
        endpoints.oauth.loginUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/login";
        endpoints.oauth.tokenUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/token";
        endpoints.oauth.clientId = "CLIENT";
        endpoints.oauth.clientSecret = "SECRET";
        endpoints.ccapi = new Endpoints.CCAPI();
        endpoints.ccapi.baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/api/v2/spa";

        OAuthClient client = new OAuthClient(endpoints, "en", "DE", true,
                new StampProvider() {
                    @Override
                    public String getStamp() {
                        return "STAMP";
                    }

                    @Override
                    protected void download(java.nio.file.Path target, URI source)
                            throws IOException, InterruptedException {
                        throw new UnsupportedOperationException();
                    }
                }, HttpClient.newHttpClient());

        client.setInitialRefreshToken("REFRESH1");

        tokenResponseRef.set("{\"access_token\":\"ACCESS2\"}");

        client.refreshToken();

        assertEquals("ACCESS2", client.getAccessToken());
        assertEquals("REFRESH1", client.getRefreshToken());
    }

    @Test
    public void refreshTokenShouldHandleConnectorResponseWithoutRefreshToken() throws Exception {
        Endpoints endpoints = new Endpoints();
        endpoints.oauth = new OAuth();
        endpoints.oauth.loginUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/login";
        endpoints.oauth.tokenUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/token";
        endpoints.oauth.clientId = "CLIENT";
        endpoints.oauth.clientSecret = "SECRET";
        endpoints.ccapi = new Endpoints.CCAPI();
        endpoints.ccapi.baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port
                + "/api/v2/spa";

        OAuthClient client = new OAuthClient(endpoints, "en", "DE", true,
                new StampProvider() {
                    @Override
                    public String getStamp() {
                        return "STAMP";
                    }

                    @Override
                    protected void download(java.nio.file.Path target, URI source)
                            throws IOException, InterruptedException {
                        throw new UnsupportedOperationException();
                    }
                }, HttpClient.newHttpClient());

        client.setInitialRefreshToken("REFRESH1");

        tokenResponseRef
                .set("{\"connector\":{\"hmgid1.0\":{\"access_token\":\"ACCESS2\"}}}");

        client.refreshToken();

        assertEquals("ACCESS2", client.getAccessToken());
        assertEquals("REFRESH1", client.getRefreshToken());
    }

    @Test
    public void buildTokenRequestShouldUseFormDataForEuCcapiHost() throws Exception {
        assertFormTokenRequestFor("https://eu-ccapi.example.com:8080/token");
    }

    @Test
    public void buildTokenRequestShouldUseFormDataForCnCcapiHost() throws Exception {
        assertFormTokenRequestFor("https://prd.cn-ccapi.hyundai.com/token");
    }

    @Test
    public void buildTokenRequestShouldUseFormDataForAuApigwHost() throws Exception {
        assertFormTokenRequestFor("https://au-apigw.ccs.hyundai.com.au:8080/token");
    }

    private void respondWithLoginSuccess(HttpExchange exchange) throws IOException {
        byte[] response = "{\"code\":\"AUTH_CODE\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String readBodyPublisher(HttpRequest.BodyPublisher publisher) throws Exception {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<java.nio.ByteBuffer>() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(buffer.toByteArray());
            }
        });

        byte[] bytes = future.get(5, TimeUnit.SECONDS);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void assertFormTokenRequestFor(String tokenUrl) throws Exception {
        OAuthClient client = createClientForTokenTests();
        HttpRequest request = invokeBuildTokenRequest(client, URI.create(tokenUrl));

        assertEquals("application/x-www-form-urlencoded", request.headers().firstValue("Content-Type").orElse(null));
        String body = readBodyPublisher(request.bodyPublisher().orElseThrow());
        assertTrue(body.contains("grant_type=authorization_code"));
        assertTrue(body.contains("client_id=CLIENT"));
        assertTrue(body.contains("client_secret=SECRET"));
    }

    private OAuthClient createClientForTokenTests() {
        Endpoints endpoints = new Endpoints();
        endpoints.oauth = new OAuth();
        endpoints.oauth.clientId = "CLIENT";
        endpoints.oauth.clientSecret = "SECRET";

        return new OAuthClient(endpoints, "en", "DE", true, new StampProvider() {
            @Override
            protected void download(java.nio.file.Path target, URI source) throws IOException, InterruptedException {
                throw new UnsupportedOperationException();
            }
        }, HttpClient.newHttpClient());
    }

    private HttpRequest invokeBuildTokenRequest(OAuthClient client, URI tokenUri) throws Exception {
        Class<?> grantClass = null;
        for (Class<?> candidate : OAuthClient.class.getDeclaredClasses()) {
            if ("AuthorizationGrant".equals(candidate.getSimpleName())) {
                grantClass = candidate;
                break;
            }
        }
        assertNotNull(grantClass);

        java.lang.reflect.Constructor<?> constructor = grantClass.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        Object grant = constructor.newInstance("AUTH_CODE", "https://redirect.example.com/callback");

        java.lang.reflect.Method buildTokenRequest = OAuthClient.class.getDeclaredMethod("buildTokenRequest", URI.class,
                grantClass);
        buildTokenRequest.setAccessible(true);
        return (java.net.http.HttpRequest) buildTokenRequest.invoke(client, tokenUri, grant);
    }
}
