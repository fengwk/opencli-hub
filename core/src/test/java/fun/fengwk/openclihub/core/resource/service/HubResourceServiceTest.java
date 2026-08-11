package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.CoreTestApplication;
import fun.fengwk.openclihub.core.resource.model.HubResourceListRequest;
import fun.fengwk.openclihub.core.resource.model.HubResourceStream;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
import fun.fengwk.openclihub.share.model.resource.HubResourceDateSummaryDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the file-system Resource Center across the full MVP loop. Each test uses a fresh
 * per-method resource root under {@code java.io.tmpdir} so the suite never writes into the
 * repository and never shares state with sibling tests.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "opencli.hub.resource.root-dir=${java.io.tmpdir}/opencli-hub-resource-tests/"
            + "${random.uuid:default}",
        "opencli.hub.resource.max-file-size=1048576",
        "opencli.hub.resource.max-request-size=2097152"
    })
@TestPropertySource(properties = "spring.cloud.nacos.config.enabled=false")
class HubResourceServiceTest {

    @Autowired
    private HubResourceService resourceService;

    @Autowired
    private HubResourceLeaseManager leaseManager;

    @AfterEach
    void cleanRoots() throws IOException {
        Path root = resourceService.rootDir();
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort; ordering may keep some empty sub-dirs on failure.
                        }
                    });
            }
        }
    }

    // -- Upload / list / resolve / read / reuse / delete loop ------------------------------

    /**
     * Walks the designer's MVP happy path: upload files, list the day, resolve virtual paths,
     * open for read under a lease, reuse the same stream across two reads, and finally delete.
     */
    @Test
    void shouldCompleteUploadListResolveReadReuseDeleteLoop() throws IOException {
        LocalDate date = LocalDate.of(2026, 7, 12);
        HubResourceUploadRequest request = HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(
                uploadItem("report.pdf", "report-body"),
                uploadItem("report.pdf", "report-body")))
            .build();

        HubResourceService.UploadResult uploadResult = resourceService.upload(request);
        assertThat(uploadResult.getItems()).hasSize(2);
        assertThat(uploadResult.getGroup()).startsWith("upload-");
        assertThat(uploadResult.getItems().get(0).getFileName()).isEqualTo("report.pdf");
        assertThat(uploadResult.getItems().get(1).getFileName()).isEqualTo("report (2).pdf");
        assertThat(uploadResult.getItems().get(0).getSource()).isEqualTo(HubResourceSource.UPLOAD);

        List<HubResourceItemDTO> day = resourceService.listDay(listFor(date));
        assertThat(day).hasSize(2);
        // Day listing paths remain relative to the group and round-trip to upload paths.
        assertThat(day).extracting(HubResourceItemDTO::getRelativePath)
            .containsExactlyInAnyOrder("report.pdf", "report (2).pdf");
        assertThat(day).extracting(HubResourceItemDTO::getResourcePath)
            .containsExactlyInAnyOrderElementsOf(uploadResult.getItems().stream()
                .map(HubResourceItemDTO::getResourcePath)
                .toList());

        String reportVp = uploadResult.getItems().get(0).getResourcePath();
        HubResourcePaths.ResolvedResource resolved = resourceService.resolve(reportVp);
        assertThat(Files.exists(resolved.getRealPath())).isTrue();
        assertThat(resolved.getDate()).isEqualTo(date);

        // Open for read; lease is bundled into the AutoCloseable stream so try-with-resources
        // guarantees release. Concurrent delete must be refused while the lease is live.
        try (HubResourceStream stream = resourceService.openForRead(reportVp)) {
            assertThatThrownBy(() -> resourceService.deleteResource(reportVp))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            try (InputStream in = stream.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).isEqualTo("report-body");
                assertThat(stream.getSize()).isEqualTo(11L);
                assertThat(stream.getSource()).isEqualTo(HubResourceSource.UPLOAD);
            }
        }
        assertThat(leaseManager.heldPathCount()).isZero();
        resourceService.deleteResource(reportVp);
        assertThat(resourceService.exists(reportVp)).isFalse();
    }

    /**
     * Spaces, Unicode and URL-reserved characters must survive upload/deduplication and
     * round-trip through resolve, read and delete; DTO URLs encode each path segment.
     */
    @Test
    void shouldRoundTripSpecialAndDuplicateUploadFileNames() throws IOException {
        LocalDate date = LocalDate.of(2026, 7, 12);
        String fileName = "报告 #1?.txt";
        HubResourceService.UploadResult result = resourceService.upload(
            HubResourceUploadRequest.builder()
                .date(date.toString())
                .items(List.of(
                    uploadItem(fileName, "first"),
                    uploadItem(fileName, "second")))
                .build());

        assertThat(result.getItems()).extracting(HubResourceItemDTO::getFileName)
            .containsExactly("报告 #1?.txt", "报告 #1? (2).txt");
        String encodedBase = "/api/resources/2026-07-12/" + result.getGroup() + "/";
        assertThat(result.getItems().get(0).getDownloadUrl())
            .isEqualTo(encodedBase + "%E6%8A%A5%E5%91%8A%20%231%3F.txt");
        assertThat(result.getItems().get(0).getContentUrl())
            .isEqualTo(encodedBase + "%E6%8A%A5%E5%91%8A%20%231%3F.txt?inline=true");
        assertThat(result.getItems().get(1).getDownloadUrl())
            .isEqualTo(encodedBase + "%E6%8A%A5%E5%91%8A%20%231%3F%20(2).txt");

        for (HubResourceItemDTO item : result.getItems()) {
            HubResourcePaths.ResolvedResource resolved = resourceService.resolve(item.getResourcePath());
            assertThat(resolved.getRealPath()).exists();
            try (HubResourceStream stream = resourceService.openForRead(item.getResourcePath())) {
                assertThat(stream.getInputStream().readAllBytes()).isNotEmpty();
            }
            resourceService.deleteResource(item.getResourcePath());
            assertThat(resourceService.exists(item.getResourcePath())).isFalse();
        }
    }

    // -- Path traversal rejection ----------------------------------------------------------

    /**
     * Any virtual path that escapes the resource root (e.g. contains "..") must be rejected,
     * even when the resolved physical path would lie outside the configured root.
     */
    @Test
    void shouldRejectTraversalAttempt() {
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x/../../etc/passwd"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x/..%2F..%2Fetc"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x/a//b.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x/a\\b.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x/a\nb.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    @Test
    void shouldRejectAbsoluteAndWindowsPaths() {
        assertThatThrownBy(() -> resourceService.resolve("/etc/passwd"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("C:/Windows/System32/drivers/etc/hosts"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("file:///etc/passwd"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.resolve("/resources/2026-07-12/upload-x"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    // -- Symlink handling ------------------------------------------------------------------

    /**
     * A symlink inside the resource center must not be resolvable as a readable resource nor
     * listed as a regular file. The lease keeps listing state consistent and protects against
     * resource entries that point outside the configured root.
     */
    @Test
    void shouldRefuseResolutionAndListingOfSymlinks() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        Path outside = Files.createTempDirectory("hub-resource-outside-");
        Path outsideFile = Files.createFile(outside.resolve("secret.txt"));
        Files.writeString(outsideFile, "secret");

        String group = HubResourcePaths.newUploadGroup();
        Path dateDir = resourceService.rootDir().resolve(date.toString());
        Path groupDir = Files.createDirectories(dateDir.resolve(group));
        Files.createSymbolicLink(groupDir.resolve("escape-link"), outsideFile);

        String linkVp = "/resources/" + date.toString() + "/" + group + "/escape-link";
        assertThatThrownBy(() -> resourceService.resolve(linkVp))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.openForRead(linkVp))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.deleteResource(linkVp))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThat(outsideFile).exists();

        // Listing skips the symlinked file entirely so no DTO exposes an untrusted target.
        List<HubResourceItemDTO> day = resourceService.listDay(listFor(date));
        assertThat(day).extracting(HubResourceItemDTO::getFileName)
            .doesNotContain("escape-link");
        forceCleanOutside(outside);
    }

    /** Existing root and ancestor symlinks are rejected before create/read/delete can follow them. */
    @Test
    void shouldRejectRootAndAncestorSymlinksOnCriticalPaths() throws IOException {
        LocalDate date = LocalDate.of(2026, 7, 14);
        String group = HubResourcePaths.newUploadGroup();
        Path root = resourceService.rootDir();
        Path outsideRoot = Files.createTempDirectory("hub-resource-root-link-");
        Path outsideFile = Files.createDirectories(outsideRoot.resolve(date.toString()).resolve(group))
            .resolve("outside.txt");
        Files.writeString(outsideFile, "outside");
        String virtualPath = "/resources/" + date + "/" + group + "/outside.txt";

        Files.deleteIfExists(root);
        Files.createSymbolicLink(root, outsideRoot);
        try {
            assertThatThrownBy(() -> resourceService.resolve(virtualPath))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThatThrownBy(() -> resourceService.upload(HubResourceUploadRequest.builder()
                .date(date.toString()).items(List.of(uploadItem("new.txt", "new"))).build()))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThatThrownBy(() -> resourceService.deleteResource(virtualPath))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThat(outsideFile).exists();
        } finally {
            Files.deleteIfExists(root);
            forceCleanOutside(outsideRoot);
            Files.createDirectories(root);
        }

        Path outsideDate = Files.createTempDirectory("hub-resource-date-link-");
        Path ancestorFile = Files.createDirectories(outsideDate.resolve(group)).resolve("ancestor.txt");
        Files.writeString(ancestorFile, "ancestor");
        Path dateLink = root.resolve(date.toString());
        Files.createSymbolicLink(dateLink, outsideDate);
        String ancestorVirtualPath = "/resources/" + date + "/" + group + "/ancestor.txt";
        try {
            assertThatThrownBy(() -> resourceService.resolve(ancestorVirtualPath))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThatThrownBy(() -> resourceService.createExecutionGroup("8123", date))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThatThrownBy(() -> resourceService.deleteGroup(date.toString(), group))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThat(ancestorFile).exists();
        } finally {
            Files.deleteIfExists(dateLink);
            forceCleanOutside(outsideDate);
        }
    }

    // -- Size limits -----------------------------------------------------------------------

    /**
     * The single-file limit must trip the moment a part's actual size exceeds it. The upload
     * is rolled back by removing the freshly-created group directory.
     */
    @Test
    void shouldRejectSingleFileAboveLimit() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceUploadRequest tooBig = HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(HubResourceUploadItem.builder()
                .originalFileName("huge.bin")
                .size(2_000_000L) // > configured maxFileSize (1 MiB)
                .inputStream(streamBody("ignored"))
                .build()))
            .build();
        assertThatThrownBy(() -> resourceService.upload(tooBig))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    /**
     * Per-request budget must aggregate across multiple parts that individually satisfy the
     * per-file cap but together exceed the request total.
     */
    @Test
    void shouldRejectRequestTotalAboveLimit() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceUploadRequest req = HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(
                uploadItemSized("a.bin", 1_100_000L, "a"),
                uploadItemSized("b.bin", 1_100_000L, "b")))
            .build();
        assertThatThrownBy(() -> resourceService.upload(req))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    /**
     * Per-request budget must trigger even when the caller provides a stream with declared
     * size {@code -1} (unknown). Without actual-byte counting the limit would be silently
     * bypassed.
     */
    @Test
    void shouldRejectRequestTotalAboveLimitWhenSizesAreUnknown() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceUploadItem bigUnknown = HubResourceUploadItem.builder()
            .originalFileName("unknown.bin")
            .size(-1L)
            .inputStream(streamBody(repeat("x", 1_500_000)))
            .build();
        HubResourceUploadRequest req = HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(bigUnknown))
            .build();
        assertThatThrownBy(() -> resourceService.upload(req))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    // -- Duplicate file name ---------------------------------------------------------------

    /**
     * Uploads into a deterministic conflict must produce stable, predictable suffixes rather
     * than overwriting existing files. The reservation is now atomic via CREATE_NEW.
     */
    @Test
    void shouldHandleDuplicateNameDeterministically() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceService.UploadResult r1 = resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItem("invoice.pdf", "data")))
            .build());
        Path groupDir = resourceService.rootDir()
            .resolve(date.toString())
            .resolve(r1.getGroup());
        assertThat(HubResourcePaths.reserveFileName(groupDir, "invoice.pdf"))
            .isEqualTo("invoice (2).pdf");
    }

    /** Only FileAlreadyExists is a naming conflict; other filesystem failures must surface. */
    @Test
    void shouldPropagateNonConflictReservationIoFailure() throws IOException {
        Path notDirectory = Files.createTempFile("hub-resource-reserve-", ".tmp");
        try {
            assertThatThrownBy(() -> HubResourcePaths.reserveFileName(notDirectory, "file.txt"))
                .isInstanceOf(IOException.class);
        } finally {
            Files.deleteIfExists(notDirectory);
        }
    }

    // -- Date and group validation ---------------------------------------------------------

    /**
     * Both listing and deleting must reject malformed dates and groups before touching disk.
     */
    @Test
    void shouldValidateDateAndGroup() {
        assertThatThrownBy(() -> resourceService.listDay(listFor("20260712")))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.listDay(listFor("oops")))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.deleteGroup("2026-07-12", "not-a-group"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.createExecutionGroup("../escape", LocalDate.now()))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.createExecutionGroup(
            "999999999999999999999999999999", LocalDate.now()))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    // -- Lease behavior --------------------------------------------------------------------

    /**
     * An open stream lease must block both file-level and group-level delete; releasing the
     * lease via close lets deletion succeed and prunes the empty group directory.
     */
    @Test
    void shouldRejectDeletionWhileLeaseHeld() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceService.UploadResult result = resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItem("locked.pdf", "contents")))
            .build());
        String vp = result.getItems().get(0).getResourcePath();
        HubResourcePaths.ResolvedResource resolved = resourceService.resolve(vp);

        try (HubResourceLease ignored = leaseManager.acquire(resolved.getRealPath(), "test")) {
            assertThatThrownBy(() -> resourceService.deleteResource(vp))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThatThrownBy(() -> resourceService.deleteGroup(date.toString(), result.getGroup()))
                .isInstanceOf(ThrowableConventionErrorCode.class);
        }
        resourceService.deleteGroup(date.toString(), result.getGroup());
        assertThat(resourceService.exists(vp)).isFalse();
    }

    /**
     * Try-with-resources must release leases on the exceptional path so that a subsequent
     * delete request from the same caller succeeds.
     */
    @Test
    void shouldReleaseLeaseOnException() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceService.UploadResult result = resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItem("rollback.txt", "lines")))
            .build());
        String vp = result.getItems().get(0).getResourcePath();
        Path real = resourceService.resolve(vp).getRealPath();
        Throwable caught = null;
        try {
            try (HubResourceLease ignored = leaseManager.acquire(real, "boom")) {
                throw new IllegalStateException("simulated");
            }
        } catch (Throwable ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();
        assertThat(leaseManager.heldPathCount()).isZero();
        resourceService.deleteResource(vp);
        assertThat(Files.exists(real)).isFalse();
    }

    // -- Recursive scan --------------------------------------------------------------------

    /**
     * The execution API must walk a generated directory tree while ignoring any symlinks
     * planted inside it.
     */
    @Test
    void shouldScanExecutionTreeIgnoringSymlinks() throws IOException {
        String executionId = UUID.randomUUID().toString();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        ExecutionGroupHolder group = ExecutionGroupHolder.create(resourceService, executionId, date);
        Path execDir = group.realPath;
        Files.createDirectories(execDir.resolve("sub"));
        Files.writeString(execDir.resolve("a.txt"), "A");
        Files.writeString(execDir.resolve("sub/b.json"), "{\"x\":1}");
        Path outsideDir = Files.createTempDirectory("hub-resource-exec-outside-");
        Files.writeString(outsideDir.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(execDir.resolve("link"), outsideDir);
        Files.createSymbolicLink(execDir.resolve("sub/skipped-link"), outsideDir.resolve("outside.txt"));

        List<HubResourceItemDTO> found = resourceService.scanExecutionGroup(group.model);
        assertThat(found).extracting(HubResourceItemDTO::getFileName)
            .containsExactlyInAnyOrder("a.txt", "b.json");
        assertThat(found).allMatch(item -> item.getSource() == HubResourceSource.EXECUTION);

        forceCleanOutside(outsideDir);
    }

    /** Nested execution output names use the same special-name resolver and URL encoder. */
    @Test
    void shouldResolveNestedExecutionPathsWithSpecialFileNames() throws IOException {
        String executionId = "8124";
        LocalDate date = LocalDate.of(2026, 7, 15);
        ExecutionGroupHolder group = ExecutionGroupHolder.create(resourceService, executionId, date);
        Path nested = Files.createDirectories(group.realPath.resolve("子 目录#?"));
        Files.writeString(nested.resolve("结果 #1?.json"), "{}");

        HubResourceItemDTO item = resourceService.scanExecutionGroup(group.model).get(0);
        assertThat(item.getRelativePath()).isEqualTo("子 目录#?/结果 #1?.json");
        assertThat(item.getDownloadUrl()).isEqualTo(
            "/api/resources/2026-07-15/execution-8124/"
                + "%E5%AD%90%20%E7%9B%AE%E5%BD%95%23%3F/"
                + "%E7%BB%93%E6%9E%9C%20%231%3F.json");
        assertThat(resourceService.resolve(item.getResourcePath()).getRealPath())
            .isEqualTo(nested.resolve("结果 #1?.json"));
        try (HubResourceStream stream = resourceService.openForRead(item.getResourcePath())) {
            assertThat(stream.getInputStream().readAllBytes())
                .containsExactly("{}".getBytes(StandardCharsets.UTF_8));
        }
        resourceService.deleteResource(item.getResourcePath());
        assertThat(resourceService.exists(item.getResourcePath())).isFalse();
    }

    // -- Deletion and empty-directory cleanup ---------------------------------------------

    /**
     * Deleting the last file in a group must prune the empty group directory and then the
     * empty date directory; deleting a date with no contents leaves a clean tree.
     */
    @Test
    void shouldPruneEmptyDirectoriesAfterDelete() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceService.UploadResult result = resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItem("only.pdf", "value")))
            .build());
        String vp = result.getItems().get(0).getResourcePath();
        resourceService.deleteResource(vp);
        Path dateDir = resourceService.rootDir().resolve(date.toString());
        assertThat(Files.exists(dateDir)).isFalse();
    }

    /**
     * Deleting a group with multiple descendants must remove every entry but never follow
     * symlinks even if one is planted inside the tree.
     */
    @Test
    void shouldDeleteGroupAndPruneEmptyTree() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        HubResourceService.UploadResult result = resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(
                uploadItem("a.txt", "aa"),
                uploadItem("b.txt", "bbb")))
            .build());
        Path groupReal = resourceService.rootDir().resolve(date.toString()).resolve(result.getGroup());
        Files.createDirectories(groupReal.resolve("nested"));
        Files.writeString(groupReal.resolve("nested/c.txt"), "ccc");
        Path outside = Files.createTempDirectory("hub-resource-prune-outside-");
        Files.createSymbolicLink(groupReal.resolve("outside-link"), outside);

        resourceService.deleteGroup(date.toString(), result.getGroup());
        assertThat(Files.exists(groupReal)).isFalse();
        assertThat(Files.exists(resourceService.rootDir().resolve(date.toString()))).isFalse();
        forceCleanOutside(outside);
    }

    // -- Date summaries --------------------------------------------------------------------

    /**
     * Date summaries must aggregate group count, file count and total bytes for the
     * requested UTC date even when multiple groups have files.
     */
    @Test
    void shouldProduceDateSummary() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItem("u1.txt", "abc"), uploadItem("u2.txt", "abcd")))
            .build());
        String execId = "42";
        ExecutionGroupHolder execGroup = ExecutionGroupHolder.create(resourceService, execId, date);
        Files.writeString(execGroup.realPath.resolve("output.json"), "[]");
        HubResourceDateSummaryDTO summary = resourceService.summarizeDate(date.toString());
        assertThat(summary.getGroupCount()).isGreaterThanOrEqualTo(2);
        assertThat(summary.getFileCount()).isGreaterThanOrEqualTo(3);
        assertThat(summary.getTotalSize()).isGreaterThan(0L);
    }

    // -- Execution API ---------------------------------------------------------------------

    /**
     * Removing an execution group only succeeds when the directory is empty. After creating
     * one and immediately asking for removal, the second request should report no-op.
     */
    @Test
    void shouldRemoveExecutionGroupOnlyIfEmpty() {
        String execId = "99";
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        ExecutionGroupHolder group = ExecutionGroupHolder.create(resourceService, execId, date);
        assertThat(resourceService.removeExecutionGroupIfEmpty(group.model)).isTrue();
        assertThat(resourceService.removeExecutionGroupIfEmpty(group.model)).isFalse();
    }

    // -- Sanitization ----------------------------------------------------------------------

    /**
     * Uploaded file names are reduced to a single safe basename: directory components and
     * Windows drive prefixes are consumed, exact {@code .}/{@code ..} tokens and empty
     * input map to {@code file}, control characters are neutralized, and ordinary
     * single-segment names pass through unchanged.
     */
    @Test
    void shouldSanitizeUploadFileNames() {
        // Traversal attempts collapse to their basename and never keep separators/tokens.
        assertThat(HubResourcePaths.sanitizeUploadFileName("../../etc/passwd"))
            .isEqualTo("passwd");
        assertThat(HubResourcePaths.sanitizeUploadFileName("..\\..\\etc\\passwd"))
            .isEqualTo("passwd");
        assertThat(HubResourcePaths.sanitizeUploadFileName("/etc/passwd"))
            .isEqualTo("passwd");
        assertThat(HubResourcePaths.sanitizeUploadFileName("a/b/c.txt"))
            .isEqualTo("c.txt");
        // Windows drive prefixes are stripped before the basename is taken.
        assertThat(HubResourcePaths.sanitizeUploadFileName("C:\\Windows\\evil.exe"))
            .isEqualTo("evil.exe");
        assertThat(HubResourcePaths.sanitizeUploadFileName("C:evil.exe"))
            .isEqualTo("evil.exe");
        // Exact navigation tokens, bare separators and empty input map to a neutral name.
        assertThat(HubResourcePaths.sanitizeUploadFileName(".")).isEqualTo("file");
        assertThat(HubResourcePaths.sanitizeUploadFileName("..")).isEqualTo("file");
        assertThat(HubResourcePaths.sanitizeUploadFileName("")).isEqualTo("file");
        assertThat(HubResourcePaths.sanitizeUploadFileName("/")).isEqualTo("file");
        assertThat(HubResourcePaths.sanitizeUploadFileName("\\")).isEqualTo("file");
        assertThat(HubResourcePaths.sanitizeUploadFileName("a/..")).isEqualTo("file");
        // Control characters are neutralized.
        assertThat(HubResourcePaths.sanitizeUploadFileName("naughty\u0000name.txt"))
            .isEqualTo("naughty_name.txt");
        assertThat(HubResourcePaths.sanitizeUploadFileName("line\nbreak.txt"))
            .isEqualTo("line_break.txt");
        // Ordinary file names pass through unchanged.
        assertThat(HubResourcePaths.sanitizeUploadFileName("invoice.pdf"))
            .isEqualTo("invoice.pdf");
        assertThat(HubResourcePaths.sanitizeUploadFileName("my report (final).txt"))
            .isEqualTo("my report (final).txt");
    }

    // -- Resource not found ----------------------------------------------------------------

    /**
     * Opening or deleting a non-existent virtual path yields {@code RESOURCE_NOT_FOUND}.
     */
    @Test
    void shouldReturnNotFoundForMissingResource() {
        assertThatThrownBy(() -> resourceService.openForRead("/resources/2099-01-01/upload-nope/missing.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        assertThatThrownBy(() -> resourceService.deleteResource("/resources/2099-01-01/upload-nope/missing.txt"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    // -- helpers ---------------------------------------------------------------------------

    private static HubResourceUploadItem uploadItem(String name, String body) {
        return uploadItemSized(name, body.getBytes(StandardCharsets.UTF_8).length, body);
    }

    private static HubResourceUploadItem uploadItemSized(String name, long size, String body) {
        return HubResourceUploadItem.builder()
            .originalFileName(name)
            .size(size)
            .inputStream(streamBody(body))
            .build();
    }

    private static ByteArrayInputStream streamBody(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static HubResourceListRequest listFor(LocalDate date) {
        return HubResourceListRequest.builder().date(date.toString()).build();
    }

    private static HubResourceListRequest listFor(String date) {
        return HubResourceListRequest.builder().date(date).build();
    }

    private static void forceCleanOutside(Path outside) throws IOException {
        if (outside == null || !Files.exists(outside)) {
            return;
        }
        Files.walkFileTree(outside, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Test-local holder pairing the service-supplied execution group model with its real
     * path so tests can touch the directory tree directly.
     */
    private static final class ExecutionGroupHolder {
        final Path realPath;
        final fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup model;

        private ExecutionGroupHolder(Path realPath,
            fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup model) {
            this.realPath = realPath;
            this.model = model;
        }

        static ExecutionGroupHolder create(HubResourceService service, String execId, LocalDate date) {
            fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup model =
                service.createExecutionGroup(execId, date);
            return new ExecutionGroupHolder(model.getRealPath(), model);
        }
    }

}
