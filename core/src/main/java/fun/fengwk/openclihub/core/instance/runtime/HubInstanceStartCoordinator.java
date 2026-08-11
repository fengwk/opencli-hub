package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Single coordination entry for every Hub Instance start: API {@code create}/{@code start}/
 * {@code restart} and the startup recovery sweep share one lock, so two entry points can
 * never launch runtimes concurrently (which would corrupt daemon context discovery and
 * allow duplicate processes).
 *
 * <p>Two primitives are provided:
 * <ul>
 *   <li><b>Start serialisation</b> — {@link #runStart(Callable)} and
 *       {@link #runRecoveryStart(Callable)} execute their action under one global
 *       {@link ReentrantLock}. All starts are globally serialised; daemon restart, context
 *       snapshot and context discovery therefore never overlap.</li>
 *   <li><b>Recovery barrier</b> — {@link #beginRecovery()} declares the barrier
 *       <em>synchronously</em> (the ApplicationRunner calls it before returning, so no API
 *       start can slip in while the recovery task is still queued); {@link #completeRecovery()}
 *       releases it and is idempotent so the sweep, executor rejection and shutdown all
 *       release cleanly. While the barrier is active, {@link #runStart(Callable)} callers
 *       wait, bounded by {@code runtime.start-coordination-timeout-millis}; on timeout they
 *       receive {@link HubErrorCodes#INSTANCE_START_RECOVERY_IN_PROGRESS} when recovery is
 *       active, otherwise {@link HubErrorCodes#INSTANCE_BUSY} (another API start in
 *       progress). An interrupted waiter aborts with
 *       {@link HubErrorCodes#INSTANCE_START_FAILED} while preserving the interrupt flag.</li>
 * </ul>
 *
 * <p>The same bounded timeout also covers the queue wait for the start lock itself, so
 * {@code runStart} never blocks indefinitely behind another start.
 *
 * <p>Lock ordering is always coordinator-lock first, then the per-instance lifecycle lock,
 * so no deadlock with stop/delete/update (which take only the per-instance lock) is possible.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceStartCoordinator {

    private final ReentrantLock startLock = new ReentrantLock(true);
    private final Condition recoveryFinished = startLock.newCondition();
    private final long startCoordinationTimeoutMillis;
    private volatile boolean recoveryInProgress;

    public HubInstanceStartCoordinator(OpenCliHubProperties properties) {
        this.startCoordinationTimeoutMillis = Math.max(0L,
            properties.getRuntime().getStartCoordinationTimeoutMillis());
    }

    /**
     * API entry point used by {@code create}, {@code start} and {@code restart}. Waits for a
     * running startup recovery sweep and for any other in-flight start, both bounded by the
     * configured coordination timeout, then runs the action under the global start lock.
     *
     * <p>When the wait times out the caller receives
     * {@link HubErrorCodes#INSTANCE_START_RECOVERY_IN_PROGRESS} (startup recovery is active)
     * or {@link HubErrorCodes#INSTANCE_BUSY} (another API start holds the lock); when the
     * wait is interrupted the caller receives {@link HubErrorCodes#INSTANCE_START_FAILED}
     * and the interrupt flag is restored.
     */
    public <T> T runStart(Callable<T> action) {
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(startCoordinationTimeoutMillis);
        try {
            if (!startLock.tryLock(startCoordinationTimeoutMillis, TimeUnit.MILLISECONDS)) {
                if (recoveryInProgress) {
                    throw HubErrorCodes.INSTANCE_START_RECOVERY_IN_PROGRESS.asThrowable(
                        "instance start rejected: startup recovery is in progress; "
                            + "retry later");
                }
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance start rejected: another instance start is in progress; "
                        + "retry later");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "instance start interrupted while waiting for the start lock");
        }
        try {
            // The barrier may have been declared before the recovery thread started
            // (beginRecovery is synchronous), so re-check it under the lock and wait
            // bounded; awaitNanos releases the lock so recovery can make progress.
            while (recoveryInProgress) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw HubErrorCodes.INSTANCE_START_RECOVERY_IN_PROGRESS.asThrowable(
                        "instance start rejected: startup recovery did not finish within "
                            + startCoordinationTimeoutMillis + " ms; retry later");
                }
                try {
                    recoveryFinished.awaitNanos(remainingNanos);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                        ex, "instance start interrupted while waiting for startup recovery "
                            + "to finish");
                }
            }
            return call(action);
        } finally {
            startLock.unlock();
        }
    }

    /**
     * Single-start entry used by the recovery sweep itself: re-enters the global start lock
     * and never waits on the barrier. The recovery sweep is single-threaded, so this is only
     * ever called from the recovery thread.
     */
    public <T> T runRecoveryStart(Callable<T> action) {
        startLock.lock();
        try {
            return call(action);
        } finally {
            startLock.unlock();
        }
    }

    /**
     * Synchronously declares the startup recovery barrier. Must be called by the
     * ApplicationRunner <em>before</em> it returns (and before the recovery task is even
     * scheduled), so API starts are held back from the very moment recovery is announced.
     * Nested declaration is rejected.
     */
    public void beginRecovery() {
        startLock.lock();
        try {
            if (recoveryInProgress) {
                throw new IllegalStateException("startup recovery is already in progress");
            }
            recoveryInProgress = true;
            log.info("startup recovery barrier acquired; API start/create/restart will wait");
        } finally {
            startLock.unlock();
        }
    }

    /**
     * Releases the startup recovery barrier and wakes every waiting API start. Idempotent:
     * safe to call from the sweep's {@code finally}, from executor-submit failure handling
     * and from the ApplicationRunner's shutdown path — the barrier can never stay active
     * forever.
     */
    public void completeRecovery() {
        startLock.lock();
        try {
            recoveryInProgress = false;
            recoveryFinished.signalAll();
            log.info("startup recovery barrier released");
        } finally {
            startLock.unlock();
        }
    }

    /** True while the startup recovery barrier is active. Useful for diagnostics and tests. */
    public boolean isRecoveryInProgress() {
        return recoveryInProgress;
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("start action failed", ex);
        }
    }
}
