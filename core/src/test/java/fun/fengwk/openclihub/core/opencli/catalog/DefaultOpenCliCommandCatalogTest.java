package fun.fengwk.openclihub.core.opencli.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.nio.file.Path;
import java.util.Optional;
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
        // resolved command must surface PERSISTENT to M5 for routing decisions.
        assertThat(catalog.findPublicCommand("12306", "login"))
            .isPresent()
            .get()
            .extracting(fun.fengwk.openclihub.core.command.catalog.OpenCliCommand::getSiteSession)
            .isEqualTo(SiteSessionMode.PERSISTENT);
        // Commands without an explicit siteSession must resolve to null so M5 falls
        // back to the OpenCLI default (ephemeral). Use Optional.isPresent plus a direct
        // null check on the unwrapped command because Optional.map() flattens null
        // mapped values back to Optional.empty().
        var bilibili = catalog.findPublicCommand("bilibili", "hot");
        assertThat(bilibili).isPresent();
        assertThat(bilibili.get().getSiteSession()).isNull();
    }

}
