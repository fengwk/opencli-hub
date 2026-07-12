package fun.fengwk.openclihub.core.command.validator;

/**
 * Recognized OpenCLI argument value types.
 *
 * <p>The Hub parser recognizes every spelling that appears in the pinned
 * {@code cli-manifest.json} (string/str, int, float/number, bool/boolean). Anything
 * else falls back to {@link #STRING} so Hub never silently rejects an unknown type.
 *
 * @author fengwk
 */
public enum OpenCliArgumentType {

    STRING,
    INT,
    FLOAT,
    BOOLEAN;

    /**
     * Resolve a manifest {@code type} string to an enum, defaulting to {@link #STRING}.
     */
    public static OpenCliArgumentType of(String type) {
        if (type == null) {
            return STRING;
        }
        switch (type.trim().toLowerCase()) {
            case "int":
            case "integer":
                return INT;
            case "float":
            case "double":
            case "number":
                return FLOAT;
            case "bool":
            case "boolean":
                return BOOLEAN;
            case "string":
            case "str":
            case "text":
            default:
                return STRING;
        }
    }

    /**
     * Whether the supplied raw string is parseable to this type.
     */
    public boolean accepts(String value) {
        if (value == null) {
            return false;
        }
        switch (this) {
            case BOOLEAN:
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case INT:
                try {
                    Long.parseLong(value.trim());
                    return true;
                } catch (NumberFormatException ex) {
                    return false;
                }
            case FLOAT:
                try {
                    Double.parseDouble(value.trim());
                    return true;
                } catch (NumberFormatException ex) {
                    return false;
                }
            case STRING:
            default:
                return true;
        }
    }

}
