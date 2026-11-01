package fun.fengwk.openclihub.core.opencli.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
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

    @Test
    void shouldParseSiteSessionWithDefaultsAndFailSafeUnknown() {
        // Missing and blank default to EPHEMERAL; canonical/trim/case parse; unknown -> null.
        OpenCliCommandIndex index = parser.parse("""
            [
              {"site":"a","name":"missing","browser":true,"args":[]},
              {"site":"a","name":"blank","browser":true,"args":[],"siteSession":"   "},
              {"site":"a","name":"empty","browser":true,"args":[],"siteSession":""},
              {"site":"a","name":"canonical-eph","browser":true,"args":[],"siteSession":"ephemeral"},
              {"site":"a","name":"case-trim-eph","browser":true,"args":[],"siteSession":"  EPHEMERAL  "},
              {"site":"a","name":"canonical-pers","browser":true,"args":[],"siteSession":"persistent"},
              {"site":"a","name":"case-trim-pers","browser":true,"args":[],"siteSession":" Persistent "},
              {"site":"a","name":"unknown","browser":true,"args":[],"siteSession":"sticky"}
            ]
            """);
        assertThat(index.findByKey("a/missing"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL));
        assertThat(index.findByKey("a/blank"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL));
        assertThat(index.findByKey("a/empty"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL));
        assertThat(index.findByKey("a/canonical-eph"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL));
        assertThat(index.findByKey("a/case-trim-eph"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.EPHEMERAL));
        assertThat(index.findByKey("a/canonical-pers"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.PERSISTENT));
        assertThat(index.findByKey("a/case-trim-pers"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isEqualTo(SiteSessionMode.PERSISTENT));
        assertThat(index.findByKey("a/unknown"))
            .hasValueSatisfying(c -> assertThat(c.getSiteSession()).isNull());
    }

    @Test
    void shouldParseEnvelopeSuccessWithOver25CommandsAndRepeatableLifecycleFields() {
        StringBuilder json = new StringBuilder("{\"ok\": true, \"data\": [");
        for (int i = 1; i <= 30; i++) {
            if (i > 1) {
                json.append(",");
            }
            json.append(String.format("""
                {
                  "site": "site%d",
                  "name": "cmd%d",
                  "browser": true,
                  "description": "Command %d",
                  "lifecycle": "post-run",
                  "args": [
                    {
                      "name": "arg%d",
                      "type": "string",
                      "repeatable": true
                    }
                  ]
                }
                """, i, i, i, i));
        }
        json.append("]}");

        OpenCliCommandIndex index = parser.parse(json.toString());
        // Verify full catalog (>25) is parsed without truncation
        assertThat(index.size()).isEqualTo(30);
        // Verify first and last entries exist
        assertThat(index.findByKey("site1/cmd1")).isPresent();
        assertThat(index.findByKey("site30/cmd30")).isPresent();
        // Verify arguments survived despite repeatable and lifecycle fields
        OpenCliCommand first = index.findByKey("site1/cmd1").orElseThrow();
        assertThat(first.getArgs()).hasSize(1);
        assertThat(first.getArgs().get(0).getName()).isEqualTo("arg1");
    }

    @Test
    void shouldRejectEnvelopeFailure() {
        String failureJson = """
            {
              "ok": false,
              "error": {
                "code": "REGISTRY_ERROR",
                "message": "Failed to load manifest"
              }
            }
            """;
        assertThatThrownBy(() -> parser.parse(failureJson))
            .isInstanceOf(OpenCliCatalogParseException.class)
            .hasMessageContaining("REGISTRY_ERROR: Failed to load manifest");
    }

    @Test
    void shouldRejectMalformedEnvelopeSuchAsPaginatedListSummary() {
        // Normal list returns a summary object under data instead of full manifest array
        String paginatedSummary = """
            {
              "ok": true,
              "data": {
                "commands": [
                  {"command": "bilibili hot", "description": "Hot", "access": "read"}
                ],
                "total": 100,
                "nextOffset": 25
              }
            }
            """;
        assertThatThrownBy(() -> parser.parse(paginatedSummary))
            .isInstanceOf(OpenCliCatalogParseException.class)
            .hasMessageContaining("Expected 'data' to be a JSON array");
    }

    @Test
    void shouldRejectMalformedEnvelopeMissingOkOrNotBoolean() {
        String missingOk = "{\"data\": [{\"site\": \"a\", \"name\": \"b\", \"browser\": true, \"args\": []}]}";
        assertThatThrownBy(() -> parser.parse(missingOk))
            .isInstanceOf(OpenCliCatalogParseException.class);

        String okString = "{\"ok\": \"true\", \"data\": [{\"site\": \"a\", \"name\": \"b\", \"browser\": true, \"args\": []}]}";
        assertThatThrownBy(() -> parser.parse(okString))
            .isInstanceOf(OpenCliCatalogParseException.class);
    }

}
