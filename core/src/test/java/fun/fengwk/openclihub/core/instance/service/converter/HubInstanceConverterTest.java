package fun.fengwk.openclihub.core.instance.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceRuntimeDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON shape produced by {@link HubInstanceConverter} and verifies the
 * "no runtime" branch returns zero counters without fabricating runtime fields.
 *
 * <p>The JSON round trip doubles as documentation for the wire shape consumed by the
 * front-end.
 */
class HubInstanceConverterTest {

    private final HubInstanceConverter converter = new HubInstanceConverter();

    @Test
    void shouldReturnAbsentRuntimeWhenSnapshotMissing() {
        // The "no runtime" branch is the most common path pre-M4: counters must be 0 and
        // not fabricate any registered=true state.
        HubInstance domain = sampleDomain();
        var dto = converter.toDTO(domain, null);

        assertThat(dto.getRuntime()).isNotNull();
        assertThat(dto.getRuntime().isRegistered()).isFalse();
        assertThat(dto.getRuntime().getActiveCount()).isZero();
        assertThat(dto.getRuntime().getPendingCount()).isZero();
        assertThat(dto.getRuntime().getDisplayNumber()).isNull();
        assertThat(dto.getRuntime().getVncPort()).isNull();
    }

    @Test
    void shouldMergeSnapshotWhenProvided() {
        // When a snapshot exists the converter must mirror its fields verbatim; this is the
        // extension point M4 will use to expose display/vnc port and queue depth.
        HubInstance domain = sampleDomain();
        HubInstanceRuntimeSnapshot snapshot = new HubInstanceRuntimeSnapshot(
            true, 7, 5900, 2, 3);

        var dto = converter.toDTO(domain, snapshot);

        HubInstanceRuntimeDTO runtime = dto.getRuntime();
        assertThat(runtime.isRegistered()).isTrue();
        assertThat(runtime.getDisplayNumber()).isEqualTo(7);
        assertThat(runtime.getVncPort()).isEqualTo(5900);
        assertThat(runtime.getActiveCount()).isEqualTo(2);
        assertThat(runtime.getPendingCount()).isEqualTo(3);
    }

    @Test
    void shouldSerializeAllScalarFields() throws Exception {
        // JSON shape check: the front-end consumes these field names directly, so any
        // rename must be a deliberate cross-stack change.
        HubInstance domain = sampleDomain();
        var dto = converter.toDTO(domain, HubInstanceRuntimeSnapshot.absent());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        String json = objectMapper.writeValueAsString(dto);

        // The key invariant: no positive counters leak out when the snapshot is absent.
        assertThat(json).contains("\"registered\":false");
        assertThat(json).contains("\"activeCount\":0");
        assertThat(json).contains("\"pendingCount\":0");
        assertThat(json).contains("\"code\":\"instance-01\"");
        assertThat(json).contains("\"state\":\"RUNNING\"");
        assertThat(json).contains("\"bilibili\"");
        assertThat(json).contains("\"proxyMode\":\"CUSTOM\"");
        assertThat(json).contains("\"proxyServer\":\"http://proxy.example:8080\"");
    }

    @Test
    void shouldReturnNullWhenDomainMissing() {
        // Null-in / null-out: the converter is a pure function so callers can chain safely.
        assertThat(converter.toDTO(null, null)).isNull();
    }

    private HubInstance sampleDomain() {
        HubInstance inst = new HubInstance();
        inst.setId("42");
        inst.setCode("instance-01");
        inst.setDisplayName("Instance One");
        inst.setContextId("ctx-1");
        inst.setState(HubInstanceState.RUNNING);
        inst.setWebsites(List.of("bilibili"));
        inst.setMaxPending(5);
        inst.setProxyMode(HubProxyMode.CUSTOM);
        inst.setProxyServer("http://proxy.example:8080");
        inst.setLastErrorMessage(null);
        inst.setStateChangedAt(LocalDateTime.of(2026, 7, 12, 12, 0, 0));
        inst.setCreateTime(LocalDateTime.of(2026, 7, 12, 11, 0, 0));
        inst.setUpdateTime(LocalDateTime.of(2026, 7, 12, 12, 0, 0));
        return inst;
    }

}
