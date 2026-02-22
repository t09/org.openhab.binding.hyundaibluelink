package org.openhab.binding.hyundaibluelink.internal;

import org.openhab.core.config.core.Configuration;

public class AccountConfiguration {
    public String initialRefreshToken;
    public String brand = "hyundai";
    public String region = "eu";
    public String country = "DE";
    public String language = "de";
    public String clientId;
    public String clientSecret;
    public boolean autoUpdateStamp = true;
    public String endpointsOverride;
    public int refreshMinutes = 60;
    public String pin;

    public static AccountConfiguration from(Configuration cfg) {
        AccountConfiguration c = new AccountConfiguration();
        Object initToken = cfg.get(HyundaiBlueLinkBindingConstants.CONFIG_INITIAL_REFRESH_TOKEN);
        if (initToken instanceof String) {
            c.initialRefreshToken = (String) initToken;
        } else if (initToken != null) {
            c.initialRefreshToken = initToken.toString();
        }

        if (cfg.get("brand") != null)
            c.brand = String.valueOf(cfg.get("brand"));
        if (cfg.get("region") != null)
            c.region = String.valueOf(cfg.get("region"));
        if (cfg.get("country") != null)
            c.country = String.valueOf(cfg.get("country"));
        if (cfg.get("language") != null)
            c.language = String.valueOf(cfg.get("language"));
        c.clientId = (String) cfg.get("clientId");
        c.clientSecret = (String) cfg.get("clientSecret");
        Object aus = cfg.get("autoUpdateStamp");
        if (aus instanceof Boolean)
            c.autoUpdateStamp = (Boolean) aus;
        c.endpointsOverride = (String) cfg.get("endpointsOverride");
        c.pin = (String) cfg.get(HyundaiBlueLinkBindingConstants.CONFIG_PIN);
        Object refresh = cfg.get(HyundaiBlueLinkBindingConstants.CONFIG_REFRESH);
        if (refresh instanceof Number) {
            c.refreshMinutes = Math.max(0, ((Number) refresh).intValue());
        } else if (refresh != null) {
            try {
                c.refreshMinutes = Math.max(0, Integer.parseInt(refresh.toString()));
            } catch (NumberFormatException e) {
                c.refreshMinutes = 60;
            }
        }
        if (c.clientId == null)
            c.clientId = "";
        if (c.clientSecret == null)
            c.clientSecret = "";
        if (c.endpointsOverride == null)
            c.endpointsOverride = "";
        if (c.pin == null)
            c.pin = "";
        if (c.initialRefreshToken == null)
            c.initialRefreshToken = "";
        return c;
    }
}
