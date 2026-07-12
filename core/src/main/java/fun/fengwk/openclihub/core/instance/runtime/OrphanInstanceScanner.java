package fun.fengwk.openclihub.core.instance.runtime;

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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reconciles the on-disk instance directory tree against the database after an ungraceful
 * Hub shutdown. Implements design §16.7:
 * <ul>
 *   <li>{@code .creating} + no DB row -> delete the orphan directory (create never inserted).</li>
 *   <li>{@code .creating} + DB row -> remove the marker only (create completed; the marker
 *       removal step didn't run).</li>
 *   <li>Pure-numeric directory name + no DB row -> delete (a delete-in-DB-but-not-on-disk
 *       hang).</li>
 *   <li>Non-numeric directory -> NEVER auto-delete; surface as warning so operators can
 *       triage manually.</li>
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
@Component
public class OrphanInstanceScanner {

    private static final Pattern NUMERIC_NAME = Pattern.compile("^[0-9]+$");

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
        public int numericOrphanDeleted;
        public int nonNumericProtected;

        public int total() {
            return creatingOrphanDeleted + creatingMarkerRemoved
                + numericOrphanDeleted + nonNumericProtected;
        }
    }

    /**
     * Performs a single scan and returns the counters.
     */
    public Result scan() {
        Result result = new Result();
        Path root = HubInstanceDirectoryLayout.instancesRoot(properties.getDataDir());
        if (!Files.exists(root)) {
            return result;
        }
        Set<Long> knownIds = new HashSet<>();
        for (HubInstance instance : instanceService.list()) {
            knownIds.add(instance.getId());
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .forEach(dir -> classify(dir, knownIds, result));
        } catch (IOException ex) {
            log.error("orphan scan failed to list {}: {}", root, ex.getMessage());
        }
        if (result.total() > 0) {
            log.info("orphan scan: creatingOrphanDeleted={} creatingMarkerRemoved={} "
                + "numericOrphanDeleted={} nonNumericProtected={}",
                result.creatingOrphanDeleted, result.creatingMarkerRemoved,
                result.numericOrphanDeleted, result.nonNumericProtected);
        }
        return result;
    }

    private void classify(Path dir, Set<Long> knownIds, Result result) {
        String name = dir.getFileName().toString();
        if (!NUMERIC_NAME.matcher(name).matches()) {
            log.warn("orphan scanner: protected non-numeric directory: {}", dir);
            result.nonNumericProtected++;
            return;
        }
        long id;
        try {
            id = Long.parseLong(name);
        } catch (NumberFormatException ex) {
            log.warn("orphan scanner: protected out-of-range numeric directory: {}", dir);
            result.nonNumericProtected++;
            return;
        }
        Path marker = dir.resolve(HubInstanceDirectoryLayout.MARKER_CREATING);
        if (Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            handleCreating(dir, id, marker, knownIds, result);
            return;
        }
        if (!knownIds.contains(id)) {
            try {
                deleteRecursively(dir);
                log.warn("orphan scanner: removed numeric directory with no DB row: {}", dir);
                result.numericOrphanDeleted++;
            } catch (IOException ex) {
                log.warn("orphan scanner: failed to delete {}: {}", dir, ex.getMessage());
            }
        }
    }

    private void handleCreating(Path dir, long id, Path marker, Set<Long> knownIds,
        Result result) {
        if (knownIds.contains(id)) {
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
