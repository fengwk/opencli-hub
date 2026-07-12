package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrency-safe registry tracking active leases per real path. The manager uses a single
 * metadata lock to serialize changes to the {@code shared} and {@code deleting} maps, both
 * of which clean up after themselves so the manager's memory footprint remains bounded by
 * the live concurrency. Per-path locks are intentionally NOT used: this avoids the unbounded
 * growth of an entry-per-Path map and keeps the critical section trivially short.
 * <p>
 * Rule matrix (all paths normalized; {@code A~B} means {@code A.startsWith(B) || B.startsWith(A)}):
 * <ul>
 *   <li>{@link #acquire(Path, String)} refuses any shared lease while a destructive delete
 *       reservation overlaps the target (ancestor, descendant, or same path).</li>
 *   <li>{@link #runExclusively(Path, Runnable)} refuses any destructive delete while a shared
 *       lease overlaps the target.</li>
 *   <li>Both rules are evaluated under the metadata lock so a concurrent acquirer cannot slip
 *       between the assert and the actual delete.</li>
 * </ul>
 * Leases are intentionally in-memory only; restarting the Hub simply abandons them.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubResourceLeaseManager {

    /** Serializes any state mutation on {@link #shared} or {@link #deleting}. */
    private final ReentrantLock metaLock = new ReentrantLock();

    /** Number of in-progress shared leases per real path. */
    private final Map<Path, AtomicInteger> shared = new ConcurrentHashMap<>();

    /** Counter driven to 1 by an active {@link #runExclusively} reservation. */
    private final Map<Path, AtomicInteger> deleting = new ConcurrentHashMap<>();

    /**
     * Acquire a shared lease on {@code realPath}. Throws {@code RESOURCE_IN_USE} when a
     * destructive delete reservation overlaps the target or any ancestor/descendant of it.
     */
    public HubResourceLease acquire(Path realPath, String reason) {
        Objects.requireNonNull(realPath, "realPath");
        Path abs = realPath.toAbsolutePath().normalize();
        metaLock.lock();
        try {
            if (overlapsAnyReservation(abs)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
            AtomicInteger counter = shared.computeIfAbsent(abs, k -> new AtomicInteger());
            counter.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Acquired lease {} reason={} count={}", abs, reason, counter.get());
            }
            return new HubResourceLease(this, realPath, reason, counter);
        } finally {
            metaLock.unlock();
        }
    }

    /**
     * Run {@code action} while holding a destructive delete reservation on {@code realPath}.
     * The check + flag set happen under {@link #metaLock} so a concurrent acquirer cannot
     * squeeze between the assert and the actual delete. Returns normally if the action
     * completes; rethrows whatever the action throws (with the reservation cleared first).
     */
    public void runExclusively(Path realPath, Runnable action) {
        Objects.requireNonNull(realPath, "realPath");
        Path abs = realPath.toAbsolutePath().normalize();
        metaLock.lock();
        boolean reserved = false;
        try {
            if (overlapsAnySharedLease(abs)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
            AtomicInteger flag = deleting.computeIfAbsent(abs, k -> new AtomicInteger());
            if (!flag.compareAndSet(0, 1)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
            reserved = true;
        } finally {
            metaLock.unlock();
        }
        Throwable actionFailure = null;
        try {
            action.run();
        } catch (Throwable t) {
            actionFailure = t;
        } finally {
            metaLock.lock();
            try {
                AtomicInteger flag = deleting.get(abs);
                if (flag != null) {
                    flag.compareAndSet(1, 0);
                    if (deleting.get(abs) != null && deleting.get(abs).get() <= 0) {
                        deleting.remove(abs, flag);
                    }
                }
            } finally {
                metaLock.unlock();
            }
        }
        if (actionFailure instanceof RuntimeException re) {
            throw re;
        }
        if (actionFailure != null) {
            throw new RuntimeException("Destructive action failed", actionFailure);
        }
    }

    /**
     * Verify that no active lease exists for {@code realPath} (or any descendant) and that
     * no destructive delete is in progress on it. Pure check; callers that plan to delete
     * should use {@link #runExclusively(Path, Runnable)} so the check and the delete share
     * a single critical section.
     */
    public void assertDeletable(Path realPath) {
        Path target = realPath.toAbsolutePath().normalize();
        for (Map.Entry<Path, AtomicInteger> entry : shared.entrySet()) {
            if (entry.getValue().get() <= 0) {
                continue;
            }
            Path key = entry.getKey();
            if (key.equals(target) || key.startsWith(target) || target.startsWith(key)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
        }
        for (Map.Entry<Path, AtomicInteger> entry : deleting.entrySet()) {
            if (entry.getValue().get() <= 0) {
                continue;
            }
            Path key = entry.getKey();
            if (key.equals(target) || key.startsWith(target)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
        }
    }

    /**
     * Release the previously acquired lease by decrementing its counter. When the counter
     * drops to zero the entry is removed so the map cannot grow unbounded.
     */
    void release(HubResourceLease lease, AtomicInteger counter) {
        Path abs = lease.realPath().toAbsolutePath().normalize();
        metaLock.lock();
        try {
            int remaining = counter.decrementAndGet();
            if (remaining < 0) {
                counter.set(0);
                remaining = 0;
            }
            if (remaining == 0) {
                shared.remove(abs, counter);
            }
        } finally {
            metaLock.unlock();
        }
    }

    /**
     * How many distinct paths currently hold a shared lease.
     */
    public int heldPathCount() {
        int count = 0;
        for (AtomicInteger counter : shared.values()) {
            if (counter.get() > 0) {
                count++;
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------------------------
    // Bidirectional overlap checks. Both directions (ancestor and descendant) are evaluated
    // so that acquire and runExclusively share the same semantics for path overlap.
    // -----------------------------------------------------------------------------------------

    private boolean overlapsAnyReservation(Path abs) {
        for (Map.Entry<Path, AtomicInteger> entry : deleting.entrySet()) {
            if (entry.getValue().get() <= 0) {
                continue;
            }
            Path key = entry.getKey();
            if (key.equals(abs) || key.startsWith(abs) || abs.startsWith(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsAnySharedLease(Path abs) {
        for (Map.Entry<Path, AtomicInteger> entry : shared.entrySet()) {
            if (entry.getValue().get() <= 0) {
                continue;
            }
            Path key = entry.getKey();
            if (key.equals(abs) || key.startsWith(abs) || abs.startsWith(key)) {
                return true;
            }
        }
        return false;
    }

}
