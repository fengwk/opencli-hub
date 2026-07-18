package fun.fengwk.openclihub.core.plugin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.plugin.cli.OpenCliPluginCli;
import fun.fengwk.openclihub.core.plugin.repo.HubPluginSourceRepository;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceStatus;
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

    /** GitHub source forms must compile to the official sub-plugin grammar without malformed URL suffixes. */
    @Test
    void shouldJoinGitHubSourceAndPluginNames() {
        assertThat(HubPluginService.joinSourceAndPlugin("github:acme/plugins", "weather"))
            .isEqualTo("github:acme/plugins/weather");
        assertThat(HubPluginService.joinSourceAndPlugin("github:acme/plugins/weather", "weather"))
            .isEqualTo("github:acme/plugins/weather");
        assertThat(HubPluginService.joinSourceAndPlugin("https://github.com/acme/plugins", "weather"))
            .isEqualTo("github:acme/plugins/weather");
        assertThat(HubPluginService.joinSourceAndPlugin("https://github.com/acme/plugins.git", "weather"))
            .isEqualTo("github:acme/plugins/weather");
    }

    /** Generic repository URLs cannot express a selected monorepo child in the official plugin CLI grammar. */
    @Test
    void shouldRejectGenericSourceWhenSelectingSubPlugin() {
        assertThatThrownBy(() -> HubPluginService.joinSourceAndPlugin(
            "https://git.example.com/team/plugins.git", "crm"))
            .hasMessageContaining("desiredPlugins requires");
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

    /** Source validation must fail before persistence instead of leaving an unsyncable generic source. */
    @Test
    void shouldRejectGenericSourceWithDesiredPlugins() {
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            mock(OpenCliPluginCli.class),
            mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("generic");
        request.setSource("https://git.example.com/team/plugins.git");
        request.setDesiredPlugins(List.of("crm"));

        assertThatThrownBy(() -> service.createSource(request))
            .hasMessageContaining("desiredPlugins requires");
    }

    /** Installed plugins must come from the CLI's JSON protocol, not human table headings. */
    @Test
    void shouldListInstalledPluginsFromJson() {
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            pluginCli,
            mock(OpenCliCommandCatalog.class));
        when(pluginCli.run(List.of("list", "-f", "json"))).thenReturn(new OpenCliPluginCli.CliResult(
            0,
            """
                [{
                  "name":"chatgpt-agent",
                  "version":"0.1.2",
                  "description":"Protocol stream",
                  "commands":["ask"]
                }]
                """,
            ""));

        var installed = service.listInstalled();

        assertThat(installed).hasSize(1);
        assertThat(installed.get(0).getName()).isEqualTo("chatgpt-agent");
        assertThat(installed.get(0).getRaw()).isEqualTo("chatgpt-agent @0.1.2 — Protocol stream (ask)");
    }

    /** A malformed successful CLI payload must fail closed rather than appearing as fabricated plugins. */
    @Test
    void shouldRejectMalformedInstalledPluginJson() {
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            pluginCli,
            mock(OpenCliCommandCatalog.class));
        when(pluginCli.run(List.of("list", "-f", "json")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "not-json", ""));

        assertThatThrownBy(service::listInstalled)
            .hasMessageContaining("Failed to parse opencli plugin list JSON");
    }

    /** A post-install catalog failure must not leave the source permanently stuck in SYNCING. */
    @Test
    void shouldMarkSourceFailedWhenCatalogReloadFails() {
        InMemoryRepo repo = new InMemoryRepo();
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        OpenCliCommandCatalog catalog = mock(OpenCliCommandCatalog.class);
        HubPluginService service = new HubPluginService(repo, pluginCli, catalog);

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:acme/opencli-plugins");
        request.setDesiredPlugins(List.of());
        request.setEnabled(true);
        var created = service.createSource(request);

        when(pluginCli.run(List.of("install", request.getSource())))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "installed", ""));
        when(pluginCli.run(List.of("list")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "demo", ""));
        doThrow(new IllegalStateException("invalid manifest")).when(catalog).reload();

        assertThatThrownBy(() -> service.syncSource(created.getId()))
            .hasMessageContaining("invalid manifest");
        assertThat(repo.findById(created.getId()).getLastStatus()).isEqualTo(HubPluginSourceStatus.FAILED);
        assertThat(repo.findById(created.getId()).getLastError()).contains("invalid manifest");
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
