package fun.fengwk.openclihub.core.opencli.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies catalog parsing against a real-world OpenCLI v1.8.6 manifest fixture.
 *
 * <p>The fixture lives at {@code src/test/resources/opencli/cli-manifest.json} and was
 * extracted from the pinned OpenCLI repository. The test asserts the public surface only
 * contains browser commands, that aliases resolve to canonical keys and that the reserved
 * management commands never match.
 *
 * @author fengwk
 */
class OpenCliCommandCatalogParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/opencli/cli-manifest.json");

    private final OpenCliCommandCatalogParser parser = new OpenCliCommandCatalogParser();

    @Test
    void shouldParseRealFixtureAndExcludeNonBrowserCommands() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);

        // The fixture intentionally mixes browser=true with browser=false entries;
        // the public catalog must only see the former.
        assertThat(index.size()).isPositive();
        assertThat(index.findByKey("github-trending/repos")).isEmpty();
        assertThat(index.findByKey("hackernews/top")).isEmpty();
        assertThat(index.findByKey("wikipedia/search")).isEmpty();
        assertThat(index.findByKey("bilibili/hot")).isPresent();
        assertThat(index.findByKey("chatgpt/image")).isPresent();
    }

    @Test
    void shouldExposeWebsitesFromBrowserCommandsOnly() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);
        assertThat(index.websites()).contains("bilibili", "chatgpt", "douyin");
        assertThat(index.websites()).doesNotContain("github-trending", "hackernews", "wikipedia");
    }

    @Test
    void shouldResolveAliasesToCanonicalKeys() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);
        Optional<OpenCliCommand> aliased = index.findBySiteAndAlias("dianping", "detail");
        assertThat(aliased).isPresent();
        assertThat(aliased.get().getCommandKey()).isEqualTo("dianping/shop");
    }

    @Test
    void shouldNeverMatchReservedManagementCommands() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);
        for (String name : OpenCliReservedManagementCommands.NAMES) {
            // Reserved names must miss for any site even if some CLI version ever lists them.
            assertThat(index.findBySiteAndAlias("bilibili", name)).isEmpty();
            assertThat(index.findBySiteAndAlias(name, "anything")).isEmpty();
        }
    }

    @Test
    void shouldPreserveTypedArgumentMetadataForChatgptImage() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);
        OpenCliCommand image = index.findByKey("chatgpt/image").orElseThrow();
        List<OpenCliCommandArg> args = image.getArgs();
        // prompt must be a required positional string; op must be a string-valued option.
        OpenCliCommandArg prompt = args.stream()
            .filter(a -> "prompt".equals(a.getName()))
            .findFirst().orElseThrow();
        assertThat(prompt.isPositional()).isTrue();
        assertThat(prompt.isRequired()).isTrue();
        assertThat(prompt.getType()).isEqualTo("str");

        OpenCliCommandArg op = args.stream()
            .filter(a -> "op".equals(a.getName()))
            .findFirst().orElseThrow();
        assertThat(op.isPositional()).isFalse();
        assertThat(op.isValueRequired()).isTrue();
    }

    @Test
    void shouldPreserveChoicesForBandMentionsFilter() {
        OpenCliCommandIndex index = parser.parse(FIXTURE);
        OpenCliCommand mentions = index.findByKey("band/mentions").orElseThrow();
        OpenCliCommandArg filter = mentions.getArgs().stream()
            .filter(a -> "filter".equals(a.getName()))
            .findFirst().orElseThrow();
        assertThat(filter.getChoices()).contains("mentioned", "all", "post", "comment");
    }

    @Test
    void shouldFailOnDuplicateCanonicalKeys() {
        String dup = "["
            + "{\"site\":\"a\",\"name\":\"b\",\"browser\":true,\"args\":[]},"
            + "{\"site\":\"a\",\"name\":\"b\",\"browser\":true,\"args\":[]}"
            + "]";
        assertThatThrownBy(() -> parser.parse(dup))
            .isInstanceOf(OpenCliCatalogParseException.class)
            .hasMessageContaining("Duplicate canonical command key");
    }

    @Test
    void shouldFailOnConflictingAliasMappings() {
        String conflict = "["
            + "{\"site\":\"a\",\"name\":\"b\",\"browser\":true,\"aliases\":[\"x\"],\"args\":[]},"
            + "{\"site\":\"a\",\"name\":\"c\",\"browser\":true,\"aliases\":[\"x\"],\"args\":[]}"
            + "]";
        assertThatThrownBy(() -> parser.parse(conflict))
            .isInstanceOf(OpenCliCatalogParseException.class)
            .hasMessageContaining("maps to multiple canonical commands");
    }

    @Test
    void shouldRejectBlankJson() {
        assertThatThrownBy(() -> parser.parse((String) null))
            .isInstanceOf(OpenCliCatalogParseException.class);
        assertThatThrownBy(() -> parser.parse(""))
            .isInstanceOf(OpenCliCatalogParseException.class);
        assertThatThrownBy(() -> parser.parse("   \n  "))
            .isInstanceOf(OpenCliCatalogParseException.class);
    }

}