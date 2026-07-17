package fun.fengwk.openclihub.core.plugin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.plugin.cli.OpenCliPluginCli;
import fun.fengwk.openclihub.core.plugin.repo.HubPluginSourceRepository;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceUpsertDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubPluginServiceTest {

    /** Invalid official source strings must fail closed before any CLI process is started. */
    @Test
    void shouldRejectInvalidPluginSourceFormat() {
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            mock(OpenCliPluginCli.class),
            mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("bad");
        request.setSource("ftp://example.com/plugin");

        assertThatThrownBy(() -> service.createSource(request))
            .hasMessageContaining("source must be");
    }

    /** Monorepo install sources must append the sub-plugin once and keep already-qualified sources stable. */
    @Test
    void shouldJoinSourceAndPluginNamesIdempotently() {
        assertThat(HubPluginService.joinSourceAndPlugin("github:acme/plugins", "weather"))
            .isEqualTo("github:acme/plugins/weather");
        assertThat(HubPluginService.joinSourceAndPlugin("github:acme/plugins/weather", "weather"))
            .isEqualTo("github:acme/plugins/weather");
        assertThat(HubPluginService.joinSourceAndPlugin("https://git.example.com/team/plugins.git", "crm"))
            .isEqualTo("https://git.example.com/team/plugins.git/crm");
    }

    /** Desired plugin names are normalized and empty entries are dropped. */
    @Test
    void shouldNormalizeDesiredPluginNames() {
        InMemoryRepo repo = new InMemoryRepo();
        HubPluginService service = new HubPluginService(
            repo,
            mock(OpenCliPluginCli.class),
            mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:acme/opencli-plugins");
        request.setDesiredPlugins(List.of(" alpha ", "", "beta"));
        request.setEnabled(true);

        var created = service.createSource(request);
        assertThat(created.getDesiredPlugins()).containsExactly("alpha", "beta");
        assertThat(repo.saved).hasSize(1);
    }

    private static final class InMemoryRepo implements HubPluginSourceRepository {
        private final java.util.Map<String, fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource> saved =
            new java.util.LinkedHashMap<>();

        @Override
        public java.util.List<fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource> listAll() {
            return new java.util.ArrayList<>(saved.values());
        }

        @Override
        public fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource findById(String id) {
            return saved.get(id);
        }

        @Override
        public fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource findByName(String name) {
            return saved.values().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public boolean add(fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource source) {
            saved.put(source.getId(), source);
            return true;
        }

        @Override
        public boolean update(fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource source) {
            saved.put(source.getId(), source);
            return true;
        }

        @Override
        public boolean deleteById(String id) {
            return saved.remove(id) != null;
        }
    }

}
