package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency-safe registry tracking active leases per real path. The manager rejects a
 * delete request whenever the held count for the target path is greater than zero. Both the
 * file-level and group-level deletion paths go through {@link #assertDeletable(Path)}.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubResourceLeaseManager {

    private final Map<Path, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Acquire a lease on {@code realPath}. The caller is responsible for invoking
     * {@link HubResourceLease#close()} (typically via try-with-resources) so the counter
     * returns to zero. The lease is shared between concurrent readers, so multiple
     * acquisitions increment the same counter until each holder closes.
     */
    public HubResourceLease acquire(Path realPath, String reason) {
        Objects.requireNonNull(realPath, "realPath");
        AtomicInteger counter = counters.computeIfAbsent(
            realPath.toAbsolutePath().normalize(),
            k -> new AtomicInteger());
        counter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("Acquired lease {} reason={} count={}", realPath, reason, counter.get());
        }
        return new HubResourceLease(this, realPath, reason, counter);
    }

    /**
     * Verify that no active lease exists for {@code realPath} (or any descendant directory).
     * Throws {@code RESOURCE_IN_USE} when the resource is currently held.
     */
    public void assertDeletable(Path realPath) {
        Path target = realPath.toAbsolutePath().normalize();
        if (counters.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, AtomicInteger> entry : counters.entrySet()) {
            if (entry.getValue().get() <= 0) {
                continue;
            }
            if (entry.getKey().equals(target) || entry.getKey().startsWith(target) || target.startsWith(entry.getKey())) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
        }
    }

    /**
     * Release the previously acquired lease by decrementing its counter. When the counter
     * drops to zero the entry is removed so the map cannot grow unbounded.
     */
    void release(HubResourceLease lease, AtomicInteger counter) {
        int remaining = counter.decrementAndGet();
        if (remaining < 0) {
            // Defensive reset; this should never trigger under correct usage.
            counter.set(0);
            remaining = 0;
        }
        if (remaining == 0) {
            counters.compute(lease.realPath().toAbsolutePath().normalize(), (k, v) -> v == null || v.get() <= 0 ? null : v);
        }
    }

    /**
     * For tests: how many distinct paths currently hold an outstanding lease.
     */
    public int heldPathCount() {
        int count = 0;
        for (AtomicInteger counter : counters.values()) {
            if (counter.get() > 0) {
                count++;
            }
        }
        return count;
    }

}
