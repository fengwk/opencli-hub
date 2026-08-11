package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
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
 *   <li><b>Recovery barrier</b> — {@link #runRecovery(Callable)} holds the global start lock
 *       for the whole sweep. API {@link #runStart(Callable)} callers therefore wait, bounded
 *       by {@code runtime.recovery-barrier-timeout-millis}; on timeout they receive
 *       {@link HubErrorCodes#INSTANCE_START_RECOVERY_IN_PROGRESS} when recovery still holds
 *       the lock, otherwise {@link HubErrorCodes#INSTANCE_BUSY} (another API start in
 *       progress). An interrupted waiter aborts with
 *       {@link HubErrorCodes#INSTANCE_START_FAILED} while preserving the interrupt flag.</li>
 * </ul>
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
    private final long recoveryBarrierTimeoutMillis;
    private volatile boolean recoveryInProgress;

    public HubInstanceStartCoordinator(OpenCliHubProperties properties) {
        this.recoveryBarrierTimeoutMillis = Math.max(0L,
            properties.getRuntime().getRecoveryBarrierTimeoutMillis());
    }

    /**
     * API entry point used by {@code create}, {@code start} and {@code restart}. Waits for a
     * running startup recovery sweep and for any other in-flight start, both bounded by the
     * configured barrier timeout, then runs the action under the global start lock.
     *
     * <p>When the wait times out the caller receives
     * {@link HubErrorCodes#INSTANCE_START_RECOVERY_IN_PROGRESS} (startup recovery holds the
     * lock) or {@link HubErrorCodes#INSTANCE_BUSY} (another API start holds it); when the
     * wait is interrupted the caller receives {@link HubErrorCodes#INSTANCE_START_FAILED}
     * and the interrupt flag is restored.
     */
    public <T> T runStart(Callable<T> action) {
        try {
            if (!startLock.tryLock(recoveryBarrierTimeoutMillis, TimeUnit.MILLISECONDS)) {
                if (recoveryInProgress) {
                    throw HubErrorCodes.INSTANCE_START_RECOVERY_IN_PROGRESS.asThrowable(
                        "instance start rejected: startup recovery is still in progress; "
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
            return call(action);
        } finally {
            startLock.unlock();
        }
    }

    /**
     * Single-start entry used by the recovery sweep itself: re-enters the global start lock
     * (already held by {@link #runRecovery(Callable)}) and never waits on the barrier. The
     * recovery sweep is single-threaded, so this is only ever called from the recovery thread.
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
     * Marks the whole startup recovery sweep (orphan scan + state normalisation + instance
     * recovery) as a barrier phase: the global start lock is held for the sweep, so API
     * starts wait (bounded) until it finishes. Nested recovery is rejected.
     */
    public <T> T runRecovery(Callable<T> action) {
        startLock.lock();
        try {
            if (recoveryInProgress) {
                throw new IllegalStateException("startup recovery is already in progress");
            }
            recoveryInProgress = true;
            log.info("startup recovery barrier acquired; API start/create/restart will wait");
            try {
                return call(action);
            } finally {
                recoveryInProgress = false;
                log.info("startup recovery barrier released");
            }
        } finally {
            startLock.unlock();
        }
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
