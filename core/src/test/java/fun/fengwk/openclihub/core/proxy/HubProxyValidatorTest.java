package fun.fengwk.openclihub.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Verifies proxy modes cannot inject malformed Chrome command-line values. */
class HubProxyValidatorTest {

    @Test
    void shouldNormalizeLegacyInstanceAndNonCustomServers() {
        assertThat(HubProxyValidator.normalizeInstance(null, "http://ignored:8080"))
            .satisfies(value -> {
                assertThat(value.proxyMode()).isEqualTo(HubProxyMode.INHERIT);
                assertThat(value.proxyServer()).isNull();
            });
        assertThat(HubProxyValidator.normalizeInstance(HubProxyMode.DIRECT, "http://ignored:8080"))
            .satisfies(value -> {
                assertThat(value.proxyMode()).isEqualTo(HubProxyMode.DIRECT);
                assertThat(value.proxyServer()).isNull();
            });
    }

    @Test
    void shouldCanonicalizeSupportedCustomProxyUri() {
        assertThat(HubProxyValidator.normalizeGlobal(
            HubProxyMode.CUSTOM, "  SOCKS5://Proxy.Example:1080  ").proxyServer())
            .isEqualTo("socks5://proxy.example:1080");
        assertThat(HubProxyValidator.normalizeInstance(
            HubProxyMode.CUSTOM, "http://[2001:db8::1]:8080").proxyServer())
            .isEqualTo("http://[2001:db8::1]:8080");
    }

    @Test
    void shouldAcceptEverySupportedSchemeAndEnforceTheLengthBoundary() {
        for (String scheme : new String[] { "http", "https", "socks4", "socks5" }) {
            assertThat(HubProxyValidator.normalizeGlobal(
                HubProxyMode.CUSTOM, scheme.toUpperCase(Locale.ROOT) + "://proxy.example:8080")
                .proxyServer()).isEqualTo(scheme + "://proxy.example:8080");
        }
        String maxLength = "http://" + "a".repeat(502) + ":80";
        assertThat(maxLength).hasSize(HubProxyValidator.PROXY_SERVER_MAX_LENGTH);
        assertThat(HubProxyValidator.normalizeGlobal(HubProxyMode.CUSTOM, maxLength).proxyServer())
            .isEqualTo(maxLength);
        assertCode(() -> HubProxyValidator.normalizeGlobal(
            HubProxyMode.CUSTOM, maxLength + "a"), HubErrorCodes.SETTINGS_ARGUMENT_INVALID);
    }

    @Test
    void shouldRejectInvalidGlobalModeWithSettingsError() {
        assertCode(() -> HubProxyValidator.normalizeGlobal(HubProxyMode.INHERIT, null),
            HubErrorCodes.SETTINGS_ARGUMENT_INVALID);
        assertCode(() -> HubProxyValidator.normalizeGlobal(null, null),
            HubErrorCodes.SETTINGS_ARGUMENT_INVALID);
    }

    @Test
    void shouldRejectUnsafeCustomProxyUrisWithScopeSpecificError() {
        String[] invalid = {
            "ftp://proxy.example:21",
            "http://proxy.example",
            "http://user:pass@proxy.example:8080",
            "http://proxy.example:8080/path",
            "http://proxy.example:8080?x=1",
            "http://proxy.example:8080#fragment",
            "http://:8080"
        };
        for (String value : invalid) {
            assertCode(() -> HubProxyValidator.normalizeInstance(HubProxyMode.CUSTOM, value),
                HubErrorCodes.INSTANCE_ARGUMENT_INVALID);
            assertCode(() -> HubProxyValidator.normalizeGlobal(HubProxyMode.CUSTOM, value),
                HubErrorCodes.SETTINGS_ARGUMENT_INVALID);
        }
    }

    private static void assertCode(Runnable action, HubErrorCodes expected) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(expected.getDomain() + "." + expected.name());
    }

}
