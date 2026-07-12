package fun.fengwk.openclihub.core.command.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.opencli.catalog.DefaultOpenCliCommandCatalog;
import fun.fengwk.openclihub.core.opencli.catalog.FileOpenCliCatalogSource;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for the argv validator: type coercion, choices, required/valueRequired
 * flags, positional vs named options, alias canonicalization, reserved argument rejection
 * and normalized argv reconstruction. All paths run against the real cli-manifest.json
 * fixture from {@code src/test/resources/opencli/cli-manifest.json}.
 *
 * @author fengwk
 */
class OpenCliArgvValidatorTest {

    private static final Path FIXTURE = Path.of("src/test/resources/opencli/cli-manifest.json");

    private OpenCliArgvValidator validator;

    @BeforeEach
    void setUp() {
        OpenCliCommandCatalog catalog = new DefaultOpenCliCommandCatalog(
            new FileOpenCliCatalogSource(FIXTURE));
        validator = new OpenCliArgvValidator(catalog);
    }

    @Test
    void shouldNormalizeBilibiliHotWithIntLimit() {
        NormalizedOpenCliArgv normalized = validator.validate(
            List.of("bilibili", "hot", "--limit", "5"));
        assertThat(normalized.getCanonicalKey()).isEqualTo("bilibili/hot");
        assertThat(normalized.getNamedValue("limit")).isEqualTo("5");
        assertThat(normalized.getNormalizedArgv())
            .containsExactly("bilibili", "hot", "--limit", "5");
    }

    @Test
    void shouldAcceptInlineEqualsForm() {
        NormalizedOpenCliArgv normalized = validator.validate(
            List.of("bilibili", "hot", "--limit=10"));
        assertThat(normalized.getNamedValue("limit")).isEqualTo("10");
        assertThat(normalized.getNormalizedArgv())
            .containsExactly("bilibili", "hot", "--limit", "10");
    }

    @Test
    void shouldResolveAliasToCanonicalCommand() {
        NormalizedOpenCliArgv normalized = validator.validate(
            List.of("dianping", "detail", "12345"));
        assertThat(normalized.getCanonicalKey()).isEqualTo("dianping/shop");
        assertThat(normalized.getNormalizedArgv())
            .containsExactly("dianping", "shop", "12345");
    }

    @Test
    void shouldRejectReservedProfileOption() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--profile", "ctx")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT));
    }

    @Test
    void shouldRejectReservedFormatWithInlineEquals() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--format=json")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT));
    }

    @Test
    void shouldRejectReservedShortOption() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "-f", "json")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT));
    }

    @Test
    void shouldRejectUnknownNamedOption() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--made-up", "x")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_NOT_ALLOWED));
    }

    @Test
    void shouldRejectWrongTypeForInt() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--limit", "abc")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_INVALID));
    }

    @Test
    void shouldRejectChoiceViolation() {
        assertThatThrownBy(() -> validator.validate(
            List.of("band", "mentions", "--filter", "unknown")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_INVALID));
    }

    @Test
    void shouldAcceptBandMentionsWithChoice() {
        NormalizedOpenCliArgv normalized = validator.validate(
            List.of("band", "mentions", "--filter", "mentioned", "--limit", "5", "--unread"));
        assertThat(normalized.getNamedValue("filter")).isEqualTo("mentioned");
        assertThat(normalized.getNamedValue("limit")).isEqualTo("5");
        // --unread is a boolean flag without an explicit value, validator must default to "true".
        assertThat(normalized.getNamedValue("unread")).isEqualTo("true");
        assertThat(normalized.getNormalizedArgv()).containsExactly(
            "band", "mentions", "--filter", "mentioned", "--limit", "5", "--unread", "true");
    }

    @Test
    void shouldRejectManagementCommandInvocation() {
        for (String reserved : fun.fengwk.openclihub.core.opencli.catalog.OpenCliReservedManagementCommands.NAMES) {
            assertThatThrownBy(() -> validator.validate(
                List.of("bilibili", reserved)))
                .isInstanceOf(OpenCliArgvValidationException.class);
            assertThatThrownBy(() -> validator.validate(
                List.of(reserved, "x")))
                .isInstanceOf(OpenCliArgvValidationException.class);
        }
    }

    @Test
    void shouldRejectNonPublicCommand() {
        assertThatThrownBy(() -> validator.validate(
            List.of("github-trending", "repos")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND));
    }

    @Test
    void shouldRejectTooShortArgv() {
        assertThatThrownBy(() -> validator.validate(List.of("bilibili")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_INVALID));
    }

    @Test
    void shouldRejectMissingRequiredPositional() {
        // google/search requires positional `keyword`.
        assertThatThrownBy(() -> validator.validate(List.of("google", "search")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_INVALID));
    }

    @Test
    void shouldAcceptChatgptImageWithRequiredPositionalAndOptionalNamed() {
        NormalizedOpenCliArgv normalized = validator.validate(
            List.of("chatgpt", "image", "a cat", "--op", "/tmp/out", "--timeout", "120"));
        assertThat(normalized.getPositionalValues()).containsExactly("a cat");
        assertThat(normalized.getNamedValue("op")).isEqualTo("/tmp/out");
        assertThat(normalized.getNamedValue("timeout")).isEqualTo("120");
        assertThat(normalized.getNormalizedArgv()).containsExactly(
            "chatgpt", "image", "a cat", "--op", "/tmp/out", "--timeout", "120");
    }

    @Test
    void shouldRejectPositionalOverflow() {
        // taobao/detail only declares a single positional `id`.
        assertThatThrownBy(() -> validator.validate(
            List.of("taobao", "detail", "id1", "id2")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_ARGUMENT_INVALID));
    }

    @Test
    void shouldRejectEmptyStringToken() {
        List<String> argv = Arrays.asList("bilibili", "hot", "");
        assertThatThrownBy(() -> validator.validate(argv))
            .isInstanceOf(OpenCliArgvValidationException.class);
    }

    @Test
    void shouldRejectLiteralDoubleDashSeparator() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--", "anything")))
            .isInstanceOf(OpenCliArgvValidationException.class);
    }

    @Test
    void shouldRejectShortUnknownOption() {
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "-x", "foo")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT));
    }

    @Test
    void shouldRejectReservedInlineValue() {
        // The value token itself must not be a reserved arg either, e.g. `--profile --format json`.
        assertThatThrownBy(() -> validator.validate(
            List.of("bilibili", "hot", "--limit", "--format")))
            .isInstanceOf(OpenCliArgvValidationException.class)
            .satisfies(ex -> assertThat(((OpenCliArgvValidationException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT));
    }

}