package fun.fengwk.openclihub.core.plugin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.plugin.cli.OpenCliPluginCli;
import fun.fengwk.openclihub.core.plugin.repo.HubPluginSourceRepository;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceDTO;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceStatus;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceUpsertDTO;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    /** The official CLI prints these exact human layouts instead of JSON for an empty list. */
    @Test
    void shouldTreatOfficialEmptyPluginListMessagesAsEmptyList() {
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            pluginCli,
            mock(OpenCliCommandCatalog.class));
        when(pluginCli.run(List.of("list", "-f", "json"))).thenReturn(
            new OpenCliPluginCli.CliResult(
                0,
                "No plugins installed.\nInstall one with: opencli plugin install github:user/repo\n",
                ""),
            new OpenCliPluginCli.CliResult(
                0,
                "  No plugins installed.\n  Install one with: opencli plugin install github:user/repo\n",
                ""));

        assertThat(service.listInstalled()).isEmpty();
        assertThat(service.listInstalled()).isEmpty();
    }

    /** A near match must remain rejected so unrelated successful text cannot be treated as an empty list. */
    @Test
    void shouldRejectNearMatchForOfficialEmptyPluginListMessage() {
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        HubPluginService service = new HubPluginService(
            mock(HubPluginSourceRepository.class),
            pluginCli,
            mock(OpenCliCommandCatalog.class));
        when(pluginCli.run(List.of("list", "-f", "json"))).thenReturn(new OpenCliPluginCli.CliResult(
            0,
            "No plugins installed.\n Install one with: opencli plugin install github:user/repo\n",
            ""));

        assertThatThrownBy(service::listInstalled)
            .hasMessageContaining("Failed to parse opencli plugin list JSON");
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


    /** Empty desiredPlugins must still refresh installed plugins via official update, not install-skip. */
    @Test
    void shouldUpdateInstalledPluginsWhenDesiredListIsEmpty() {
        InMemoryRepo repo = new InMemoryRepo();
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        OpenCliCommandCatalog catalog = mock(OpenCliCommandCatalog.class);
        HubPluginService service = new HubPluginService(repo, pluginCli, catalog);

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("https://github.com/fengwk/my-opencli");
        request.setDesiredPlugins(List.of());
        request.setEnabled(true);
        var created = service.createSource(request);

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
        when(pluginCli.run(List.of("update", "chatgpt-agent")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "updated chatgpt-agent", ""));
        when(pluginCli.run(List.of("list")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "chatgpt-agent @0.1.3", ""));

        var result = service.updateInstalledFromSource(created.getId());

        assertThat(result.getLastStatus()).isEqualTo(HubPluginSourceStatus.SUCCEEDED);
        assertThat(result.getLastResult()).contains("update chatgpt-agent");
        assertThat(result.getLastResult()).doesNotContain("install ");
    }

    /** Explicit desiredPlugins should update only the selected names. */
    @Test
    void shouldUpdateOnlyDesiredPlugins() {
        InMemoryRepo repo = new InMemoryRepo();
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        OpenCliCommandCatalog catalog = mock(OpenCliCommandCatalog.class);
        HubPluginService service = new HubPluginService(repo, pluginCli, catalog);

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:fengwk/my-opencli");
        request.setDesiredPlugins(List.of("chatgpt-agent"));
        request.setEnabled(true);
        var created = service.createSource(request);

        when(pluginCli.run(List.of("update", "chatgpt-agent")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "updated", ""));
        when(pluginCli.run(List.of("list")))
            .thenReturn(new OpenCliPluginCli.CliResult(0, "ok", ""));

        var result = service.updateInstalledFromSource(created.getId());
        assertThat(result.getLastStatus()).isEqualTo(HubPluginSourceStatus.SUCCEEDED);
        assertThat(result.getLastResult()).contains("update chatgpt-agent");
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

    /** All source mutations share one fence: while a sync runs, create/update/delete and other syncs are rejected with PLUGIN_SYNC_BUSY. */
    @Test
    void shouldRejectConcurrentMutationsWhileSyncRuns() throws Exception {
        InMemoryRepo repo = new InMemoryRepo();
        OpenCliPluginCli pluginCli = mock(OpenCliPluginCli.class);
        HubPluginService service = new HubPluginService(
            repo, pluginCli, mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:acme/opencli-plugins");
        request.setEnabled(true);
        var created = service.createSource(request);

        CountDownLatch cliEntered = new CountDownLatch(1);
        CountDownLatch releaseCli = new CountDownLatch(1);
        when(pluginCli.run(List.of("install", request.getSource()))).thenAnswer(invocation -> {
            cliEntered.countDown();
            assertThat(releaseCli.await(2, TimeUnit.SECONDS)).isTrue();
            return new OpenCliPluginCli.CliResult(0, "installed", "");
        });
        when(pluginCli.run(List.of("list"))).thenReturn(new OpenCliPluginCli.CliResult(0, "ok", ""));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HubPluginSourceDTO> sync = pool.submit(() -> service.syncSource(created.getId()));
            assertThat(cliEntered.await(2, TimeUnit.SECONDS)).isTrue();

            HubPluginSourceUpsertDTO update = new HubPluginSourceUpsertDTO();
            update.setName("demo-renamed");
            update.setSource(request.getSource());
            update.setEnabled(true);
            assertBusy(() -> service.updateSource(created.getId(), update));
            assertBusy(() -> service.deleteSource(created.getId()));
            assertBusy(() -> service.syncSource(created.getId()));
            assertBusy(() -> service.updateInstalledFromSource(created.getId()));
            HubPluginSourceUpsertDTO another = new HubPluginSourceUpsertDTO();
            another.setName("other");
            another.setSource("github:acme/other-plugins");
            assertBusy(() -> service.createSource(another));

            releaseCli.countDown();
            assertThat(sync.get(5, TimeUnit.SECONDS).getLastStatus())
                .isEqualTo(HubPluginSourceStatus.SUCCEEDED);
            assertThat(repo.findById(created.getId()).getLastStatus())
                .isEqualTo(HubPluginSourceStatus.SUCCEEDED);
            assertThat(repo.findById(created.getId()).getName()).isEqualTo("demo");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertBusy(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(error -> assertThat(((ThrowableConventionErrorCode) error).getCode())
                .isEqualTo(HubErrorCodes.PLUGIN_SYNC_BUSY.getCode()));
    }

    /** A disabled source must be rejected for both sync and update-installed operations. */
    @Test
    void shouldRejectSyncOfDisabledSource() {
        InMemoryRepo repo = new InMemoryRepo();
        HubPluginService service = new HubPluginService(
            repo, mock(OpenCliPluginCli.class), mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:acme/opencli-plugins");
        request.setEnabled(true);
        var created = service.createSource(request);

        HubPluginSourceUpsertDTO disable = new HubPluginSourceUpsertDTO();
        disable.setName("demo");
        disable.setSource(request.getSource());
        disable.setEnabled(false);
        service.updateSource(created.getId(), disable);

        assertThatThrownBy(() -> service.syncSource(created.getId()))
            .hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.updateInstalledFromSource(created.getId()))
            .hasMessageContaining("disabled");
        assertThat(repo.findById(created.getId()).getLastStatus())
            .isEqualTo(HubPluginSourceStatus.IDLE);
    }

    /** An update that omits the enabled flag must preserve the current value instead of silently re-enabling a disabled source. */
    @Test
    void shouldPreserveEnabledFlagWhenUpdateOmitsIt() {
        InMemoryRepo repo = new InMemoryRepo();
        HubPluginService service = new HubPluginService(
            repo, mock(OpenCliPluginCli.class), mock(OpenCliCommandCatalog.class));

        HubPluginSourceUpsertDTO request = new HubPluginSourceUpsertDTO();
        request.setName("demo");
        request.setSource("github:acme/opencli-plugins");
        request.setEnabled(true);
        var created = service.createSource(request);
        assertThat(created.isEnabled()).isTrue();

        HubPluginSourceUpsertDTO disable = new HubPluginSourceUpsertDTO();
        disable.setName("demo");
        disable.setSource(request.getSource());
        disable.setEnabled(false);
        assertThat(service.updateSource(created.getId(), disable).isEnabled()).isFalse();

        // A partial update without the enabled flag must keep the source disabled.
        HubPluginSourceUpsertDTO rename = new HubPluginSourceUpsertDTO();
        rename.setName("demo-renamed");
        rename.setSource(request.getSource());
        assertThat(service.updateSource(created.getId(), rename).isEnabled()).isFalse();
        assertThat(repo.findById(created.getId()).isEnabled()).isFalse();

        // Create with an omitted flag still defaults to enabled.
        HubPluginSourceUpsertDTO defaulted = new HubPluginSourceUpsertDTO();
        defaulted.setName("fresh");
        defaulted.setSource("github:acme/fresh-plugins");
        assertThat(service.createSource(defaulted).isEnabled()).isTrue();
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
