package org.openhab.binding.hyundaibluelink.internal.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EndpointResolver {
    public static class Endpoints {
        public OAuth oauth = new OAuth();
        public CCAPI ccapi = new CCAPI();

        public static class OAuth {
            public String clientId;
            public String applicationId;
            public String clientSecret;
            public String authorizeUrl;
            public String tokenUrl;
            public String loginUrl;
            public String browserFallbackUrl;
            public String integrationInfoUrl;
            public String requiredActionUrl;
            public String silentSigninUrl;
        }
        public static class CCAPI {
            public String baseUrl;
        }
    }

    public static JsonNode loadTree(ClassLoader cl, String overridePath) throws Exception {
        ObjectMapper om = new ObjectMapper();
        if (overridePath != null && !overridePath.isBlank()) {
            Path p = Path.of(overridePath);
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    return om.readTree(in);
                }
            }
        }
        try (InputStream in = cl.getResourceAsStream("OH-INF/endpoints-defaults.json")) {
            if (in == null) throw new IllegalStateException("endpoints-defaults.json not found in resources");
            return om.readTree(in);
        }
    }

    public static Endpoints resolve(JsonNode root, String region, String brand) {
        JsonNode n = root.path(region).path(brand);
        if (n.isMissingNode()) throw new IllegalArgumentException("No endpoints for region=" + region + " brand=" + brand);
        Endpoints e = new Endpoints();
        e.oauth.clientId = text(n, "oauth", "clientId");
        e.oauth.applicationId = text(n, "oauth", "applicationId");
        e.oauth.clientSecret = text(n, "oauth", "clientSecret");
        e.oauth.authorizeUrl = text(n, "oauth", "authorizeUrl");
        e.oauth.tokenUrl = text(n, "oauth", "tokenUrl");
        e.oauth.loginUrl = text(n, "oauth", "loginUrl");
        e.oauth.browserFallbackUrl = text(n, "oauth", "browserFallbackUrl");
        e.oauth.integrationInfoUrl = text(n, "oauth", "integrationInfoUrl");
        e.oauth.requiredActionUrl = text(n, "oauth", "requiredActionUrl");
        e.oauth.silentSigninUrl = text(n, "oauth", "silentSigninUrl");
        e.ccapi.baseUrl = text(n, "ccapi", "baseUrl");
        return e;
    }

    private static String text(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) {
            cur = cur.path(p);
        }
        return cur.isMissingNode() ? null : cur.asText(null);
    }
}
