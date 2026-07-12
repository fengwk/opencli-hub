package fun.fengwk.openclihub.core.resource.service;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrency-safe registry tracking active leases per real path. The manager refuses a
 * destructive delete whenever a shared lease exists on the target or any descendant; it also
 * refuses a shared lease whenever a destructive delete is in progress on the target or any
 * of its ancestors. {@link #runExclusively(Path, Runnable)} performs the check + flag set
 * under a single per-path lock so the gap between "check" and "delete" can no longer be
 * exploited by a concurrent acquirer.
 * <p>
 * Leases are intentionally in-memory only; restarting the Hub simply abandons them. The
 * underlying files remain safe to delete on the next request.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubResourceLeaseManager {

    private final Map<Path, AtomicInteger> shared = new ConcurrentHashMap<>();
    private final Map<Path, AtomicInteger> deleting = new ConcurrentHashMap<>();
    private final Map<Path, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    /**
     * Acquire a shared lease on {@code realPath}. The caller is responsible for invoking
     * {@link HubResourceLease#close()} (typically via try-with-resources). Acquiring on a
     * path or any ancestor that currently has a destructive delete in progress throws
     * {@code RESOURCE_IN_USE}.
     */
    public HubResourceLease acquire(Path realPath, String reason) {
        Objects.requireNonNull(realPath, "realPath");
        Path abs = realPath.toAbsolutePath().normalize();
        List<ReentrantLock> held = new ArrayList<>();
        try {
            // Walk from the target up to the file-system root. Each ancestor lock is held
            // only for the duration of the delete-flag check, so the critical section is
            // strictly bounded.
            for (Path p = abs; p != null; p = p.getParent()) {
                ReentrantLock lock = lockFor(p);
                lock.lock();
                held.add(lock);
                AtomicInteger inProgress = deleting.get(p);
                if (inProgress != null && inProgress.get() > 0) {
                    throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
                }
            }
            AtomicInteger counter = shared.computeIfAbsent(abs, k -> new AtomicInteger());
            counter.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Acquired lease {} reason={} count={}", abs, reason, counter.get());
            }
            return new HubResourceLease(this, realPath, reason, counter);
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    /**
     * Run {@code action} while holding a destructive delete reservation on
     * {@code realPath}. The check + flag set happens under a single per-path lock so that
     * {@link #acquire(Path, String)} arriving on the same path or any descendant cannot
     * sneak between the assert and the actual delete. Returns normally if the action
     * completes; rethrows whatever the action throws (with the reservation cleared first).
     */
    public void runExclusively(Path realPath, Runnable action) {
        Objects.requireNonNull(realPath, "realPath");
        Path abs = realPath.toAbsolutePath().normalize();
        ReentrantLock lock = lockFor(abs);
        lock.lock();
        boolean reserved = false;
        try {
            // Reject when any active lease is on the target, a descendant, or an ancestor.
            for (Map.Entry<Path, AtomicInteger> entry : shared.entrySet()) {
                if (entry.getValue().get() <= 0) {
                    continue;
                }
                Path key = entry.getKey();
                if (key.equals(abs) || key.startsWith(abs) || abs.startsWith(key)) {
                    throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
                }
            }
            // Only one destructive delete at a time on the same target.
            AtomicInteger flag = deleting.computeIfAbsent(abs, k -> new AtomicInteger());
            if (!flag.compareAndSet(0, 1)) {
                throw HubErrorCodes.RESOURCE_IN_USE.asThrowable();
            }
            reserved = true;
        } finally {
            lock.unlock();
        }
        Throwable actionFailure = null;
        try {
            action.run();
        } catch (Throwable t) {
            actionFailure = t;
        } finally {
            lock.lock();
            try {
                AtomicInteger flag = deleting.get(abs);
                if (flag != null) {
                    flag.compareAndSet(1, 0);
                    if (deleting.get(abs) != null && deleting.get(abs).get() <= 0) {
                        deleting.remove(abs, flag);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        if (actionFailure instanceof RuntimeException) {
            throw (RuntimeException) actionFailure;
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
        int remaining = counter.decrementAndGet();
        if (remaining < 0) {
            counter.set(0);
            remaining = 0;
        }
        if (remaining == 0) {
            shared.compute(lease.realPath().toAbsolutePath().normalize(),
                (k, v) -> v == null || v.get() <= 0 ? null : v);
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

    private ReentrantLock lockFor(Path path) {
        return pathLocks.computeIfAbsent(path, k -> new ReentrantLock());
    }

}
