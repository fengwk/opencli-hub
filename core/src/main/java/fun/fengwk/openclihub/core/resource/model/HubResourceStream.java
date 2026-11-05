package fun.fengwk.openclihub.core.resource.model;

import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of opening a resource for streaming. The stream is owned by the caller and must be
 * closed via {@link #close()} (typically through try-with-resources) so the underlying
 * {@link fun.fengwk.openclihub.core.resource.service.HubResourceLease} is released in the
 * same step. The size and ETag are computed from the opened file before streaming.
 *
 * @author fengwk
 */
@Getter
public final class HubResourceStream implements AutoCloseable {

    private final Path realPath;
    private final String fileName;
    private final String mimeType;
    private final long size;
    private final String etag;
    private final HubResourceSource source;
    private final InputStream inputStream;
    private final AutoCloseable lease;
    @Getter(AccessLevel.NONE)
    private final IoCheck consistencyCheck;

    public HubResourceStream(Path realPath, String fileName, String mimeType, long size, String etag,
                             HubResourceSource source, InputStream inputStream, AutoCloseable lease) {
        this(realPath, fileName, mimeType, size, etag, source, inputStream, lease, () -> {});
    }

    public HubResourceStream(Path realPath, String fileName, String mimeType, long size, String etag,
                             HubResourceSource source, InputStream inputStream, AutoCloseable lease,
                             IoCheck consistencyCheck) {
        this.realPath = Objects.requireNonNull(realPath, "realPath");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.size = size;
        this.etag = Objects.requireNonNull(etag, "etag");
        this.source = source;
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.consistencyCheck = Objects.requireNonNull(consistencyCheck, "consistencyCheck");
    }

    @FunctionalInterface
    public interface IoCheck {
        void verify() throws IOException;
    }

    /** Recheck the source before completing a bounded transfer. */
    public void verifyUnchanged() throws IOException {
        consistencyCheck.verify();
    }

    @Override
    public void close() throws IOException {
        Throwable inputFailure = null;
        Throwable leaseFailure = null;
        try {
            inputStream.close();
        } catch (Throwable t) {
            inputFailure = t;
        }
        try {
            lease.close();
        } catch (Throwable t) {
            leaseFailure = t;
        }
        if (inputFailure != null) {
            // Input stream failure is always the primary; the lease-release problem (if any)
            // is reported as suppressed so the caller can inspect both at once.
            if (leaseFailure != null) {
                inputFailure.addSuppressed(leaseFailure);
            }
            if (inputFailure instanceof IOException io) {
                throw io;
            }
            if (inputFailure instanceof RuntimeException re) {
                throw re;
            }
            if (inputFailure instanceof Error err) {
                throw err;
            }
            throw new IOException("Failed to close resource input", inputFailure);
        }
        if (leaseFailure != null) {
            if (leaseFailure instanceof IOException io) {
                throw io;
            }
            if (leaseFailure instanceof RuntimeException re) {
                throw re;
            }
            if (leaseFailure instanceof Error err) {
                throw err;
            }
            throw new IOException("Failed to release resource lease", leaseFailure);
        }
    }

}
