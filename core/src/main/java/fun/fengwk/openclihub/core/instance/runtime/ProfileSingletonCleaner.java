package fun.fengwk.openclihub.core.instance.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes Chrome's volatile per-Profile singleton markers. Each Profile belongs to one Hub
 * instance so these stale files (left behind by an ungraceful container stop or crash) are
 * safe to delete before Chrome starts. Persistent data, cookies, extension storage and
 * login state must NOT be touched.
 *
 * @author fengwk
 */
@Slf4j
public class ProfileSingletonCleaner {

    /** Volatile Chrome process singleton markers that must NOT be persisted across launches. */
    private static final String[] VOLATILE_FILES = {
        "SingletonLock",
        "SingletonSocket",
        "SingletonCookie"
    };

    /**
     * Removes volatile singleton lock files from {@code profileDir} if present.
     *
     * @return count of files removed
     */
    public int cleanStaleSingletons(Path profileDir) {
        if (profileDir == null || !Files.exists(profileDir)) {
            return 0;
        }
        int removed = 0;
        for (String name : VOLATILE_FILES) {
            Path file = profileDir.resolve(name);
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                    log.debug("removed stale Chrome singleton: {}", file);
                }
            } catch (IOException ex) {
                log.warn("failed to remove {}: {}", file, ex.getMessage());
            }
        }
        return removed;
    }

}
