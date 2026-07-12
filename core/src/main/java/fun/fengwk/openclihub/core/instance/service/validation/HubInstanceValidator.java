package fun.fengwk.openclihub.core.instance.service.validation;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceEditablePropertiesDTO;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pure-function validator for instance editable properties and website bindings.
 *
 * <p>Validation rules (per docs/technical-design.md §8.1 and implementation-plan.md §M3):
 * <ul>
 *   <li>{@code code} must match the stable format and have a configurable length (1..64).</li>
 *   <li>{@code displayName} must be non-blank and within 128 chars after trim.</li>
 *   <li>{@code websites} must be non-empty, trimmed, deduplicated and each item must belong
 *       to the catalog website set.</li>
 *   <li>{@code maxPending} must be positive and within a reasonable upper bound.</li>
 * </ul>
 *
 * <p>The {@link OpenCliCommandCatalog} is queried through {@link CatalogWebsiteSource} so the
 * validator can be unit tested without Spring context. If the catalog is not yet supplied by
 * M1, validation must fail loudly with {@link HubErrorCodes#INSTANCE_ARGUMENT_INVALID} rather
 * than silently accepting any website.
 *
 * @author fengwk
 */
@Component
public class HubInstanceValidator {

    /**
     * Stable code format: lowercase letters, digits and single hyphens. Must start and end with
     * an alphanumeric character. Length 1..64 inclusive. The optional middle group accepts up to
     * 62 lowercase letters / digits / hyphens, which permits 1- and 2-character codes as well
     * as longer ones.
     */
    public static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

    public static final int CODE_MAX_LENGTH = 64;
    public static final int DISPLAY_NAME_MAX_LENGTH = 128;
    public static final int MAX_PENDING_MIN = 1;
    public static final int MAX_PENDING_MAX = 50;
    public static final int CONTEXT_ID_MAX_LENGTH = 128;

    private final CatalogWebsiteLookup websiteSource;

    public HubInstanceValidator(CatalogWebsiteLookup websiteSource) {
        this.websiteSource = websiteSource;
    }

    /**
     * Validates the editable properties and returns the normalized website list (trimmed,
     * deduplicated, order-preserving). Side effect: writes the normalized code, displayName
     * and maxPending back to {@code dto} so the caller persists the canonical form.
     *
     * @param dto input properties, may be {@code null}
     * @return normalized websites
     * @throws ThrowableConventionErrorCode when any rule is violated
     */
    public List<String> validateEditableProperties(HubInstanceEditablePropertiesDTO dto) {
        if (dto == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("instance editable payload is required");
        }
        dto.setCode(validateCode(dto.getCode()));
        dto.setDisplayName(validateDisplayName(dto.getDisplayName()));
        dto.setMaxPending(validateMaxPending(dto.getMaxPending()));
        List<String> websites = validateWebsites(dto.getWebsites());
        return websites;
    }

    /**
     * Validates {@code code} format and returns the trimmed, canonical value.
     * The same rules apply to create and update paths.
     */
    public String validateCode(String code) {
        if (code == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("code is required");
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("code must not be blank");
        }
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "code length must be <= " + CODE_MAX_LENGTH);
        }
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "code must match " + CODE_PATTERN.pattern());
        }
        return trimmed;
    }

    /**
     * Validates display name and returns the trimmed value.
     */
    public String validateDisplayName(String displayName) {
        if (displayName == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("displayName is required");
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("displayName must not be blank");
        }
        if (trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "displayName length must be <= " + DISPLAY_NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * Validates maxPending range.
     *
     * @return the validated value as primitive int
     */
    public int validateMaxPending(Integer maxPending) {
        if (maxPending == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("maxPending is required");
        }
        if (maxPending < MAX_PENDING_MIN) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "maxPending must be at least " + MAX_PENDING_MIN);
        }
        if (maxPending > MAX_PENDING_MAX) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "maxPending must be at most " + MAX_PENDING_MAX);
        }
        return maxPending;
    }

    /**
     * Validates the website list against the catalog and returns a normalized
     * (trimmed, deduplicated, order-preserving) list.
     *
     * <p>If the catalog source reports an empty website set, validation fails explicitly.
     * This guarantees the validator cannot silently accept arbitrary websites when the
     * M1 Command Catalog has not yet been wired in.
     */
    public List<String> validateWebsites(List<String> websites) {
        if (websites == null || websites.isEmpty()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "instance must declare at least one website");
        }
        Set<String> known = websiteSource.knownWebsites();
        if (known == null || known.isEmpty()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "OpenCLI command catalog is not available; cannot validate websites");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String website : websites) {
            if (website == null) {
                throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                    "website entry must not be null");
            }
            String trimmed = website.trim();
            if (trimmed.isEmpty()) {
                throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                    "website entry must not be blank");
            }
            if (!known.contains(trimmed)) {
                throw HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.asThrowable(
                    "website is not declared by OpenCLI catalog: " + trimmed);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    /**
     * Validates and normalizes a context id. The trimmed value is returned so the caller
     * persists the canonical form. Allowed when {@code contextId} is {@code null} (an
     * instance may not yet have a connected extension profile); otherwise must be
     * non-blank and within {@link #CONTEXT_ID_MAX_LENGTH} characters.
     */
    public String validateContextId(String contextId) {
        if (contextId == null) {
            return null;
        }
        String trimmed = contextId.trim();
        if (trimmed.isEmpty()) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("contextId must not be blank");
        }
        if (trimmed.length() > CONTEXT_ID_MAX_LENGTH) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "contextId length must be <= " + CONTEXT_ID_MAX_LENGTH);
        }
        return trimmed;
    }

}
