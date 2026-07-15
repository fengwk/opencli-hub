package fun.fengwk.openclihub.core.instance.runtime;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reconciles the on-disk instance directory tree against the database after an ungraceful
 * Hub shutdown. Only canonical UUID and migrated positive-long directory names are managed
 * automatically. Symlinks and every other non-canonical entry are protected.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class OrphanInstanceScanner {

    private final OpenCliHubProperties properties;
    private final HubInstanceService instanceService;

    public OrphanInstanceScanner(OpenCliHubProperties properties, HubInstanceService instanceService) {
        this.properties = properties;
        this.instanceService = instanceService;
    }

    /** Result counters useful for logging and tests. */
    public static final class Result {
        public int creatingOrphanDeleted;
        public int creatingMarkerRemoved;
        public int managedOrphanDeleted;
        public int unsafeNameProtected;

        public int total() {
            return creatingOrphanDeleted + creatingMarkerRemoved
                + managedOrphanDeleted + unsafeNameProtected;
        }
    }

    /** Performs a single scan and returns the counters. */
    public Result scan() {
        Result result = new Result();
        Path root = HubInstanceDirectoryLayout.instancesRoot(properties.getDataDir());
        if (!isRealInstancesRoot(root, result)) {
            return result;
        }
        Set<String> knownIds = new HashSet<>();
        for (HubInstance instance : instanceService.list()) {
            knownIds.add(instance.getId());
        }
        if (!isRealInstancesRoot(root, result)) {
            return result;
        }
        try (var stream = Files.list(root)) {
            stream.forEach(entry -> inspectEntry(entry, knownIds, result));
        } catch (IOException ex) {
            log.error("orphan scan failed to list {}: {}", root, ex.getMessage());
        }
        if (result.total() > 0) {
            log.info("orphan scan: creatingOrphanDeleted={} creatingMarkerRemoved={} "
                + "managedOrphanDeleted={} unsafeNameProtected={}",
                result.creatingOrphanDeleted, result.creatingMarkerRemoved,
                result.managedOrphanDeleted, result.unsafeNameProtected);
        }
        return result;
    }

    private boolean isRealInstancesRoot(Path root, Result result) {
        if (Files.isSymbolicLink(root)) {
            log.warn("orphan scanner: protected symbolic link instances root: {}", root);
            result.unsafeNameProtected++;
            return false;
        }
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            log.warn("orphan scanner: protected non-directory instances root: {}", root);
            result.unsafeNameProtected++;
            return false;
        }
        return true;
    }

    private void inspectEntry(Path entry, Set<String> knownIds, Result result) {
        if (!isDirectoryEntry(entry, result)) {
            return;
        }
        classify(entry, knownIds, result);
    }

    private boolean isDirectoryEntry(Path entry, Result result) {
        if (Files.isSymbolicLink(entry)) {
            log.warn("orphan scanner: protected symbolic link: {}", entry);
            result.unsafeNameProtected++;
            return false;
        }
        return Files.isDirectory(entry, NOFOLLOW_LINKS);
    }

    private void classify(Path dir, Set<String> knownIds, Result result) {
        // The entry may have been replaced after Files.list emitted it.
        if (!isDirectoryEntry(dir, result)) {
            return;
        }
        String id = dir.getFileName().toString();
        if (!HubInstanceDirectoryLayout.isManagedInstanceId(id)) {
            log.warn("orphan scanner: protected directory with unmanaged name: {}", dir);
            result.unsafeNameProtected++;
            return;
        }
        Path marker = dir.resolve(HubInstanceDirectoryLayout.MARKER_CREATING);
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            handleCreating(dir, id, marker, knownIds, result);
            return;
        }
        if (!knownIds.contains(id)) {
            if (!isDirectoryEntry(dir, result)) {
                return;
            }
            try {
                deleteRecursively(dir);
                log.warn("orphan scanner: removed managed directory with no DB row: {}", dir);
                result.managedOrphanDeleted++;
            } catch (IOException ex) {
                log.warn("orphan scanner: failed to delete {}: {}", dir, ex.getMessage());
            }
        }
    }

    private void handleCreating(Path dir, String id, Path marker, Set<String> knownIds,
                                Result result) {
        if (knownIds.contains(id)) {
            if (!isDirectoryEntry(dir, result)) {
                return;
            }
            try {
                Files.deleteIfExists(marker);
                log.info("orphan scanner: removed leftover .creating marker for instance {}", id);
                result.creatingMarkerRemoved++;
            } catch (IOException ex) {
                log.warn("orphan scanner: failed to remove marker {}: {}", marker,
                    ex.getMessage());
            }
            return;
        }
        if (!isDirectoryEntry(dir, result)) {
            return;
        }
        try {
            deleteRecursively(dir);
            log.warn("orphan scanner: removed .creating orphan directory: {}", dir);
            result.creatingOrphanDeleted++;
        } catch (IOException ex) {
            log.warn("orphan scanner: failed to delete {}: {}", dir, ex.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, java.util.Set.of(),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
    }

}
