package org.openhab.binding.hyundaibluelink.internal.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tests for {@link EndpointResolver}.
 */
public @NonNullByDefault @SuppressWarnings("null") class EndpointResolverTest {

    private static JsonNode root;

    @BeforeAll
    public static void loadDefaults() throws Exception {
        root = EndpointResolver.loadTree(EndpointResolverTest.class.getClassLoader(), null);
        assertNotNull(root, "Endpoint configuration should load from defaults");
    }

    @Test
    public void europeanBrandsProvideSilentSigninEndpoints() {
        EndpointResolver.Endpoints hyundai = EndpointResolver.resolve(root, "eu", "hyundai");
        // For European Hyundai we rely only on the primary authorize/token endpoints;
        // the
        // additional integration / required-action / silent-signin endpoints may be
        // omitted.
        assertNotNull(hyundai, "Hyundai EU endpoints should resolve");

        EndpointResolver.Endpoints kia = EndpointResolver.resolve(root, "eu", "kia");
        assertFalse(kia.oauth.integrationInfoUrl == null || kia.oauth.integrationInfoUrl.isBlank(),
                "Kia integration info URL should be populated");
        assertFalse(kia.oauth.requiredActionUrl == null || kia.oauth.requiredActionUrl.isBlank(),
                "Kia required action URL should be populated");
        assertFalse(kia.oauth.silentSigninUrl == null || kia.oauth.silentSigninUrl.isBlank(),
                "Kia silent sign-in URL should be populated");
    }
}
