package fun.fengwk.openclihub.core.opencli.catalog;

import java.io.IOException;
import java.io.InputStream;

/**
 * Strategy for acquiring the raw OpenCLI {@code cli-manifest.json} bytes.
 *
 * <p>Two implementations live alongside this interface: one shells out to the pinned
 * {@code opencli list -f json} CLI binary, the other reads a local file. Tests prefer
 * the local file source because it avoids spawning a Node process and pinning the CLI.
 *
 * @author fengwk
 */
public interface OpenCliCatalogSource {

    /**
     * Open a stream over the raw catalog JSON. The caller owns the stream and must close it.
     *
     * @throws IOException when the source cannot be opened
     */
    InputStream open() throws IOException;

}