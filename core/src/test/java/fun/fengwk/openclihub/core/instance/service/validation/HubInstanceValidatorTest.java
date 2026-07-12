package fun.fengwk.openclihub.core.instance.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceEditablePropertiesDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure-function rules of {@link HubInstanceValidator} without any Spring context
 * or persistence layer. Each test pins down exactly one rule boundary so regressions in the
 * validator are easy to triage.
 */
class HubInstanceValidatorTest {

    private final HubInstanceValidator validator = new HubInstanceValidator(
        () -> Set.of("bilibili", "chatgpt"));

    @Test
    void shouldAcceptValidPayloadAndNormalizeWebsites() {
        HubInstanceEditablePropertiesDTO dto = new HubInstanceEditablePropertiesDTO();
        dto.setCode("instance-01");
        dto.setDisplayName("Instance One  ");
        dto.setWebsites(List.of("  bilibili  ", "bilibili", "chatgpt"));
        dto.setMaxPending(5);

        List<String> normalized = validator.validateEditableProperties(dto);

        assertThat(normalized).containsExactly("bilibili", "chatgpt");
        assertThat(dto.getDisplayName()).isEqualTo("Instance One");
    }

    @Test
    void shouldRejectBlankCode() {
        // Whitespace-only code must fail before length/pattern checks fire.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setCode("   ");
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));
    }

    @Test
    void shouldRejectTooLongDisplayName() {
        // displayName > 128 chars after trim is rejected per design §8.1.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setDisplayName("x".repeat(129));
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectWebsiteNotInCatalog() {
        // A website not declared by the catalog must produce INSTANCE_WEBSITE_NOT_ENABLED
        // so the front-end can distinguish "unknown site" from generic arg errors.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setWebsites(List.of("unknown-site"));
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED));
    }

    @Test
    void shouldFailLoudlyWhenCatalogUnavailable() {
        // Without a catalog source the validator must refuse the payload rather than
        // accept arbitrary websites (no silent default-allow).
        HubInstanceValidator isolated = new HubInstanceValidator(Set::of);
        HubInstanceEditablePropertiesDTO dto = baseDto();
        assertThatThrownBy(() -> isolated.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .hasMessageContaining("catalog is not available");
    }

    @Test
    void shouldRejectEmptyWebsiteList() {
        // Routing requires at least one website so empty list is invalid input.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setWebsites(List.of());
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectMaxPendingBelowOne() {
        // maxPending == 0 would create a zero-capacity dispatcher; reject below 1.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setMaxPending(0);
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectMaxPendingAboveUpperBound() {
        // Cap protects against unbounded queue depth and matches execution pool sizing.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setMaxPending(999);
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectCodeWithInvalidCharacters() {
        // Uppercase and spaces violate the lowercase/digit/hyphen stable format.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setCode("BAD CODE!");
        assertThatThrownBy(() -> validator.validateEditableProperties(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectCodeStartingOrEndingWithHyphen() {
        // Leading or trailing hyphens would yield ambiguous URL/filename segments.
        HubInstanceEditablePropertiesDTO dto = baseDto();
        dto.setCode("-bad");
        assertThatThrownBy(() -> validator.validateEditableProperties(dto)).isInstanceOf(ThrowableConventionErrorCode.class);

        dto.setCode("bad-");
        assertThatThrownBy(() -> validator.validateEditableProperties(dto)).isInstanceOf(ThrowableConventionErrorCode.class);
    }

    private HubInstanceEditablePropertiesDTO baseDto() {
        HubInstanceEditablePropertiesDTO dto = new HubInstanceEditablePropertiesDTO();
        dto.setCode("instance-01");
        dto.setDisplayName("Instance One");
        dto.setWebsites(List.of("bilibili"));
        dto.setMaxPending(5);
        return dto;
    }

    /**
     * Resolves the actual wire code (e.g. {@code "HUB.INSTANCE_ARGUMENT_INVALID"}) the way
     * the convention4j resolver would. The constant's name() is the suffix and the domain
     * is hard-wired to {@code "HUB"}.
     */
    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }

}
