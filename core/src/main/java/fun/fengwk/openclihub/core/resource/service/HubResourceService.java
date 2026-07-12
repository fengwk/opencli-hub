package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.core.resource.model.HubResourceListConstants;
import fun.fengwk.openclihub.core.resource.model.HubResourceListRequest;
import fun.fengwk.openclihub.core.resource.model.HubResourceStream;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest;
import fun.fengwk.openclihub.core.resource.util.HubResourceMimeTypes;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.resource.HubResourceDateSummaryDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Stateless service fronting the file-system resource center. Backed by
 * {@link OpenCliHubProperties.Resource} which governs the root directory and the per-file
 * and per-request size budgets. The service deliberately exposes core-level operations
 * only; the M6 web controller is responsible for translating multipart streams into
 * {@link HubResourceUploadRequest}.
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubResourceService {

    private static final Pattern DATE_DIR_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final OpenCliHubProperties properties;
    private final Path root;
    private final HubResourceLeaseManager leaseManager;

    public HubResourceService(OpenCliHubProperties properties, HubResourceLeaseManager leaseManager) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.root = HubResourcePaths.resourceRoot(properties);
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
    }

    /** Real path of the configured resource root. */
    public Path rootDir() {
        return root;
    }

    /**
     * Upload one or more items. Each item is sanitized, deduped, written atomically with
     * size enforcement, and recorded as a frozen-share DTO entry. Throws
     * {@code RESOURCE_UPLOAD_TOO_LARGE} when a single file or the total exceeds the policy.
     */
    public UploadResult upload(HubResourceUploadRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        long maxFile = properties.getResource().getMaxFileSize();
        long maxRequest = properties.getResource().getMaxRequestSize();
        List<HubResourceUploadItem> items = request.getItems();
        // Fast-path: reject only items whose declared size already exceeds the per-file cap.
        // Per-request accounting happens during the actual write so that unknown-size items
        // (size < 0) and known-size items share one counting path.
        for (HubResourceUploadItem item : items) {
            if (item == null || item.getInputStream() == null) {
                throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
            }
            if (item.getSize() > maxFile) {
                throw HubErrorCodes.RESOURCE_UPLOAD_TOO_LARGE.asThrowable();
            }
        }

        LocalDate date = request.getDate() == null || request.getDate().isBlank()
            ? LocalDate.now(ZoneOffset.UTC)
            : HubResourcePaths.parseDate(request.getDate());
        String group = HubResourcePaths.newUploadGroup();
        Path groupDir = root.resolve(date.format(HubResourcePaths.DATE_FORMAT)).resolve(group);
        try {
            Files.createDirectories(groupDir);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }

        List<HubResourceItemDTO> uploaded = new ArrayList<>(items.size());
        AtomicLong requestBytes = new AtomicLong(0L);
        for (HubResourceUploadItem item : items) {
            String sanitized = HubResourcePaths.sanitizeUploadFileName(item.getOriginalFileName());
            String unique = HubResourcePaths.resolveFileNameConflict(groupDir, sanitized);
            Path target = groupDir.resolve(unique);
            long fileBytes;
            try {
                fileBytes = writeBounded(item.getInputStream(), target, maxFile, maxRequest, requestBytes);
            } catch (BoundedWriteException ex) {
                deleteQuietly(target);
                deleteQuietly(groupDir);
                throw HubErrorCodes.RESOURCE_UPLOAD_TOO_LARGE.asThrowable(ex);
            } catch (IOException ex) {
                deleteQuietly(target);
                deleteQuietly(groupDir);
                throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
            }
            String virtualPath = HubResourcePaths.VIRTUAL_PREFIX
                + date.format(HubResourcePaths.DATE_FORMAT) + "/" + group + "/" + unique;
            uploaded.add(toItemDTO(date, group, unique, virtualPath, HubResourceSource.UPLOAD,
                unique, fileBytes, target));
        }
        log.info("Uploaded {} files into group {} ({} bytes total)",
            uploaded.size(), group, requestBytes.get());
        return new UploadResult(group, date, uploaded);
    }

    /**
     * Resolve a virtual path into a real, validated path. Throws {@code RESOURCE_NOT_FOUND}
     * when the real path does not exist on disk.
     */
    public HubResourcePaths.ResolvedResource resolve(String virtualPath) {
        HubResourcePaths.ResolvedResource resolved = HubResourcePaths.resolve(root, virtualPath);
        if (!Files.exists(resolved.getRealPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        return resolved;
    }

    /**
     * Open a resource for streaming under an active lease. The returned
     * {@link HubResourceStream} is {@link AutoCloseable}; try-with-resources guarantees that
     * the underlying input stream and the lease are both released when the read finishes.
     * The lease blocks concurrent deletion while reading.
     */
    public HubResourceStream openForRead(String virtualPath) {
        HubResourcePaths.ResolvedResource resolved = resolve(virtualPath);
        Path real = resolved.getRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        HubResourceLease lease = leaseManager.acquire(real, "read");
        InputStream stream;
        try {
            stream = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            lease.close();
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable(ex);
        }
        long size;
        try {
            size = Files.size(real);
        } catch (IOException ex) {
            size = 0L;
        }
        String mime = HubResourceMimeTypes.probe(real);
        return new HubResourceStream(real, real.getFileName().toString(), mime, size,
            HubResourcePaths.detectSource(resolved.getGroup()), stream, lease);
    }

    /** Convenience for tests: existence check by virtual path. */
    public boolean exists(String virtualPath) {
        try {
            HubResourcePaths.ResolvedResource resolved = HubResourcePaths.resolve(root, virtualPath);
            return Files.exists(resolved.getRealPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (ThrowableConventionErrorCode ex) {
            return false;
        }
    }

    /**
     * Delete a single resource. Throws {@code RESOURCE_IN_USE} when the target is leased.
     */
    public void deleteResource(String virtualPath) {
        HubResourcePaths.ResolvedResource resolved = HubResourcePaths.resolve(root, virtualPath);
        Path real = resolved.getRealPath();
        if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        leaseManager.assertDeletable(real);
        try {
            Files.delete(real);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            HubResourcePaths.pruneEmptyAncestors(root, real.getParent());
        } catch (IOException ex) {
            // Best-effort cleanup; do not fail the delete if prune fails.
        }
    }

    /**
     * Recursively delete a group directory. Throws {@code RESOURCE_IN_USE} when any resource
     * within the group is currently leased.
     */
    public void deleteGroup(String date, String group) {
        LocalDate parsedDate = HubResourcePaths.parseDate(date);
        Path groupReal = HubResourcePaths.groupDir(root, parsedDate, group);
        if (!Files.exists(groupReal, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        if (!Files.isDirectory(groupReal, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        // Assert every existing descendant before the first delete so failure semantics are predictable.
        try {
            Files.walkFileTree(groupReal, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    leaseManager.assertDeletable(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    leaseManager.assertDeletable(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            HubResourcePaths.deleteRecursivelyNoFollow(groupReal);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            HubResourcePaths.pruneEmptyAncestors(root, groupReal.getParent());
        } catch (IOException ex) {
            // ignored; non-essential cleanup
        }
    }

    /**
     * Recursively delete an entire date directory. Throws {@code RESOURCE_IN_USE} when any
     * resource within the date is currently leased.
     */
    public void deleteDate(String date) {
        LocalDate parsedDate = HubResourcePaths.parseDate(date);
        Path dateReal = HubResourcePaths.dateDir(root, parsedDate);
        if (!Files.exists(dateReal, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        if (!Files.isDirectory(dateReal, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        try {
            Files.walkFileTree(dateReal, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    leaseManager.assertDeletable(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    leaseManager.assertDeletable(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            HubResourcePaths.deleteRecursivelyNoFollow(dateReal);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
    }

    /**
     * Summaries per UTC date in descending date order.
     */
    public List<HubResourceDateSummaryDTO> listDateSummaries() {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<HubResourceDateSummaryDTO> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path datePath : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(datePath, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = datePath.getFileName().toString();
                if (!DATE_DIR_NAME.matcher(name).matches()) {
                    continue;
                }
                LocalDate date = HubResourcePaths.parseDate(name);
                out.add(summarizeDateInternal(date, datePath));
            }
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        out.sort(Comparator.comparing(HubResourceDateSummaryDTO::getDate).reversed());
        return out;
    }

    /**
     * Aggregate stats for a single UTC date.
     */
    public HubResourceDateSummaryDTO summarizeDate(String date) {
        LocalDate parsed = HubResourcePaths.parseDate(date);
        Path dateReal = HubResourcePaths.dateDir(root, parsed);
        if (!Files.exists(dateReal, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        return summarizeDateInternal(parsed, dateReal);
    }

    /**
     * List resources of a UTC date with optional source/keyword filter, sort and paging.
     */
    public List<HubResourceItemDTO> listDay(HubResourceListRequest request) {
        if (request == null || request.getDate() == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        LocalDate date = HubResourcePaths.parseDate(request.getDate());
        Path dateReal = HubResourcePaths.dateDir(root, date);
        if (!Files.exists(dateReal, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        String keyword = request.getKeyword() == null ? null : request.getKeyword().toLowerCase(Locale.ROOT);
        HubResourceListRequest.ResourceSort sort = request.getSort() == null
            ? HubResourceListRequest.ResourceSort.MODIFIED_DESC
            : request.getSort();
        int page = Math.max(0, request.getPage());
        int pageSize = request.getPageSize() <= 0
            ? HubResourceListConstants.DEFAULT_PAGE_SIZE
            : Math.min(request.getPageSize(), HubResourceListConstants.MAX_PAGE_SIZE);

        List<HubResourceItemDTO> items = new ArrayList<>();
        try {
            Files.walkFileTree(dateReal, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = dateReal.relativize(file);
                    String relStr = relative.toString().replace('\\', '/');
                    String[] segments = relStr.split("/");
                    if (segments.length < 2) {
                        return FileVisitResult.CONTINUE;
                    }
                    String group = segments[0];
                    HubResourceSource source = HubResourcePaths.detectSource(group);
                    if (source == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (request.getSource() != null && request.getSource() != source) {
                        return FileVisitResult.CONTINUE;
                    }
                    String fileName = segments[segments.length - 1];
                    if (keyword != null && !fileName.toLowerCase(Locale.ROOT).contains(keyword)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String virtualPath = HubResourcePaths.VIRTUAL_PREFIX
                        + date.format(HubResourcePaths.DATE_FORMAT) + "/" + group + "/" + relStr;
                    String declaredRelative = relStr;
                    items.add(toItemDTO(date, group, declaredRelative, virtualPath, source, fileName, attrs.size(), file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        Comparator<HubResourceItemDTO> cmp = switch (sort) {
            case MODIFIED_DESC -> Comparator.comparing(HubResourceItemDTO::getModifiedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
            case MODIFIED_ASC -> Comparator.comparing(HubResourceItemDTO::getModifiedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
            case SIZE_DESC -> Comparator.comparingLong(HubResourceItemDTO::getSize).reversed();
            case SIZE_ASC -> Comparator.comparingLong(HubResourceItemDTO::getSize);
            case NAME_ASC -> Comparator.comparing(HubResourceItemDTO::getFileName, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> Comparator.comparing(HubResourceItemDTO::getFileName,
                String.CASE_INSENSITIVE_ORDER.reversed());
        };
        items.sort(cmp);
        int from = Math.min(page * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return new ArrayList<>(items.subList(from, to));
    }

    /**
     * Create an execution group directory under the supplied UTC date. Returns the model
     * describing the group location.
     */
    public HubExecutionResourceGroup createExecutionGroup(long executionId, LocalDate date) {
        if (executionId <= 0L) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        LocalDate targetDate = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        String group = HubResourcePaths.executionGroup(executionId);
        Path groupReal = HubResourcePaths.groupDir(root, targetDate, group);
        try {
            Files.createDirectories(groupReal);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        return HubExecutionResourceGroup.builder()
            .executionId(executionId)
            .date(targetDate)
            .group(group)
            .realPath(groupReal)
            .build();
    }

    /**
     * Recursively scan an execution group without following symbolic links. Returns a flat
     * listing of files with their relative paths inside the group.
     */
    public List<HubResourceItemDTO> scanExecutionGroup(HubExecutionResourceGroup group) {
        Objects.requireNonNull(group, "group");
        if (!Files.exists(group.getRealPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable();
        }
        if (!Files.isDirectory(group.getRealPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        List<HubResourceItemDTO> items = new ArrayList<>();
        try {
            Files.walkFileTree(group.getRealPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = group.getRealPath().relativize(file);
                    String relStr = relative.toString().replace('\\', '/');
                    String lastSegment = relStr;
                    int slash = relStr.lastIndexOf('/');
                    if (slash >= 0) {
                        lastSegment = relStr.substring(slash + 1);
                    }
                    String virtualPath = HubResourcePaths.VIRTUAL_PREFIX
                        + group.getDate().format(HubResourcePaths.DATE_FORMAT) + "/" + group.getGroup() + "/" + relStr;
                    items.add(toItemDTO(group.getDate(), group.getGroup(), relStr, virtualPath,
                        HubResourceSource.EXECUTION, lastSegment, attrs.size(), file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        items.sort(Comparator.comparing(HubResourceItemDTO::getRelativePath));
        return items;
    }

    /**
     * Remove an execution group only when it is empty (no entries, no descendants). Used by
     * the execution runtime after a successful run with no recorded outputs.
     */
    public boolean removeExecutionGroupIfEmpty(HubExecutionResourceGroup group) {
        Objects.requireNonNull(group, "group");
        Path real = group.getRealPath();
        if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(real)) {
            return false;
        }
        try (var stream = Files.list(real)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
            }
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            Files.delete(real);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        try {
            HubResourcePaths.pruneEmptyAncestors(root, real.getParent());
        } catch (IOException ex) {
            // best-effort
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private HubResourceDateSummaryDTO summarizeDateInternal(LocalDate date, Path dateReal) {
        long groupCount = 0L;
        long fileCount = 0L;
        long totalSize = 0L;
        try (var stream = Files.list(dateReal)) {
            for (Path groupPath : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(groupPath, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(groupPath)) {
                    continue;
                }
                groupCount++;
                long[] counts = new long[] { 0L, 0L };
                Files.walkFileTree(groupPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!Files.isSymbolicLink(file)) {
                            counts[0]++;
                            counts[1] += attrs.size();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                fileCount += counts[0];
                totalSize += counts[1];
            }
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
        HubResourceDateSummaryDTO dto = new HubResourceDateSummaryDTO();
        dto.setDate(date.format(HubResourcePaths.DATE_FORMAT));
        dto.setGroupCount(groupCount);
        dto.setFileCount(fileCount);
        dto.setTotalSize(totalSize);
        return dto;
    }

    private HubResourceItemDTO toItemDTO(LocalDate date, String group, String relative, String virtualPath,
                                          HubResourceSource source, String fileName, long size, Path real) {
        HubResourceItemDTO dto = new HubResourceItemDTO();
        dto.setDate(date.format(HubResourcePaths.DATE_FORMAT));
        dto.setGroup(group);
        dto.setRelativePath(relative);
        dto.setResourcePath(virtualPath);
        dto.setFileName(fileName);
        dto.setSource(source);
        dto.setMimeType(HubResourceMimeTypes.probe(real));
        dto.setSize(size);
        try {
            dto.setModifiedAt(LocalDateTime.ofInstant(Files.getLastModifiedTime(real).toInstant(), ZoneOffset.UTC));
        } catch (IOException ex) {
            dto.setModifiedAt(null);
        }
        dto.setContentUrl("/api" + virtualPath + "?inline=true");
        dto.setDownloadUrl("/api" + virtualPath);
        return dto;
    }

    private long writeBounded(InputStream in, Path target, long maxFile, long maxRequest,
                                AtomicLong requestBytes) throws IOException {
        long fileBytes = 0L;
        int bufSize = 64 * 1024;
        byte[] buffer = new byte[bufSize];
        Path tmp = target.resolveSibling("." + UUID.randomUUID() + ".part");
        try (OutputStream out = Files.newOutputStream(tmp,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) {
                    fileBytes += read;
                    long req = requestBytes.addAndGet(read);
                    if (maxFile > 0 && fileBytes > maxFile) {
                        throw new BoundedWriteException("file");
                    }
                    if (maxRequest > 0 && req > maxRequest) {
                        throw new BoundedWriteException("request");
                    }
                    out.write(buffer, 0, read);
                }
            }
            out.flush();
        }
        try {
            Files.move(tmp, target);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // swallow
            }
            throw ex;
        }
        return fileBytes;
    }

    private void deleteQuietly(Path target) {
        if (target == null) {
            return;
        }
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                HubResourcePaths.deleteRecursivelyNoFollow(target);
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target);
            }
        } catch (IOException ex) {
            // best-effort
        }
    }

    private static final class BoundedWriteException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String scope;

        BoundedWriteException(String scope) {
            super("Upload exceeded " + scope + " size limit");
            this.scope = scope;
        }

        String scope() {
            return scope;
        }
    }

    /**
     * Result of a successful upload. Wraps the freshly created group and the list of files
     * that landed inside it. The group identity is exposed so the M6 controller can return a
     * single object to the frontend.
     */
    public static final class UploadResult {
        private final String group;
        private final LocalDate date;
        private final List<HubResourceItemDTO> items;

        public UploadResult(String group, LocalDate date, List<HubResourceItemDTO> items) {
            this.group = group;
            this.date = date;
            this.items = List.copyOf(items);
        }

        public String getGroup() {
            return group;
        }

        public LocalDate getDate() {
            return date;
        }

        public List<HubResourceItemDTO> getItems() {
            return items;
        }
    }

}
