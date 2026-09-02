package fun.fengwk.openclihub.core.opencli.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the public {@link OpenCliCommandCatalog} contract via the default in-memory
 * implementation backed by a file source.
 *
 * @author fengwk
 */
class DefaultOpenCliCommandCatalogTest {

    private static final Path FIXTURE = Path.of("src/test/resources/opencli/cli-manifest.json");

    private OpenCliCommandCatalog newCatalog() {
        return new DefaultOpenCliCommandCatalog(new FileOpenCliCatalogSource(FIXTURE));
    }

    @Test
    void shouldListOnlyBrowserCommandsAndExcludeManagement() {
        OpenCliCommandCatalog catalog = newCatalog();
        // bilibili/hot is a browser command; list must contain it.
        assertThat(catalog.findPublicCommand("bilibili", "hot")).isPresent();
        // management commands never match even when used as a site or alias.
        for (String reserved : OpenCliReservedManagementCommands.NAMES) {
            assertThat(catalog.findPublicCommand(reserved, "anything")).isEmpty();
            assertThat(catalog.findPublicCommand("bilibili", reserved)).isEmpty();
        }
    }

    @Test
    void shouldResolveAliasThroughCanonicalName() {
        OpenCliCommandCatalog catalog = newCatalog();
        Optional<fun.fengwk.openclihub.core.command.catalog.OpenCliCommand> aliased =
            catalog.findPublicCommand("dianping", "detail");
        assertThat(aliased).isPresent();
        assertThat(aliased.get().getCommandKey()).isEqualTo("dianping/shop");
    }

    @Test
    void shouldExposeWebsitesForRouting() {
        OpenCliCommandCatalog catalog = newCatalog();
        assertThat(catalog.listWebsites()).contains("bilibili", "chatgpt", "dianping");
        assertThat(catalog.containsWebsite("bilibili")).isTrue();
        assertThat(catalog.containsWebsite("github-trending")).isFalse();
        assertThat(catalog.containsWebsite(null)).isFalse();
    }

    @Test
    void shouldExposePersistentSiteSession() {
        OpenCliCommandCatalog catalog = newCatalog();
        // 12306/login declares siteSession=persistent in the pinned manifest, so the
        // resolved command must surface PERSISTENT to the routing layer.
        assertThat(catalog.findPublicCommand("12306", "login"))
            .isPresent()
            .get()
            .extracting(fun.fengwk.openclihub.core.command.catalog.OpenCliCommand::getSiteSession)
            .isEqualTo(SiteSessionMode.PERSISTENT);
        // Commands without an explicit siteSession resolve to EPHEMERAL, matching the
        // OpenCLI runtime default (`resolveSiteSession: ?? 'ephemeral'`).
        var bilibili = catalog.findPublicCommand("bilibili", "hot");
        assertThat(bilibili).isPresent();
        assertThat(bilibili.get().getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL);
    }

    /**
     * A failed reload must leave the previous snapshot fully served: the catalog builds a
     * fresh index and atomically swaps it, so a broken source can never clear the cache into
     * an empty fail-open state.
     */
    @Test
    void shouldKeepServingPreviousSnapshotWhenReloadFails() throws IOException {
        AtomicInteger opens = new AtomicInteger();
        OpenCliCatalogSource flakySource = new OpenCliCatalogSource() {
            @Override
            public InputStream open() throws IOException {
                if (opens.getAndIncrement() == 0) {
                    return Files.newInputStream(FIXTURE);
                }
                throw new IOException("manifest temporarily unavailable");
            }
        };
        DefaultOpenCliCommandCatalog catalog = new DefaultOpenCliCommandCatalog(flakySource);
        assertThat(catalog.findPublicCommand("bilibili", "hot")).isPresent();

        assertThatThrownBy(catalog::reload)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to load OpenCLI catalog");

        // The previous snapshot must still be served — no empty window after the failure.
        assertThat(catalog.findPublicCommand("bilibili", "hot")).isPresent();
        assertThat(catalog.listPublicCommands()).isNotEmpty();
        assertThat(catalog.listWebsites()).contains("bilibili");
    }

}
