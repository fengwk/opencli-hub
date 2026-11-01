package fun.fengwk.openclihub.core.opencli.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Utility for parsing and unwrapping OpenCLI 2 JSON envelopes.
 *
 * <p>OpenCLI 2 standardized command outputs to a top-level envelope:
 * <ul>
 *   <li>Success: {@code {"ok": true, "data": ...}}</li>
 *   <li>Failure: {@code {"ok": false, "error": {"code": "...", "message": "...", "hint": "...", "exitCode": ...}}}</li>
 * </ul>
 *
 * @author fengwk
 */
public final class OpenCliJsonOutput {

    private OpenCliJsonOutput() {
    }

    /**
     * Determines whether the given JSON node matches an OpenCLI envelope.
     * An object is recognized as an envelope if it has a boolean {@code ok} field
     * AND at least one of {@code data} or {@code error} fields.
     */
    public static boolean isEnvelope(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode okNode = node.get("ok");
        if (okNode == null || !okNode.isBoolean()) {
            return false;
        }
        return node.has("data") || node.has("error");
    }

    /**
     * Unwraps array data from either a raw JSON array or an OpenCLI 2 success envelope
     * ({@code {"ok": true, "data": [...]}}).
     *
     * <p>Rejects failure envelopes ({@code ok: false}) and malformed envelopes (missing or
     * non-array {@code data}, such as paginated summary objects).
     *
     * @param root parsed JSON root
     * @return unwrapped array node
     * @throws IllegalArgumentException if the root is not an array and cannot be unwrapped
     */
    public static ArrayNode unwrapDataArray(JsonNode root) {
        if (root == null) {
            throw new IllegalArgumentException("JSON root is null");
        }
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        if (isEnvelope(root)) {
            if (root.get("ok").asBoolean()) {
                JsonNode dataNode = root.get("data");
                if (dataNode != null && dataNode.isArray()) {
                    return (ArrayNode) dataNode;
                }
                throw new IllegalArgumentException("Expected 'data' to be a JSON array");
            } else {
                String error = extractErrorMessage(root);
                throw new IllegalArgumentException(
                    error.isBlank() ? "OpenCLI returned failure envelope" : error);
            }
        }
        throw new IllegalArgumentException(
            "Expected JSON array or OpenCLI success envelope with array data");
    }

    /**
     * Extracts an error code and message from an OpenCLI failure envelope or error node.
     * Format: {@code "CODE: message"} or {@code "message"} or {@code "CODE"}.
     */
    public static String extractErrorMessage(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode errorNode = root.get("error");
        if (errorNode != null && errorNode.isObject()) {
            String code = text(errorNode, "code");
            String message = text(errorNode, "message");
            if (!code.isBlank() && !message.isBlank()) {
                return code + ": " + message;
            }
            if (!code.isBlank()) {
                return code;
            }
            if (!message.isBlank()) {
                return message;
            }
        } else if (errorNode != null && errorNode.isTextual() && !errorNode.asText().isBlank()) {
            return errorNode.asText().trim();
        }
        String topMessage = text(root, "message");
        if (!topMessage.isBlank()) {
            return topMessage;
        }
        return "";
    }

    /**
     * Extracts {@code exitCode} from {@code error.exitCode} if present.
     */
    public static Integer extractExitCode(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode errorNode = root.get("error");
        if (errorNode != null && errorNode.isObject()) {
            JsonNode exitCodeNode = errorNode.get("exitCode");
            if (exitCodeNode != null && exitCodeNode.isIntegralNumber()) {
                return exitCodeNode.asInt();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(fieldName);
        return child != null && child.isTextual() ? child.asText().trim() : "";
    }
}
