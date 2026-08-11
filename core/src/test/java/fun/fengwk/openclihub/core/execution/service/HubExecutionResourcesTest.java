package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.service.HubResourceLeaseManager;
import fun.fengwk.openclihub.core.resource.service.HubResourceService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link HubExecutionResources}: resource lease acquisition,
 * execution group creation, scan output, and empty-group cleanup. Uses real
 * {@link HubResourceService} on a temp directory so the lease manager, symlink
 * protection and atomic file logic are exercised end-to-end.
 */
class HubExecutionResourcesTest {

    @TempDir
    Path dataDir;
    @TempDir
    Path resourceRoot;
    @TempDir
    Path workdir;

    private OpenCliHubProperties properties;
    private HubResourceLeaseManager leaseManager;
    private HubResourceService resourceService;
    private HubExecutionResources resources;

    @BeforeEach
    void setUp() {
        properties = new OpenCliHubProperties();
        properties.setDataDir(dataDir.toString());
        properties.getResource().setRootDir(resourceRoot.toString());
        properties.getOpencli().setWorkdir(workdir.toString());

        leaseManager = new HubResourceLeaseManager();
        resourceService = new HubResourceService(properties, leaseManager);
        resources = new HubExecutionResources(
            resourceService, leaseManager, new HubLocalPathGuard(properties), properties);
    }

    /**
     * A virtual-path argv token is resolved to a real path on disk and acquires a
     * lease; closing the context releases it. This is the canonical happy-path lease
     * guarantee.
     */
    @Test
    void shouldAcquireLeaseForVirtualPathInput() throws IOException {
        // Upload an input file via the service.
        Path upload = writeUpload("report.pdf", "pdf-bytes".getBytes());
        String virtual = upload.toString();
        NormalizedOpenCliArgv normalized = argv(List.of("bilibili", "submit", virtual));

        try (HubExecutionResources.ResourceContext c = resources.prepare("7001", normalized, null)) {
            assertThat(c.getSubstitutedArgv()).hasSize(3);
            // argv token[2] (formerly the virtual path) was resolved to its real path
            // under resourceRoot.
            Path real = Path.of(c.getSubstitutedArgv().get(2));
            assertThat(Files.exists(real)).isTrue();
            // The lease is held while the context is open.
            assertThat(leaseManager.heldPathCount()).as("active lease during execution").isOne();
        }
        // After close, the lease is released.
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * Input leases must be released even when the execution throws. The try-with-
     * resources pattern used by the service guarantees the close() side runs even
     * after an exception; each lease remains idempotent if cleanup reaches it twice.
     */
    @Test
    void shouldReleaseLeasesOnException() throws IOException {
        Path upload = writeUpload("note.txt", "hello".getBytes());
        NormalizedOpenCliArgv broken = argv(List.of(
            "bilibili",
            "submit",
            upload.toString(),
            "/resources/2099-01-01/upload-x/missing.pdf"));

        assertThatThrownBy(() -> resources.prepare("7002", broken, null))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThat(leaseManager.heldPathCount())
            .as("a later preparation failure must release earlier input leases")
            .isZero();
    }

    /**
     * {@code FILE}-mode output rule creates the execution group and resolves a real
     * file under it; the scanner reports that file as an EXECUTION-source DTO.
     */
    @Test
    void shouldCreateGroupAndScanFileOutput() throws IOException {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "report.json");
        NormalizedOpenCliArgv normalized = argv(List.of("bilibili", "hot"));
        try (HubExecutionResources.ResourceContext c = resources.prepare("8001", normalized, rule)) {
            assertThat(c.getGroup()).isNotNull();
            Path expected = c.getGroup().getRealPath().resolve("report.json");
            // Simulate OpenCLI dropping an output file.
            Files.writeString(expected, "{\"hot\":[1,2,3]}");
            List<fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO> items =
                resources.scan(c.getGroup());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getFileName()).isEqualTo("report.json");
            assertThat(items.get(0).getSource()).isEqualTo(HubResourceSource.EXECUTION);
            assertThat(items.get(0).getSize()).isGreaterThan(0);
        }
    }

    @Test
    void shouldFindExistingExecutionGroupAcrossDateDirectories() throws IOException {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "history.json");
        NormalizedOpenCliArgv normalized = argv(List.of("bilibili", "hot"));
        try (HubExecutionResources.ResourceContext context =
                 resources.prepare("8006", normalized, rule)) {
            Files.writeString(context.getGroup().getRealPath().resolve("history.json"), "{}");
        }

        assertThat(resources.scanExisting("8006"))
            .extracting(HubResourceItemDTO::getFileName)
            .containsExactly("history.json");
        assertThat(resources.scanExisting("8999")).isEmpty();
    }

    /**
     * When no output rule is configured, no execution group directory is created, and
     * scanning returns an empty list.
     */
    @Test
    void shouldNotCreateGroupWhenNoOutputRule() {
        NormalizedOpenCliArgv normalized = argv(List.of("chatgpt", "ask"));
        try (HubExecutionResources.ResourceContext c = resources.prepare("8002", normalized, null)) {
            assertThat(c.getGroup()).isNull();
            assertThat(resources.scan(c.getGroup())).isEmpty();
        }
    }

    /**
     * When the execution produced no files, the empty execution group must be pruned
     * to keep the resource center tidy.
     */
    @Test
    void shouldRemoveGroupIfEmpty() throws IOException {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.DIRECTORY, null);
        NormalizedOpenCliArgv normalized = argv(List.of("bilibili", "hot"));
        Path realGroupPath;
        java.time.LocalDate groupDate;
        try (HubExecutionResources.ResourceContext c = resources.prepare("8003", normalized, rule)) {
            assertThat(c.getGroup()).isNotNull();
            realGroupPath = c.getGroup().getRealPath();
            groupDate = c.getGroup().getDate();
            assertThat(Files.exists(realGroupPath)).isTrue();
        }
        // After close, ask the service to remove the group using the same path.
        fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup ref =
            fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup.builder()
                .executionId("8003").date(groupDate).group("execution-8003")
                .realPath(realGroupPath).build();
        resources.removeGroupIfEmpty(ref);
        assertThat(Files.exists(realGroupPath))
            .as("empty execution group must be removed after the run")
            .isFalse();
    }

    /**
     * A symlink planted inside the group must NOT be exposed by the scanner (mirrors resource
     * list and execution-group scan behaviour).
     */
    @Test
    void shouldNotExposeSymlinksInScannedGroup() throws IOException {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "real.txt");
        NormalizedOpenCliArgv normalized = argv(List.of("bilibili", "hot"));
        try (HubExecutionResources.ResourceContext c = resources.prepare("8004", normalized, rule)) {
            Path realFile = c.getGroup().getRealPath().resolve("real.txt");
            Files.writeString(realFile, "I am real");
            // Create a sibling file + a symlink inside the group pointing at it.
            Path sibling = c.getGroup().getRealPath().resolve("link.txt");
            Files.createSymbolicLink(sibling, Path.of("real.txt"));
            List<fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO> items =
                resources.scan(c.getGroup());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getFileName()).isEqualTo("real.txt");
        }
    }

    /**
     * The full argv path-safety: a token starting with {@code /resources/} that
     * escapes the root (e.g. raw filename) is rejected with RESOURCE_NOT_FOUND instead
     * of being assembled as an injection vector.
     */
    @Test
    void shouldRejectVirtualPathThatDoesNotExist() {
        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit",
                "/resources/2099-01-01/upload-missing/missing.pdf"));
        assertThatThrownBy(() -> resources.prepare("8005", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    /**
     * A caller-supplied absolute path is refused with OPENCLI_LOCAL_PATH_NOT_ALLOWED
     * before any resource work is performed.
     */
    @Test
    void shouldRejectAbsolutePathArgvToken() {
        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit", "/etc/passwd"));
        assertThatThrownBy(() -> resources.prepare("8101", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * Even a real absolute path that points inside the data-volume resource root is
     * refused: the stable protocol only admits virtual {@code /resources/...} references.
     */
    @Test
    void shouldRejectRealAbsolutePathInsideResourceRoot() throws IOException {
        Path real = resourceRoot.resolve("2026-08-12").resolve("upload-x").resolve("f.txt");
        Files.createDirectories(real.getParent());
        Files.writeString(real, "data");
        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit", real.toString()));
        assertThatThrownBy(() -> resources.prepare("8102", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A bare file name that already exists relative to the configured OpenCLI workdir is
     * a local path reference and must be refused; the same token as an upload is fine.
     */
    @Test
    void shouldRejectWorkdirExistingFileArgvToken() throws IOException {
        Files.writeString(workdir.resolve("note.txt"), "hello");
        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit", "note.txt"));
        assertThatThrownBy(() -> resources.prepare("8103", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * Explicit traversal tokens are refused before substitution.
     */
    @Test
    void shouldRejectTraversalArgvToken() {
        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit", "../etc/passwd"));
        assertThatThrownBy(() -> resources.prepare("8104", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A virtual token whose chain contains a symlink escaping the root is refused with
     * OPENCLI_LOCAL_PATH_NOT_ALLOWED (symlink escape), not resolved.
     */
    @Test
    void shouldRejectVirtualSymlinkEscapeArgvToken() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("upload-x");
        Files.createDirectories(group);
        Files.writeString(dataDir.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(group.resolve("link.txt"), dataDir.resolve("secret.txt"));

        NormalizedOpenCliArgv normalized = argv(
            List.of("bilibili", "submit", "/resources/2026-08-12/upload-x/link.txt"));
        assertThatThrownBy(() -> resources.prepare("8105", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * A local-path rejection after an earlier virtual substitution must release the
     * leases acquired so far.
     */
    @Test
    void shouldReleaseLeasesWhenLaterTokenIsRejectedAsLocalPath() throws IOException {
        Path upload = writeUpload("ok.txt", "ok".getBytes());
        NormalizedOpenCliArgv normalized = argv(List.of(
            "bilibili", "submit", upload.toString(), "/etc/passwd"));
        assertThatThrownBy(() -> resources.prepare("8106", normalized, null))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * URLs and ordinary prompts are not path candidates: they pass through unchanged and
     * no resource work is triggered.
     */
    @Test
    void shouldPassThroughUrlAndPromptTokens() {
        NormalizedOpenCliArgv normalized = argv(List.of(
            "chatgpt", "ask", "https://example.com/a/b", "你好，帮我写一首诗"));
        try (HubExecutionResources.ResourceContext c = resources.prepare("8107", normalized, null)) {
            assertThat(c.getGroup()).isNull();
            assertThat(c.getSubstitutedArgv()).containsExactly(
                "chatgpt", "ask", "https://example.com/a/b", "你好，帮我写一首诗");
        }
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    private Path writeUpload(String filename, byte[] bytes) throws IOException {
        // Upload via the real service so the file lands under resourceRoot with the
        // upload-{uuid} group layout HubResourceService expects.
        fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest req =
            new fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest();
        fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem item =
            new fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem();
        item.setOriginalFileName(filename);
        item.setSize((long) bytes.length);
        item.setInputStream(new java.io.ByteArrayInputStream(bytes));
        req.setItems(List.of(item));
        req.setDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        HubResourceService.UploadResult result = resourceService.upload(req);
        return Path.of("/resources/" + result.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            + "/" + result.getGroup() + "/" + filename);
    }

    private static NormalizedOpenCliArgv argv(List<String> argv) {
        OpenCliCommand command = new OpenCliCommand();
        command.setSite(argv.get(0));
        command.setName(argv.get(1));
        command.setSiteSession(fun.fengwk.openclihub.share.model.execution.SiteSessionMode.EPHEMERAL);
        return new NormalizedOpenCliArgv(command, argv.get(0) + "/" + argv.get(1),
            List.of(), new LinkedHashMap<>(), argv);
    }

    private static HubCommandOutputRule rule(String commandKey, String argName,
                                             HubCommandOutputTargetType type, String fileName) {
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setCommandKey(commandKey);
        rule.setArgumentName(argName);
        rule.setTargetType(type);
        rule.setFileName(fileName);
        return rule;
    }

}
