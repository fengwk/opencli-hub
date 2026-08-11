package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Owns the on-disk instance directory lifecycle: ensure/require of the layout, the
 * {@code .creating} create-handshake marker, recursive deletion and the create-rollback
 * file cleanup.
 *
 * <p>Every path is validated through {@link HubInstanceDirectoryLayout} so a symbolic link
 * or a non-directory can never redirect writes or deletes outside the managed instances root.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceFiles {

    /**
     * Result of ensuring an instance directory tree. Log paths are derived from the logs dir.
     */
    public record InstanceDirectories(
        Path instanceDir,
        Path chromeDir,
        Path xvfbLog,
        Path openboxLog,
        Path x11vncLog,
        Path chromeLog) {
    }

    private final String dataDir;

    public HubInstanceFiles(OpenCliHubProperties properties) {
        this.dataDir = properties.getDataDir();
    }

    /**
     * Creates the instances root, instance dir and fixed child dirs when absent and verifies
     * none of them became a symbolic link or a non-directory.
     */
    public InstanceDirectories ensureDirectories(String instanceId) throws IOException {
        Path instancesRoot = HubInstanceDirectoryLayout.ensureRealInstancesRoot(dataDir);
        Path instanceDir = HubInstanceDirectoryLayout.ensureRealInstanceDirectory(instancesRoot, instanceId);
        Path chromeDir = HubInstanceDirectoryLayout.ensureRealInstanceChildDirectory(
            instanceDir, HubInstanceDirectoryLayout.DIR_CHROME);
        Path logsDir = HubInstanceDirectoryLayout.ensureRealInstanceChildDirectory(
            instanceDir, HubInstanceDirectoryLayout.DIR_LOGS);
        HubInstanceDirectoryLayout.ensureRealInstanceChildDirectory(
            instanceDir, HubInstanceDirectoryLayout.DIR_RUNTIME);
        return new InstanceDirectories(
            instanceDir,
            chromeDir,
            logsDir.resolve(HubInstanceDirectoryLayout.LOG_XVFB),
            logsDir.resolve(HubInstanceDirectoryLayout.LOG_OPENBOX),
            logsDir.resolve(HubInstanceDirectoryLayout.LOG_X11VNC),
            logsDir.resolve(HubInstanceDirectoryLayout.LOG_CHROME));
    }

    /**
     * Validates that the instance tree is present and real (each entry is absent or a real
     * directory, never a symbolic link). Fails with the given domain error code when the
     * tree is unsafe.
     */
    public void requireSafeDirectories(String instanceId, HubErrorCodes errorCode) {
        try {
            Path instancesRoot = HubInstanceDirectoryLayout.requireRealInstancesRoot(dataDir);
            Path instanceDir = HubInstanceDirectoryLayout.requireRealInstanceDirectory(
                instancesRoot, instanceId);
            HubInstanceDirectoryLayout.requireRealInstanceChildDirectory(
                instanceDir, HubInstanceDirectoryLayout.DIR_CHROME);
            HubInstanceDirectoryLayout.requireRealInstanceChildDirectory(
                instanceDir, HubInstanceDirectoryLayout.DIR_LOGS);
            HubInstanceDirectoryLayout.requireRealInstanceChildDirectory(
                instanceDir, HubInstanceDirectoryLayout.DIR_RUNTIME);
        } catch (IOException ex) {
            throw errorCode.asThrowable(ex, "unsafe instance directory: " + ex.getMessage());
        }
    }

    /**
     * Writes the {@code .creating} marker used by the create handshake. Fails when the
     * marker already exists so a leftover marker from a crashed create is never silently
     * reused.
     */
    public void createCreatingMarker(String instanceId) throws IOException {
        Path instancesRoot = HubInstanceDirectoryLayout.ensureRealInstancesRoot(dataDir);
        Path instanceDir = HubInstanceDirectoryLayout.ensureRealInstanceDirectory(
            instancesRoot, instanceId);
        Files.createFile(instanceDir.resolve(HubInstanceDirectoryLayout.MARKER_CREATING));
    }

    /** Removes the {@code .creating} marker when present. */
    public void deleteCreatingMarker(String instanceId) throws IOException {
        Path instancesRoot = HubInstanceDirectoryLayout.requireRealInstancesRoot(dataDir);
        Path instanceDir = HubInstanceDirectoryLayout.requireRealInstanceDirectory(
            instancesRoot, instanceId);
        Files.deleteIfExists(instanceDir.resolve(HubInstanceDirectoryLayout.MARKER_CREATING));
    }

    /**
     * Recursively deletes the instance directory. Fails with {@code INSTANCE_DELETE_FAILED}
     * when the tree cannot be validated as real or the deletion cannot complete.
     */
    public void deleteInstanceDirectory(String instanceId) {
        try {
            Path instancesRoot = HubInstanceDirectoryLayout.requireRealInstancesRoot(dataDir);
            Path dir = HubInstanceDirectoryLayout.requireRealInstanceDirectory(
                instancesRoot, instanceId);
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            deleteRecursively(dir);
        } catch (IOException ex) {
            throw HubErrorCodes.INSTANCE_DELETE_FAILED.asThrowable(
                ex, "delete instance directory failed: " + ex.getMessage());
        }
    }

    /**
     * Best-effort create-rollback file cleanup: drops the {@code .creating} marker and then
     * removes the instance directory. Failures are logged, never thrown — the create failure
     * itself is what propagates to the caller.
     */
    public void cleanupCreateFailureArtifacts(String instanceId) {
        try {
            deleteCreatingMarker(instanceId);
        } catch (IOException ex) {
            log.warn("failed to remove .creating marker for instance {}: {}",
                instanceId, ex.getMessage());
        }
        try {
            deleteInstanceDirectory(instanceId);
        } catch (RuntimeException ex) {
            log.warn("failed to remove instance {} directory during rollback: {}",
                instanceId, ex.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // An empty option set means "do not follow symlinks". Symlinks are deleted as entries,
        // never traversed, so they cannot escape the instance root.
        Files.walkFileTree(root, Set.of(),
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
