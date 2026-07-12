package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Production catalog source: shells out to the pinned OpenCLI {@code list -f json} binary
 * and exposes its stdout as the manifest stream.
 *
 * <p>The implementation uses {@link ProcessBuilder} directly, never {@code bash -c},
 * to keep argv injection safe.
 *
 * @author fengwk
 */
@Slf4j
public class ProcessBuilderOpenCliCatalogSource implements OpenCliCatalogSource {

    private final OpenCliHubProperties properties;
    private final long timeoutMillis;

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties) {
        this(properties, 30000L);
    }

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties, long timeoutMillis) {
        this.properties = properties;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public InputStream open() throws IOException {
        try {
            List<String> command = List.of(
                properties.getOpencli().getBinary(),
                "list",
                "-f",
                "json");
            ProcessBuilder builder = new ProcessBuilder(command);
            String workdir = properties.getOpencli().getWorkdir();
            if (workdir != null && !workdir.isBlank()) {
                builder.directory(Path.of(workdir).toFile());
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out waiting for `opencli list -f json` after "
                    + timeoutMillis + " ms");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("`opencli list -f json` exited with code " + exit);
            }
            return process.getInputStream();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading OpenCLI catalog", ex);
        }
    }

}