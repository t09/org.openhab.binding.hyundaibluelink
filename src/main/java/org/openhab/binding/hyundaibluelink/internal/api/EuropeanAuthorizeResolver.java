package org.openhab.binding.hyundaibluelink.internal.api;

import java.net.URI;
import java.util.Locale;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Helper for determining the normalized European authorize host.
 */
final class EuropeanAuthorizeResolver {

    private EuropeanAuthorizeResolver() {
    }

    static @Nullable String resolveHost(@Nullable URI uri) {
        if (uri == null) {
            return null;
        }
        return resolveHost(uri.getHost());
    }

    static @Nullable String resolveHost(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.contains("idpconnect-eu.kia.com")) {
            return "idpconnect-eu.kia.com";
        }
        if (normalized.contains("idpconnect-eu.hyundai.com")) {
            return "idpconnect-eu.hyundai.com";
        }
        if (normalized.contains("kia")) {
            return "idpconnect-eu.kia.com";
        }
        if (normalized.contains("hyundai")) {
            return "idpconnect-eu.hyundai.com";
        }
        return null;
    }
}
