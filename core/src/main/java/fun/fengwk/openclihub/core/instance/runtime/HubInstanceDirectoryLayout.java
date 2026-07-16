package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.share.util.HubIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Owns the on-disk shape of an Instance directory. Centralised so the lifecycle layer and the
 * orphan scanner stay byte-for-byte aligned.
 *
 * <pre>
 * {root}/instances/{instanceId}/
 *   chrome/      Chrome user-data-dir
 *   logs/        Per-process log files (xvfb / openbox / x11vnc / chrome)
 *   runtime/     reserved for volatile per-instance runtime files
 *   .creating    Marker file written during the create handshake, removed once the row is
 *                inserted. Survives crashes so the orphan scanner can decide whether to
 *                wipe or keep the directory.
 * </pre>
 *
 * @author fengwk
 */
public final class HubInstanceDirectoryLayout {

    public static final String MARKER_CREATING = ".creating";

    public static final String DIR_CHROME = "chrome";
    public static final String DIR_LOGS = "logs";
    public static final String DIR_RUNTIME = "runtime";

    public static final String LOG_XVFB = "xvfb.log";
    public static final String LOG_OPENBOX = "openbox.log";
    public static final String LOG_X11VNC = "x11vnc.log";
    public static final String LOG_CHROME = "chrome.log";

    private HubInstanceDirectoryLayout() {
    }

    /** Root directory that hosts the {@code instances} tree. */
    public static Path instancesRoot(String dataDir) {
        return Path.of(dataDir, "instances");
    }

    /**
     * Validates that the instances root is either absent or a real directory, never a
     * symbolic link. Absent roots are allowed for read-only callers.
     */
    public static Path requireRealInstancesRoot(String dataDir) throws IOException {
        return requireRealDirectory(instancesRoot(dataDir), "instances root");
    }

    /** Creates the instances root when absent and verifies it did not become unsafe. */
    public static Path ensureRealInstancesRoot(String dataDir) throws IOException {
        Path root = requireRealInstancesRoot(dataDir);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root);
        }
        return requireRealInstancesRoot(dataDir);
    }

    /** UUIDs and migrated positive-long ids are the only Hub-managed directory names. */
    public static boolean isManagedInstanceId(String instanceId) {
        return HubIds.isSupported(instanceId);
    }

    public static Path instanceDir(String dataDir, String instanceId) {
        return instanceDir(instancesRoot(dataDir), instanceId);
    }

    public static Path instanceDir(Path instancesRoot, String instanceId) {
        if (!isManagedInstanceId(instanceId)) {
            throw new IllegalArgumentException("Invalid instance id: " + instanceId);
        }
        return instancesRoot.resolve(instanceId);
    }

    /** Validates that an instance directory is either absent or a real directory. */
    public static Path requireRealInstanceDirectory(Path instancesRoot, String instanceId)
        throws IOException {
        requireRealDirectory(instancesRoot, "instances root");
        return requireRealDirectory(instanceDir(instancesRoot, instanceId), "instance directory");
    }

    /** Creates an instance directory when absent and verifies it did not become unsafe. */
    public static Path ensureRealInstanceDirectory(Path instancesRoot, String instanceId)
        throws IOException {
        Path dir = requireRealInstanceDirectory(instancesRoot, instanceId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir);
        }
        return requireRealInstanceDirectory(instancesRoot, instanceId);
    }

    public static Path chromeDir(String dataDir, String instanceId) {
        return chromeDir(instancesRoot(dataDir), instanceId);
    }

    public static Path chromeDir(Path instancesRoot, String instanceId) {
        return instanceDir(instancesRoot, instanceId).resolve(DIR_CHROME);
    }

    public static Path logsDir(String dataDir, String instanceId) {
        return logsDir(instancesRoot(dataDir), instanceId);
    }

    public static Path logsDir(Path instancesRoot, String instanceId) {
        return instanceDir(instancesRoot, instanceId).resolve(DIR_LOGS);
    }

    public static Path runtimeDir(String dataDir, String instanceId) {
        return runtimeDir(instancesRoot(dataDir), instanceId);
    }

    public static Path runtimeDir(Path instancesRoot, String instanceId) {
        return instanceDir(instancesRoot, instanceId).resolve(DIR_RUNTIME);
    }

    public static Path creatingMarker(String dataDir, String instanceId) {
        return instanceDir(dataDir, instanceId).resolve(MARKER_CREATING);
    }

    public static Path xvfbLog(String dataDir, String instanceId) {
        return logsDir(dataDir, instanceId).resolve(LOG_XVFB);
    }

    public static Path openboxLog(String dataDir, String instanceId) {
        return logsDir(dataDir, instanceId).resolve(LOG_OPENBOX);
    }

    public static Path x11vncLog(String dataDir, String instanceId) {
        return logsDir(dataDir, instanceId).resolve(LOG_X11VNC);
    }

    public static Path chromeLog(String dataDir, String instanceId) {
        return logsDir(dataDir, instanceId).resolve(LOG_CHROME);
    }

    /** Validates that a fixed instance child directory is absent or a real directory. */
    public static Path requireRealInstanceChildDirectory(Path instanceDir, String childName)
        throws IOException {
        requireRealDirectory(instanceDir, "instance directory");
        return requireRealDirectory(instanceChildDirectory(instanceDir, childName),
            "instance child directory");
    }

    /** Creates a fixed instance child directory when absent and verifies it did not become unsafe. */
    public static Path ensureRealInstanceChildDirectory(Path instanceDir, String childName)
        throws IOException {
        Path child = requireRealInstanceChildDirectory(instanceDir, childName);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(child);
        }
        return requireRealInstanceChildDirectory(instanceDir, childName);
    }

    private static Path requireRealDirectory(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " must not be a symbolic link: " + path);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a directory: " + path);
        }
        return path;
    }

    private static Path instanceChildDirectory(Path instanceDir, String childName) {
        if (!DIR_CHROME.equals(childName) && !DIR_LOGS.equals(childName)
            && !DIR_RUNTIME.equals(childName)) {
            throw new IllegalArgumentException("Unsupported instance child directory: " + childName);
        }
        return instanceDir.resolve(childName);
    }

}
