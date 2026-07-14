package fun.fengwk.openclihub.share.util;

import java.util.UUID;
import java.util.regex.Pattern;

/** Utilities for current UUID identifiers and migrated positive-long identifiers. */
public final class HubIds {

    private static final Pattern LEGACY_POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");

    private HubIds() {
    }

    public static boolean isSupported(String value) {
        return isCanonicalUuid(value) || isLegacyPositiveLong(value);
    }

    public static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isLegacyPositiveLong(String value) {
        if (value == null || !LEGACY_POSITIVE_LONG.matcher(value).matches()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

}
