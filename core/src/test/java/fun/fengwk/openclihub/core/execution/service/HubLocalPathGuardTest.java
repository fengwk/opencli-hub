package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.service.HubLocalPathGuard.CallerTokenKind;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link HubLocalPathGuard}: caller argv classification (absolute,
 * relative, workdir-relative existing files, file URI, URL, prompt, traversal, symlink,
 * prefix collision) and the final-argv defensive containment checks.
 */
class HubLocalPathGuardTest {

    @TempDir
    Path tempDir;

    private Path resourceRoot;
    private Path workdir;
    private HubLocalPathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        resourceRoot = tempDir.resolve("resources");
        workdir = tempDir.resolve("workdir");
        Files.createDirectories(resourceRoot);
        Files.createDirectories(workdir);

        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getResource().setRootDir(resourceRoot.toString());
        properties.getOpencli().setWorkdir(workdir.toString());
        guard = new HubLocalPathGuard(properties);
    }

    // ---- caller argv classification --------------------------------------------

    @Test
    void shouldClassifyWellFormedVirtualPathAsVirtualResource() {
        assertThat(guard.classifyCallerToken("/resources/2026-08-12/upload-x/report.pdf"))
            .isEqualTo(CallerTokenKind.VIRTUAL_RESOURCE);
    }

    /**
     * A well-formed virtual path that does not exist on disk is still a virtual resource
     * token: existence is the caller's (HubResourceService) concern and must surface as
     * RESOURCE_NOT_FOUND, not as a local-path rejection.
     */
    @Test
    void shouldClassifyMissingVirtualFileAsVirtualResource() {
        assertThat(guard.classifyCallerToken("/resources/2099-01-01/upload-x/missing.pdf"))
            .isEqualTo(CallerTokenKind.VIRTUAL_RESOURCE);
    }

    @Test
    void shouldRejectAbsoluteLocalPath() {
        assertThatThrownBy(() -> guard.classifyCallerToken("/etc/passwd"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * The stable protocol only admits virtual {@code /resources/...} references: even a
     * real absolute path that happens to point inside the data volume must be refused.
     */
    @Test
    void shouldRejectRealAbsolutePathInsideResourceRoot() {
        Path real = resourceRoot.resolve("2026-08-12").resolve("upload-x").resolve("f.txt");
        assertThatThrownBy(() -> guard.classifyCallerToken(real.toString()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    @Test
    void shouldRejectWindowsAbsolutePath() {
        assertThatThrownBy(() -> guard.classifyCallerToken("C:\\Windows\\evil.exe"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        assertThatThrownBy(() -> guard.classifyCallerToken("c:/temp/x.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectTildePaths() {
        assertThatThrownBy(() -> guard.classifyCallerToken("~/secret.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        assertThatThrownBy(() -> guard.classifyCallerToken("~"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectExplicitTraversal() {
        for (String token : List.of(
            "../etc/passwd", "./x.txt", "a/../../etc", "..", ".", "..\\..\\etc\\passwd")) {
            assertThatThrownBy(() -> guard.classifyCallerToken(token))
                .as("traversal token: %s", token)
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        }
    }

    /**
     * Prefix collision: {@code resources-evil/...} is not a virtual {@code /resources/...}
     * path and, when nothing exists under the workdir, it is ordinary slashed text — it
     * must pass through instead of being treated as a resource reference or a path.
     */
    @Test
    void shouldPassResourcePrefixCollisionWhenNotExisting() {
        assertThat(guard.classifyCallerToken("resources-evil/x.txt"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
        assertThat(guard.classifyCallerToken("resources-evil"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
    }

    /**
     * Slashed relative text is not a path by itself: {@code foo/bar} passes unless it
     * actually exists relative to the configured workdir, while a backslash traversal
     * stays forbidden.
     */
    @Test
    void shouldPassRelativePathWithSeparatorWhenNotExisting() {
        assertThat(guard.classifyCallerToken("foo/bar.txt"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
        assertThat(guard.classifyCallerToken("2026/08/12"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
    }

    @Test
    void shouldRejectExistingWorkdirFile() throws IOException {
        Files.writeString(workdir.resolve("note.txt"), "hello");
        assertThatThrownBy(() -> guard.classifyCallerToken("note.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        // A non-existent single-word value is an ordinary prompt.
        assertThat(guard.classifyCallerToken("missing.txt"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
    }

    /**
     * A nested relative path that really exists under the workdir is a local file
     * reference and must be refused, even though plain slashed text passes.
     */
    @Test
    void shouldRejectExistingWorkdirNestedRelativePath() throws IOException {
        Files.createDirectories(workdir.resolve("foo"));
        Files.writeString(workdir.resolve("foo").resolve("bar"), "data");
        assertThatThrownBy(() -> guard.classifyCallerToken("foo/bar"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        // An existing directory is a path too.
        assertThatThrownBy(() -> guard.classifyCallerToken("foo"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * The relative existence probe must anchor at the configured OpenCLI workdir, not at
     * the JVM working directory: {@code pom.xml} exists in the module basedir (the JVM
     * cwd during tests) but not in the configured workdir, so it must pass through.
     */
    @Test
    void shouldResolveRelativeExistenceAgainstConfiguredWorkdirNotJvmCwd() {
        assertThat(Path.of("pom.xml").toAbsolutePath()).exists();
        assertThat(guard.classifyCallerToken("pom.xml"))
            .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
    }

    @Test
    void shouldRejectFileUris() {
        for (String token : List.of("file:///etc/passwd", "file:relative.txt", "FILE:///tmp/x")) {
            assertThatThrownBy(() -> guard.classifyCallerToken(token))
                .as("file URI token: %s", token)
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        }
    }

    @Test
    void shouldPassUrlsAndOrdinaryPrompts() {
        for (String token : List.of(
            "https://example.com/a/b",
            "http://x/y?q=1",
            "data:text/plain,hello",
            "mailto:someone@example.com",
            "你好，帮我写一首诗",
            "请解释 /etc/passwd 的格式",
            "搜索 foo/bar 相关内容",
            "hello world",
            "5")) {
            assertThat(guard.classifyCallerToken(token))
                .as("non-path token: %s", token)
                .isEqualTo(CallerTokenKind.ORDINARY_VALUE);
        }
    }

    @Test
    void shouldRejectVirtualPathWithTraversal() {
        assertThatThrownBy(() -> guard.classifyCallerToken(
            "/resources/2026-08-12/upload-x/../evil.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    @Test
    void shouldRejectVirtualPathMissingGroupFileSegment() {
        assertThatThrownBy(() -> guard.classifyCallerToken("/resources/2026-08-12/upload-x"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A virtual path whose chain contains a symbolic link escaping the root must be
     * rejected as a local-path attempt.
     */
    @Test
    void shouldRejectVirtualPathWithSymlinkEscape() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("upload-x");
        Files.createDirectories(group);
        Files.writeString(tempDir.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(group.resolve("link.txt"), tempDir.resolve("secret.txt"));

        assertThatThrownBy(() -> guard.classifyCallerToken(
            "/resources/2026-08-12/upload-x/link.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    // ---- final argv defensive containment --------------------------------------

    @Test
    void shouldAcceptFinalArgvWithHubInjectedPathsUnderRoot() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("execution-1");
        Files.createDirectories(group);
        Files.writeString(group.resolve("input.txt"), "data");
        // Existing substituted input + non-existent managed output under an existing group.
        assertThatCode(() -> guard.assertFinalArgv(List.of(
            "--profile", "ctx-a",
            "bilibili", "submit",
            group.resolve("input.txt").toString(),
            "--output", group.resolve("out.json").toString(),
            "--format", "json")))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectFinalArgvWithAbsolutePathOutsideRoot() throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "x");
        assertThatThrownBy(() -> guard.assertFinalArgv(List.of(
            "bilibili", "submit", outside.toString())))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    @Test
    void shouldRejectFinalArgvWithWindowsPath() {
        assertThatThrownBy(() -> guard.assertFinalArgv(List.of(
            "bilibili", "submit", "C:\\Windows\\evil.exe")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    @Test
    void shouldAcceptFinalArgvWithoutAbsolutePaths() {
        assertThatCode(() -> guard.assertFinalArgv(List.of(
            "--profile", "ctx-a",
            "chatgpt", "ask", "你好", "https://example.com/a",
            "--format", "json")))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectManagedOutputOutsideRootWhenNotYetCreated() {
        Path groupOutside = tempDir.resolve("outside-group");
        assertThatThrownBy(() -> guard.assertContainedInResourceRoot(
            groupOutside.resolve("out.json")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A non-existent managed output whose nearest existing parent is the (existing)
     * execution group inside the root is accepted.
     */
    @Test
    void shouldAcceptNonExistentManagedOutputUnderExistingGroup() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("execution-9");
        Files.createDirectories(group);
        assertThatCode(() -> guard.assertContainedInResourceRoot(
            group.resolve("result.json")))
            .doesNotThrowAnyException();
    }

    /**
     * A substituted input that is a symlink escaping the root is refused by the final
     * defense (canonical containment of the nearest existing ancestor).
     */
    @Test
    void shouldRejectSymlinkEscapeInFinalArgv() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("execution-2");
        Files.createDirectories(group);
        Files.writeString(tempDir.resolve("secret.txt"), "secret");
        Path link = group.resolve("escape.txt");
        Files.createSymbolicLink(link, tempDir.resolve("secret.txt"));

        assertThatThrownBy(() -> guard.assertContainedInResourceRoot(link))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A symlink planted in the middle of the chain (group directory) is also refused by
     * the no-symlink chain walk, even before the target is created.
     */
    @Test
    void shouldRejectSymlinkedGroupDirectoryInFinalArgv() throws IOException {
        Path realGroup = tempDir.resolve("real-group");
        Files.createDirectories(realGroup);
        Path dateDir = resourceRoot.resolve("2026-08-12");
        Files.createDirectories(dateDir);
        Files.createSymbolicLink(dateDir.resolve("execution-3"), realGroup);

        assertThatThrownBy(() -> guard.assertContainedInResourceRoot(
            dateDir.resolve("execution-3").resolve("out.json")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }
}
