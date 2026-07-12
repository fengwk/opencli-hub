package fun.fengwk.openclihub.core.opencli.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Test/standalone catalog source that streams a local {@code cli-manifest.json} file.
 *
 * <p>Used by integration tests that need deterministic catalog contents without
 * depending on the pinned {@code opencli} binary being installed on the build agent.
 *
 * @author fengwk
 */
@Slf4j
public class FileOpenCliCatalogSource implements OpenCliCatalogSource {

    private final Path manifestPath;

    public FileOpenCliCatalogSource(Path manifestPath) {
        if (manifestPath == null) {
            throw new IllegalArgumentException("manifestPath must not be null");
        }
        this.manifestPath = manifestPath;
    }

    @Override
    public InputStream open() throws IOException {
        log.debug("Loading OpenCLI catalog from file: {}", manifestPath);
        return Files.newInputStream(manifestPath);
    }

}
