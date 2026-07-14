package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.share.util.HubIds;
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

    /** UUIDs and migrated positive-long ids are the only Hub-managed directory names. */
    public static boolean isManagedInstanceId(String instanceId) {
        return HubIds.isSupported(instanceId);
    }

    public static Path instanceDir(String dataDir, String instanceId) {
        if (!isManagedInstanceId(instanceId)) {
            throw new IllegalArgumentException("Invalid instance id: " + instanceId);
        }
        return instancesRoot(dataDir).resolve(instanceId);
    }

    public static Path chromeDir(String dataDir, String instanceId) {
        return instanceDir(dataDir, instanceId).resolve(DIR_CHROME);
    }

    public static Path logsDir(String dataDir, String instanceId) {
        return instanceDir(dataDir, instanceId).resolve(DIR_LOGS);
    }

    public static Path runtimeDir(String dataDir, String instanceId) {
        return instanceDir(dataDir, instanceId).resolve(DIR_RUNTIME);
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

}
