package fun.fengwk.openclihub.core.resource.model;

import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of opening a resource for streaming. The stream is owned by the caller and must be
 * closed via {@link #close()} (typically through try-with-resources) so the underlying
 * {@link fun.fengwk.openclihub.core.resource.service.HubResourceLease} is released in the
 * same step. The {@link Path realPath} is included so callers can stream via
 * {@link java.nio.file.Files#newInputStream} or implement HTTP range support.
 *
 * @author fengwk
 */
@Getter
public final class HubResourceStream implements AutoCloseable {

    private final Path realPath;
    private final String fileName;
    private final String mimeType;
    private final long size;
    private final HubResourceSource source;
    private final InputStream inputStream;
    private final AutoCloseable lease;

    public HubResourceStream(Path realPath, String fileName, String mimeType, long size,
                             HubResourceSource source, InputStream inputStream, AutoCloseable lease) {
        this.realPath = Objects.requireNonNull(realPath, "realPath");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.size = size;
        this.source = source;
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    @Override
    public void close() throws IOException {
        IOException primary = null;
        try {
            inputStream.close();
        } catch (IOException ex) {
            primary = ex;
        }
        try {
            lease.close();
        } catch (Exception ex) {
            if (primary == null && !(ex instanceof IOException)) {
                throw new IOException("Failed to release resource lease", ex);
            }
        }
        if (primary != null) {
            throw primary;
        }
    }

}
