package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hub-level start coordinator that staggers starts of overlapping
 * {@link HubExecutionConcurrencyMode#PARALLEL_SAFE} executions targeting the same normalized site.
 *
 * <p>Concurrency semantics:
 * <ul>
 *   <li>Each site coordinates independently through its own FIFO fair start gate ({@link ReentrantLock}(true)).</li>
 *   <li>When no same-site parallel execution or reservation is active, the first execution starts immediately.</li>
 *   <li>Overlapping executions acquire the per-site start gate in FIFO order and wait until the previous
 *       actual start's {@code nextAllowedStartNanos}.</li>
 *   <li>At actual admission, current monotonic time is captured and {@code nextAllowedStartNanos} is set to
 *       {@code actualStart + fresh random interval} in inclusive {@code [minMillis, maxMillis]}. The start
 *       gate is released before the task action is executed, guaranteeing that subsequent starts observe at
 *       least the configured gap even if a prior wait overslept.</li>
 *   <li>Stagger wait consumes the execution deadline. If the deadline expires while acquiring the gate or
 *       waiting for admission, {@link HubErrorCodes#QUEUE_WAIT_TIMEOUT} is thrown and OpenCLI is never run.</li>
 *   <li>On thread interrupt, interrupt status is restored and {@link HubErrorCodes#OPENCLI_EXECUTION_FAILED}
 *       is thrown.</li>
 *   <li>When all active and waiting executions for a site finish or fail, the site state is cleared so the
 *       next isolated execution starts immediately.</li>
 *   <li>Non-PARALLEL_SAFE executions (e.g. EXCLUSIVE) bypass staggering completely.</li>
 * </ul>
 *
 * @author fengwk
 */
@Component
public class HubExecutionStartStagger {

    @FunctionalInterface
    interface DelaySource {
        long nextDelayNanos(long minNanos, long maxNanos);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long minNanos;
    private final long maxNanos;
    private final boolean disabled;
    private final LongSupplier timeSource;
    private final DelaySource delaySource;
    private final Sleeper sleeper;

    private final Map<String, SiteCoordination> sites = new HashMap<>();

    @Autowired
    public HubExecutionStartStagger(OpenCliHubProperties properties) {
        this(
            properties.getExecution().getParallelStartStaggerMinMillis(),
            properties.getExecution().getParallelStartStaggerMaxMillis(),
            System::nanoTime,
            defaultDelaySource(
                properties.getExecution().getParallelStartStaggerMinMillis(),
                properties.getExecution().getParallelStartStaggerMaxMillis()),
            TimeUnit.NANOSECONDS::sleep);
    }

    HubExecutionStartStagger(
        long minMillis,
        long maxMillis,
        LongSupplier timeSource,
        DelaySource delaySource,
        Sleeper sleeper) {
        validateProperties(minMillis, maxMillis);
        this.disabled = (minMillis == 0L && maxMillis == 0L);
        this.minNanos = TimeUnit.MILLISECONDS.toNanos(minMillis);
        this.maxNanos = TimeUnit.MILLISECONDS.toNanos(maxMillis);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource must not be null");
        this.delaySource = Objects.requireNonNull(delaySource, "delaySource must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    public static void validateProperties(long minMillis, long maxMillis) {
        if (minMillis < 0L) {
            throw new IllegalArgumentException(
                "parallelStartStaggerMinMillis must not be negative: " + minMillis);
        }
        if (maxMillis < 0L) {
            throw new IllegalArgumentException(
                "parallelStartStaggerMaxMillis must not be negative: " + maxMillis);
        }
        if (maxMillis < minMillis) {
            throw new IllegalArgumentException(
                "parallelStartStaggerMaxMillis (" + maxMillis + ") must not be less than minMillis (" + minMillis + ")");
        }
        long maxAllowedMillis = Long.MAX_VALUE / 1_000_000L;
        if (maxMillis > maxAllowedMillis) {
            throw new IllegalArgumentException(
                "parallelStartStaggerMaxMillis (" + maxMillis + ") causes nanosecond overflow");
        }
    }

    private static DelaySource defaultDelaySource(long minMillis, long maxMillis) {
        return (minNanos, maxNanos) -> {
            if (minMillis == maxMillis) {
                return minNanos;
            }
            long randomMs = ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1L);
            return TimeUnit.MILLISECONDS.toNanos(randomMs);
        };
    }

    boolean isDisabled() {
        return disabled;
    }

    /**
     * Executes the supplied callable under start stagger coordination.
     */
    public <T> T execute(
        String site,
        HubExecutionConcurrencyMode mode,
        long deadlineNanos,
        Callable<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        if (disabled || mode != HubExecutionConcurrencyMode.PARALLEL_SAFE) {
            return invokeAction(action);
        }

        String siteKey = normalizeSite(site);
        final SiteCoordination coord;
        synchronized (this) {
            coord = sites.computeIfAbsent(siteKey, k -> new SiteCoordination());
            coord.refCount++;
        }

        boolean gateAcquired = false;
        try {
            long remainingNanos = deadlineNanos - timeSource.getAsLong();
            if (remainingNanos <= 0L) {
                throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                    "Execution deadline elapsed while waiting for start stagger gate");
            }

            try {
                gateAcquired = coord.startGate.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                    ex, "Interrupted while waiting for start stagger gate");
            }

            if (!gateAcquired) {
                throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                    "Execution deadline elapsed while waiting for start stagger gate");
            }

            // Holding per-site fair startGate: wait until previous actual start's nextAllowedStartNanos
            try {
                while (true) {
                    long now = timeSource.getAsLong();
                    if (now >= deadlineNanos) {
                        throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                            "Execution deadline elapsed while waiting for start stagger");
                    }
                    long waitNanos = coord.nextAllowedStartNanos - now;
                    if (waitNanos <= 0L) {
                        break;
                    }
                    if (coord.nextAllowedStartNanos >= deadlineNanos) {
                        throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                            "Execution deadline elapsed while waiting for start stagger");
                    }
                    try {
                        sleeper.sleepNanos(waitNanos);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                            ex, "Interrupted while waiting for start stagger");
                    }
                }

                // Admitted: capture actual start timestamp, compute next allowed start, and release gate
                long actualStart = timeSource.getAsLong();
                long interval = delaySource.nextDelayNanos(minNanos, maxNanos);
                coord.nextAllowedStartNanos = actualStart + interval;
            } finally {
                coord.startGate.unlock();
            }

            // Run task action outside the start gate
            return invokeAction(action);
        } finally {
            synchronized (this) {
                coord.refCount--;
                if (coord.refCount == 0) {
                    sites.remove(siteKey);
                }
            }
        }
    }

    /**
     * Executes the supplied runnable under start stagger coordination.
     */
    public void execute(
        String site,
        HubExecutionConcurrencyMode mode,
        long deadlineNanos,
        Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        execute(site, mode, deadlineNanos, () -> {
            action.run();
            return null;
        });
    }

    private <T> T invokeAction(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Interrupted while executing task");
        } catch (Exception ex) {
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Execution failed");
        }
    }

    private static String normalizeSite(String site) {
        if (site == null || site.isBlank()) {
            return "";
        }
        return site.trim().toLowerCase(Locale.ROOT);
    }

    synchronized int inFlightCount(String site) {
        SiteCoordination coord = sites.get(normalizeSite(site));
        return coord == null ? 0 : coord.refCount;
    }

    synchronized int activeSiteCount() {
        return sites.size();
    }

    private static class SiteCoordination {
        int refCount = 0;
        final ReentrantLock startGate = new ReentrantLock(true);
        long nextAllowedStartNanos = 0L;
    }

}
