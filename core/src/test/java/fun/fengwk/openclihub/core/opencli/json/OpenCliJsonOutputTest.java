package fun.fengwk.openclihub.core.opencli.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenCliJsonOutput} envelope detection, array unwrapping, and error extraction.
 *
 * @author fengwk
 */
class OpenCliJsonOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldIdentifyEnvelopeCorrectly() throws Exception {
        // null or non-object is not envelope
        assertThat(OpenCliJsonOutput.isEnvelope(null)).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("[]"))).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("\"hello\""))).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("123"))).isFalse();

        // Object missing ok or ok not boolean
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"data\": []}"))).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": \"true\", \"data\": []}"))).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": 1, \"data\": []}"))).isFalse();

        // Business object with boolean ok but neither data nor error
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": true, \"items\": [1, 2]}"))).isFalse();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": false, \"code\": 404}"))).isFalse();

        // Valid envelopes
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": true, \"data\": []}"))).isTrue();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": false, \"error\": {}}"))).isTrue();
        assertThat(OpenCliJsonOutput.isEnvelope(mapper.readTree("{\"ok\": true, \"data\": null}"))).isTrue();
    }

    @Test
    void shouldUnwrapRawArrayDirectly() throws Exception {
        JsonNode root = mapper.readTree("[{\"site\":\"bilibili\",\"name\":\"hot\"}]");
        ArrayNode array = OpenCliJsonOutput.unwrapDataArray(root);
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("site").asText()).isEqualTo("bilibili");
    }

    @Test
    void shouldUnwrapEnvelopeArraySuccessfully() throws Exception {
        JsonNode root = mapper.readTree("{\"ok\": true, \"data\": [{\"site\":\"bilibili\",\"name\":\"hot\"}]}");
        ArrayNode array = OpenCliJsonOutput.unwrapDataArray(root);
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("site").asText()).isEqualTo("bilibili");
    }

    @Test
    void shouldRejectEnvelopeFailure() throws Exception {
        JsonNode root = mapper.readTree("{\"ok\": false, \"error\": {\"code\": \"PLUGIN_ERR\", \"message\": \"failed\"}}");
        assertThatThrownBy(() -> OpenCliJsonOutput.unwrapDataArray(root))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("PLUGIN_ERR: failed");
    }

    @Test
    void shouldRejectMalformedEnvelopeWithoutArrayData() throws Exception {
        // Paginated summary object from normal list
        JsonNode paginated = mapper.readTree("{\"ok\": true, \"data\": {\"commands\": [], \"total\": 0}}");
        assertThatThrownBy(() -> OpenCliJsonOutput.unwrapDataArray(paginated))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected 'data' to be a JSON array");

        // Primitive data
        JsonNode strData = mapper.readTree("{\"ok\": true, \"data\": \"hello\"}");
        assertThatThrownBy(() -> OpenCliJsonOutput.unwrapDataArray(strData))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected 'data' to be a JSON array");

        // Missing data
        JsonNode missingData = mapper.readTree("{\"ok\": true, \"error\": \"some error\"}");
        assertThatThrownBy(() -> OpenCliJsonOutput.unwrapDataArray(missingData))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected 'data' to be a JSON array");
    }

    @Test
    void shouldRejectNonEnvelopeNonArray() throws Exception {
        JsonNode obj = mapper.readTree("{\"key\": \"val\"}");
        assertThatThrownBy(() -> OpenCliJsonOutput.unwrapDataArray(obj))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected JSON array or OpenCLI success envelope");
    }

    @Test
    void shouldExtractErrorMessageAndCode() throws Exception {
        JsonNode errorObj = mapper.readTree("{\"ok\": false, \"error\": {\"code\": \"CMD_NOT_FOUND\", \"message\": \"Not found\"}}");
        assertThat(OpenCliJsonOutput.extractErrorMessage(errorObj)).isEqualTo("CMD_NOT_FOUND: Not found");

        JsonNode codeOnly = mapper.readTree("{\"error\": {\"code\": \"ONLY_CODE\"}}");
        assertThat(OpenCliJsonOutput.extractErrorMessage(codeOnly)).isEqualTo("ONLY_CODE");

        JsonNode msgOnly = mapper.readTree("{\"error\": {\"message\": \"Only message\"}}");
        assertThat(OpenCliJsonOutput.extractErrorMessage(msgOnly)).isEqualTo("Only message");

        JsonNode textError = mapper.readTree("{\"error\": \"string error\"}");
        assertThat(OpenCliJsonOutput.extractErrorMessage(textError)).isEqualTo("string error");

        JsonNode topMsg = mapper.readTree("{\"message\": \"top level message\"}");
        assertThat(OpenCliJsonOutput.extractErrorMessage(topMsg)).isEqualTo("top level message");

        assertThat(OpenCliJsonOutput.extractErrorMessage(null)).isEmpty();
    }

    @Test
    void shouldExtractExitCode() throws Exception {
        JsonNode withExit = mapper.readTree("{\"error\": {\"exitCode\": 42}}");
        assertThat(OpenCliJsonOutput.extractExitCode(withExit)).isEqualTo(42);

        JsonNode withoutExit = mapper.readTree("{\"error\": {\"message\": \"no exit\"}}");
        assertThat(OpenCliJsonOutput.extractExitCode(withoutExit)).isNull();

        assertThat(OpenCliJsonOutput.extractExitCode(null)).isNull();
    }
}
