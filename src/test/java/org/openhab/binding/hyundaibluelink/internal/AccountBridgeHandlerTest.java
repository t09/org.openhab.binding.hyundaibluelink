package org.openhab.binding.hyundaibluelink.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.api.OAuthClient;
import org.openhab.binding.hyundaibluelink.internal.api.StampProvider;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

@NonNullByDefault
@SuppressWarnings("null")
class AccountBridgeHandlerTest {

    private static class NoOpAccountBridgeHandler extends AccountBridgeHandler {
        Endpoints capturedEndpoints;

        NoOpAccountBridgeHandler(Bridge bridge) {
            super(bridge);
        }

        @Override
        protected OAuthClient createOAuthClient(Endpoints endpoints, StampProvider stampProvider) {
            this.capturedEndpoints = endpoints;
            return super.createOAuthClient(endpoints, stampProvider);
        }

        @Override
        protected void loginApi(BlueLinkApi apiToLogin) throws Exception {
            // prevent real HTTP interactions during tests
        }
    }

    private NoOpAccountBridgeHandler initializeHandler(String region, String brand) {
        Configuration cfg = new Configuration();
        cfg.put("email", "user@example.com");
        cfg.put("password", "secret");
        cfg.put("pin", "1234");
        cfg.put("region", region);
        cfg.put("brand", brand);
        cfg.put("country", region.equals("ca") ? "CA" : "US");
        cfg.put("language", "en");

        Bridge bridge = BridgeBuilder
                .create(HyundaiBlueLinkBindingConstants.THING_TYPE_ACCOUNT_BRIDGE, "test-" + region + "-" + brand)
                .withConfiguration(cfg).build();

        NoOpAccountBridgeHandler handler = new NoOpAccountBridgeHandler(bridge);
        handler.initialize();
        assertNotNull(handler.capturedEndpoints, "initialize() should capture endpoints");
        return handler;
    }

    @Test
    void initializeLoadsEuHyundaiEndpoints() {
        NoOpAccountBridgeHandler handler = initializeHandler("eu", "hyundai");
        Endpoints endpoints = handler.capturedEndpoints;
        assertEquals("https://prd.eu-ccapi.hyundai.com:8080/api/v2/spa", endpoints.ccapi.baseUrl);
        // client id for EU/Hyundai is dynamic or set in endpoints-defaults.json
    }

}
