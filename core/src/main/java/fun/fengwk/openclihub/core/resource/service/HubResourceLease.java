package fun.fengwk.openclihub.core.resource.service;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted lease protecting a single real path. A lease is acquired before any
 * execution reads or writes the underlying resource, and is released by {@link #close()} or
 * by walking out of scope. If the caller's code throws, {@link #close()} must still run, so
 * the recommended use is try-with-resources.
 * <p>
 * Leases are intentionally in-memory only. Restarting the Hub simply abandons them; the
 * underlying files are still safe to delete on next request.
 *
 * @author fengwk
 */
@Slf4j
public final class HubResourceLease implements AutoCloseable {

    private final Path realPath;
    private final HubResourceLeaseManager manager;
    private final AtomicInteger referenceCount;
    private final String reason;
    private boolean closed;

    HubResourceLease(HubResourceLeaseManager manager, Path realPath, String reason, AtomicInteger referenceCount) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.realPath = Objects.requireNonNull(realPath, "realPath");
        this.referenceCount = Objects.requireNonNull(referenceCount, "referenceCount");
        this.reason = reason == null ? "unspecified" : reason;
    }

    /**
     * Real path protected by this lease.
     */
    public Path realPath() {
        return realPath;
    }

    /**
     * Reason supplied when the lease was acquired. Useful for diagnostics.
     */
    public String reason() {
        return reason;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        manager.release(this, referenceCount);
        if (log.isDebugEnabled()) {
            log.debug("Released lease {} reason={}", realPath, reason);
        }
    }

}
