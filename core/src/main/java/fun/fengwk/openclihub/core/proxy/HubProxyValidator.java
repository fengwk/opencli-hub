package fun.fengwk.openclihub.core.proxy;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and canonicalizes browser proxy settings.
 *
 * @author fengwk
 */
public final class HubProxyValidator {

    public static final int PROXY_SERVER_MAX_LENGTH = 512;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "socks4", "socks5");

    private HubProxyValidator() {
    }

    public static ProxyConfiguration normalizeGlobal(HubProxyMode proxyMode, String proxyServer) {
        if (proxyMode == null) {
            throw invalid(HubErrorCodes.SETTINGS_ARGUMENT_INVALID, "proxyMode is required");
        }
        if (proxyMode == HubProxyMode.INHERIT) {
            throw invalid(HubErrorCodes.SETTINGS_ARGUMENT_INVALID,
                "global proxyMode must be DIRECT or CUSTOM");
        }
        return normalize(proxyMode, proxyServer, HubErrorCodes.SETTINGS_ARGUMENT_INVALID);
    }

    /** Legacy instance requests without a mode use the global setting. */
    public static ProxyConfiguration normalizeInstance(HubProxyMode proxyMode, String proxyServer) {
        return normalize(proxyMode == null ? HubProxyMode.INHERIT : proxyMode, proxyServer,
            HubErrorCodes.INSTANCE_ARGUMENT_INVALID);
    }

    private static ProxyConfiguration normalize(HubProxyMode proxyMode, String proxyServer,
        HubErrorCodes errorCode) {
        if (proxyMode != HubProxyMode.CUSTOM) {
            return new ProxyConfiguration(proxyMode, null);
        }
        return new ProxyConfiguration(proxyMode, normalizeServer(proxyServer, errorCode));
    }

    private static String normalizeServer(String proxyServer, HubErrorCodes errorCode) {
        if (proxyServer == null || proxyServer.isBlank()) {
            throw invalid(errorCode, "proxyServer is required when proxyMode is CUSTOM");
        }
        String value = proxyServer.trim();
        if (value.length() > PROXY_SERVER_MAX_LENGTH) {
            throw invalid(errorCode,
                "proxyServer length must be <= " + PROXY_SERVER_MAX_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw invalid(errorCode, "proxyServer must be a valid proxy URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw invalid(errorCode,
                "proxyServer scheme must be http, https, socks4, or socks5");
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawUserInfo() != null) {
            throw invalid(errorCode,
                "proxyServer must be an unauthenticated hierarchical URI");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid(errorCode, "proxyServer host is required");
        }
        if (uri.getPort() < 1 || uri.getPort() > 65535) {
            throw invalid(errorCode, "proxyServer must specify a port from 1 to 65535");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(errorCode, "proxyServer must not include a query or fragment");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw invalid(errorCode, "proxyServer must not include a path");
        }
        try {
            String normalized = new URI(
                scheme.toLowerCase(Locale.ROOT),
                null,
                uri.getHost().toLowerCase(Locale.ROOT),
                uri.getPort(),
                null,
                null,
                null).toASCIIString();
            if (normalized.length() > PROXY_SERVER_MAX_LENGTH) {
                throw invalid(errorCode,
                    "proxyServer length must be <= " + PROXY_SERVER_MAX_LENGTH);
            }
            return normalized;
        } catch (URISyntaxException ex) {
            throw invalid(errorCode, "proxyServer must be a valid proxy URI");
        }
    }

    private static RuntimeException invalid(HubErrorCodes errorCode, String message) {
        return errorCode.asThrowable(message);
    }

    public record ProxyConfiguration(HubProxyMode proxyMode, String proxyServer) {
    }

}
