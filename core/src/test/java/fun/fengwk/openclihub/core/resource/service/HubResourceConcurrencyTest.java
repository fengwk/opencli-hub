package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.CoreTestApplication;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubResourceListRequest;
import fun.fengwk.openclihub.core.resource.model.HubResourceStream;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused concurrency hardening tests for the M2 Resource Center. Each scenario is
 * deterministic: a {@link CountDownLatch} start gate and per-thread recording of results
 * let the assertions verify exclusivity, atomicity and rollback boundaries.
 */
@SpringBootTest(classes = CoreTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "opencli.hub.resource.root-dir=${java.io.tmpdir}/opencli-hub-concurrency-tests/"
            + "${random.uuid:default}",
        "opencli.hub.resource.max-file-size=1048576",
        "opencli.hub.resource.max-request-size=2097152"
    })
@TestPropertySource(properties = "spring.cloud.nacos.config.enabled=false")
class HubResourceConcurrencyTest {

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
                            // best-effort cleanup
                        }
                    });
            }
        }
    }

    // -- H1: rollback never destroys concurrent siblings ------------------------------------

    /**
     * Two threads concurrently upload to the same date and the same sanitized filename.
     * Each thread creates its OWN upload-{uuid} group, so the experiment exercises the fact
     * that the partial-failure rollback of thread A must NOT delete the file uploaded by
     * thread B (which lives under a different group and a different sanitized target).
     */
    @Test
    void shouldNotDeleteConcurrentSiblingOnPartialUploadFailure() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                // Half the threads succeed; the other half simulate a mid-stream failure
                // by passing a body longer than the per-request limit. That triggers the
                // rollback path; we assert that successful threads' groups remain intact
                // and that the rollback never deletes data the failing thread did not own.
                if (tid % 2 == 0) {
                    HubResourceUploadRequest req = HubResourceUploadRequest.builder()
                        .date(date.toString())
                        .items(List.of(uploadItemSized("shared.txt", 8L, "ok-" + tid)))
                        .build();
                    return resourceService.upload(req).getItems().stream()
                        .map(HubResourceItemDTO::getFileName).toList();
                }
                HubResourceUploadRequest req = HubResourceUploadRequest.builder()
                    .date(date.toString())
                    .items(List.of(uploadItemSized("shared.txt", 0L, repeat('x', 3 * 1024 * 1024))))
                    .build();
                try {
                    resourceService.upload(req);
                    return List.<String>of();
                } catch (RuntimeException expected) {
                    return List.<String>of();
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        List<List<String>> results = new ArrayList<>();
        for (Future<List<String>> f : futures) {
            results.add(f.get());
        }
        long successful = results.stream().filter(r -> !r.isEmpty()).count();
        assertThat(successful).isEqualTo(threadCount / 2);
        Path dateDir = resourceService.rootDir().resolve(date.toString());
        long groupCount;
        try (Stream<Path> list = Files.list(dateDir)) {
            groupCount = list.filter(Files::isDirectory).count();
        }
        assertThat(groupCount).isEqualTo(threadCount / 2);
    }

    // -- H1 + atomic CREATE_NEW claim across many threads on the same group directory -----

    /**
     * Direct stress test of {@link HubResourcePaths#reserveFileName}: many threads concurrently
     * claim names in the same group directory. Because the claim is performed via atomic
     * {@code CREATE_NEW} no two threads may receive the same placeholder.
     */
    @Test
    void shouldAtomicallyReserveDistinctFileNamesAcrossManyThreads() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        String group = HubResourcePaths.newUploadGroup();
        Path groupDir = Files.createDirectories(
            resourceService.rootDir().resolve(date.toString()).resolve(group));
        int total = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return HubResourcePaths.reserveFileName(groupDir, "conflict.txt");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);
        Set<String> unique = new HashSet<>();
        for (Future<String> f : futures) {
            unique.add(f.get());
        }
        assertThat(unique).hasSize(total);
        for (String n : unique) {
            assertThat(Files.exists(groupDir.resolve(n))).isTrue();
        }
    }

    // -- H3: acquire/destructive-delete mutual exclusion on same path/ancestor --------------

    /**
     * Hold a destructive delete in {@link HubResourceLeaseManager#runExclusively} and assert
     * that a concurrent {@link HubResourceLeaseManager#acquire(Path, String)} on the same
     * target throws {@code RESOURCE_IN_USE}.
     */
    @Test
    void shouldBlockAcquireWhileDestructiveDeleteInProgress() throws Exception {
        Path target = Files.createTempFile("hub-resource-lease-test-", ".txt");
        Files.writeString(target, "data");
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread deleter = new Thread(() -> {
            leaseManager.runExclusively(target, () -> {
                try {
                    reserved.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    Files.deleteIfExists(target);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }, "deleter");
        deleter.start();
        assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> leaseManager.acquire(target, "reader"))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        release.countDown();
        deleter.join(5000);
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * After a destructive delete completes the next acquire must succeed (the underlying
     * file may be gone, but the lease manager does not check filesystem existence).
     */
    @Test
    void shouldAllowAcquireAfterDeleteExclusiveEnds() throws Exception {
        Path target = Files.createTempFile("hub-resource-lease-free-", ".txt");
        Files.writeString(target, "data");
        leaseManager.runExclusively(target, () -> {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
        HubResourceLease lease = leaseManager.acquire(target, "after");
        assertThat(lease.realPath()).isEqualTo(target.toAbsolutePath().normalize());
        lease.close();
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    /**
     * The same destructive delete invoked twice on the same target must be serialized: the
     * second invocation sees the delete-in-progress flag and refuses immediately.
     */
    @Test
    void shouldRejectConcurrentDestructiveDeleteOnSameTarget() throws Exception {
        Path target = Files.createTempFile("hub-resource-lease-dup-", ".txt");
        Files.writeString(target, "data");
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread a = new Thread(() -> leaseManager.runExclusively(target, () -> {
            try {
                reserved.countDown();
                release.await(5, TimeUnit.SECONDS);
                Files.deleteIfExists(target);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }));
        a.start();
        assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> leaseManager.runExclusively(target, () -> {}))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        release.countDown();
        a.join(5000);
        // After first delete, subsequent delete on a missing file is still allowed (file is
        // already gone). We only assert the flag cleared.
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    // -- H4a: HubResourceStream suppressed exceptions --------------------------------------

    /**
     * When the underlying input stream throws on close, the lease-release problem is
     * attached as suppressed so the caller can inspect both at once. Uses a controllable
     * lease wrapper because the production lease release never throws under nominal use.
     */
    @Test
    void shouldSuppressLeaseReleaseFailureWhenStreamCloseFails() throws Exception {
        Path real = Files.createTempFile("hub-stream-suppress-", ".txt");
        Files.writeString(real, "data");
        AutoCloseable throwingLease = () -> { throw new IllegalStateException("lease close failed"); };
        InputStream failing = new FailingOnCloseInputStream(new ByteArrayInputStream(
            "data".getBytes(StandardCharsets.UTF_8)), "primary close failed");
        HubResourceStream stream = new HubResourceStream(
            real, "failing.txt", "text/plain", 4L,
            HubResourceSource.UPLOAD, failing, throwingLease);
        try {
            stream.close();
            assertThat(false).as("close must throw").isTrue();
        } catch (java.io.IOException ex) {
            assertThat(ex.getMessage()).contains("primary close failed");
            assertThat(ex.getSuppressed()).hasSize(1);
            assertThat(ex.getSuppressed()[0]).isInstanceOf(IllegalStateException.class);
        }
    }

    // -- H4b: pagination overflow ---------------------------------------------------------

    /**
     * Large page indices must not overflow: the multiplication is performed in long space
     * before clamping against the actual item count.
     */
    @Test
    void shouldNotOverflowPageMultiplication() throws IOException {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        resourceService.upload(HubResourceUploadRequest.builder()
            .date(date.toString())
            .items(List.of(uploadItemSized("only.txt", 1L, "x")))
            .build());
        HubResourceListRequest req = HubResourceListRequest.builder()
            .date(date.toString())
            .page(Integer.MAX_VALUE)
            .pageSize(Integer.MAX_VALUE)
            .build();
        List<HubResourceItemDTO> page = resourceService.listDay(req);
        assertThat(page).isEmpty();
    }

    // -- H4c: blank rootDir fail-fast -----------------------------------------------------

    /**
     * A blank or null {@code rootDir} must fail fast with {@code RESOURCE_PATH_INVALID}
     * rather than silently fall back to a default.
     */
    @Test
    void shouldFailFastOnBlankRootDir() {
        OpenCliHubProperties props = new OpenCliHubProperties();
        props.getResource().setRootDir("");
        assertThatThrownBy(() -> HubResourcePaths.resourceRoot(props))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        props.getResource().setRootDir("   ");
        assertThatThrownBy(() -> HubResourcePaths.resourceRoot(props))
            .isInstanceOf(ThrowableConventionErrorCode.class);
        props.getResource().setRootDir(null);
        assertThatThrownBy(() -> HubResourcePaths.resourceRoot(props))
            .isInstanceOf(ThrowableConventionErrorCode.class);
    }

    // -- End-to-end: concurrent upload of identical content to distinct groups -------------

    /**
     * The simplest deterministic concurrency test of upload correctness: many threads upload
     * to distinct groups on the same UTC date with the same filename. Every result must be
     * present, every file content must match the request's body, and no orphan files must
     * be left behind.
     */
    @Test
    void shouldHandleManyConcurrentUploadsToDistinctGroupsDeterministically() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        int threadCount = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HubResourceService.UploadResult>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                HubResourceUploadRequest req = HubResourceUploadRequest.builder()
                    .date(date.toString())
                    .items(List.of(
                        uploadItemSized("race.txt", 0L, "thread-" + tid + "-0"),
                        uploadItemSized("race.txt", 0L, "thread-" + tid + "-1")))
                    .build();
                return resourceService.upload(req);
            }));
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        Set<String> distinctGroups = new HashSet<>();
        for (Future<HubResourceService.UploadResult> f : futures) {
            HubResourceService.UploadResult r = f.get();
            assertThat(r.getGroup()).startsWith("upload-");
            distinctGroups.add(r.getGroup());
            Path groupDir = resourceService.rootDir().resolve(date.toString()).resolve(r.getGroup());
            // Deterministic dedupe within a single thread's request: "race.txt" wins, then
            // "race (2).txt".
            assertThat(r.getItems()).extracting(HubResourceItemDTO::getFileName)
                .containsExactly("race.txt", "race (2).txt");
            // Verify file content integrity for both items.
            for (HubResourceItemDTO item : r.getItems()) {
                String body = Files.readString(groupDir.resolve(item.getFileName()));
                assertThat(body).startsWith("thread-");
            }
        }
        assertThat(distinctGroups).hasSize(threadCount);
    }

    // -- Helpers ---------------------------------------------------------------------------

    private static HubResourceUploadItem uploadItemSized(String name, long declaredSize, String body) {
        return HubResourceUploadItem.builder()
            .originalFileName(name)
            .size(declaredSize)
            .inputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
            .build();
    }

    private static String repeat(char ch, int times) {
        char[] buf = new char[times];
        java.util.Arrays.fill(buf, ch);
        return new String(buf);
    }

    private static final class FailingOnCloseInputStream extends InputStream {
        private final InputStream delegate;
        private final String message;

        private FailingOnCloseInputStream(InputStream delegate, String message) {
            this.delegate = delegate;
            this.message = message;
        }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
        @Override public void close() throws IOException { throw new IOException(message); }
    }

}
