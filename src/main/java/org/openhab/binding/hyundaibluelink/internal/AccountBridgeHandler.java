package org.openhab.binding.hyundaibluelink.internal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hyundaibluelink.internal.api.BlueLinkApi;
import org.openhab.binding.hyundaibluelink.internal.model.*;
import org.openhab.binding.hyundaibluelink.internal.api.OAuthClient;
import org.openhab.binding.hyundaibluelink.internal.api.StampProvider;
import org.openhab.binding.hyundaibluelink.internal.discovery.HyundaiBlueLinkDiscoveryService;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver.Endpoints;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import org.openhab.binding.hyundaibluelink.internal.util.EndpointResolver;

@org.eclipse.jdt.annotation.NonNullByDefault
public class AccountBridgeHandler extends BaseBridgeHandler {
    private final Logger logger = Objects.requireNonNull(LoggerFactory.getLogger(AccountBridgeHandler.class));

    private @Nullable AccountConfiguration cfg;
    private @Nullable BlueLinkApi api;

    public AccountBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Objects.requireNonNull((Collection<Class<? extends ThingHandlerService>>) (Object) Set.of(
                HyundaiBlueLinkDiscoveryService.class, HyundaiBlueLinkActions.class));
    }

    @Override
    public void initialize() {
        AccountConfiguration localCfg = AccountConfiguration.from(getConfig());
        cfg = localCfg;
        try {
            JsonNode root = EndpointResolver.loadTree(this.getClass().getClassLoader(), localCfg.endpointsOverride);
            Endpoints localEndpoints = Objects
                    .requireNonNull(EndpointResolver.resolve(root, localCfg.region, localCfg.brand));

            if (localCfg.clientId != null && !localCfg.clientId.isBlank()) {
                localEndpoints.oauth.clientId = localCfg.clientId;
            }
            if (localCfg.clientSecret != null && !localCfg.clientSecret.isBlank()) {
                localEndpoints.oauth.clientSecret = localCfg.clientSecret;
            }

            StampProvider localStampProvider = createStampProvider();
            OAuthClient localClient = createOAuthClient(localEndpoints, localStampProvider);
            api = createBlueLinkApi(localEndpoints, localClient, localStampProvider);

            scheduler.execute(() -> loginAfterAuthorization());
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            logger.warn("Account init failed: {}", e.getMessage());
        }
    }

    protected OAuthClient createOAuthClient(Endpoints endpoints, StampProvider stampProvider) {
        AccountConfiguration localCfg = Objects.requireNonNull(cfg);
        OAuthClient client = new OAuthClient(endpoints, localCfg.language,
                localCfg.country, localCfg.autoUpdateStamp, stampProvider);
        String initToken = localCfg.initialRefreshToken;
        if (initToken != null && !initToken.isBlank()) {
            logger.info("Using initial refresh token from configuration for {}", getThing().getUID());
            client.setInitialRefreshToken(initToken);
        }
        return client;
    }

    protected StampProvider createStampProvider() {
        return new StampProvider();
    }

    protected BlueLinkApi createBlueLinkApi(Endpoints endpoints, OAuthClient oauthClient,
            StampProvider stampProvider) {
        AccountConfiguration localCfg = Objects.requireNonNull(cfg);
        String pin = localCfg.pin;
        return new BlueLinkApi(endpoints, oauthClient, stampProvider, pin == null ? "" : pin);
    }

    protected void loginApi(BlueLinkApi apiToLogin) throws Exception {
        apiToLogin.login();
    }

    public @Nullable BlueLinkApi api() {
        return api;
    }

    @SuppressWarnings("null")
    public List<VehicleSummary> listVehicles() throws Exception {
        BlueLinkApi localApi = api;
        if (localApi == null) {
            return List.of();
        }
        return localApi.listVehicles();
    }

    public int refreshIntervalMinutes() {
        AccountConfiguration localCfg = cfg;
        if (localCfg == null) {
            return 60;
        }
        return Math.max(0, localCfg.refreshMinutes);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // no channels on bridge
    }

    private void loginAfterAuthorization() {
        if (api == null) {
            logger.debug("Cannot login after authorization - API not initialized");
            return;
        }
        try {
            loginApi(Objects.requireNonNull(api));
            updateStatus(ThingStatus.ONLINE);
        } catch (Exception loginEx) {
            String detail = loginEx.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = loginEx.getClass().getSimpleName();
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, detail);
            logger.warn("Account login failed during initialization for {}", getThing().getUID(), loginEx);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}