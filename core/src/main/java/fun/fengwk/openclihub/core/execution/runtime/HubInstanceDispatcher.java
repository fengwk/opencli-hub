package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;

/**
 * Bounded execution queue and concurrency gate for one instance.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>{@code maxConcurrency} defines the upper bound of concurrent worker threads (1..4).</li>
 *   <li>{@code maxPending} defines the extra waiting queue capacity (0..50).</li>
 *   <li>Total accepted capacity is strictly {@code maxConcurrency + maxPending}.</li>
 *   <li>Dynamic concurrency update: expanding increases maximum before core; shrinking decreases
 *       core before maximum. Existing active tasks drain naturally.</li>
 *   <li>Per-instance fairness gate via {@link ReentrantReadWriteLock}: {@code PARALLEL_SAFE} takes
 *       read lock; {@code EXCLUSIVE} takes write lock. Waiting writers prevent subsequent readers
 *       from barging.</li>
 * </ul>
 *
 * @author fengwk
 */
public class HubInstanceDispatcher {

    private int maxConcurrency;
    private int maxPending;
    private final ThreadPoolExecutor executor;
    /**
     * Fair read-write gate enforcing concurrency isolation within the instance.
     */
    private final ReentrantReadWriteLock executionGate = new ReentrantReadWriteLock(true);
    /**
     * Serialises submit, maxConcurrency and maxPending changes against {@link #shutdownIfIdle()}
     * so a submittable window and a shutdown decision cannot race.
     */
    private final ReentrantLock submitLock = new ReentrantLock();
    /** Number of accepted FutureTasks that have not reached FutureTask.done() yet. */
    private int acceptedNotTerminalCount;
    private volatile boolean shutdown;

    public HubInstanceDispatcher(String instanceCode, int maxPending) {
        this(instanceCode, 1, maxPending);
    }

    public HubInstanceDispatcher(String instanceCode, int maxConcurrency, int maxPending) {
        this(instanceCode, maxConcurrency, maxPending, new ThreadPoolExecutor(
            maxConcurrency,
            maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new DispatcherThreadFactory(instanceCode),
            new ThreadPoolExecutor.AbortPolicy()));
    }

    /** Package-private test wiring for deterministic executor acceptance control. */
    HubInstanceDispatcher(String instanceCode, int maxPending, ThreadPoolExecutor executor) {
        this(instanceCode, 1, maxPending, executor);
    }

    /** Package-private test wiring for deterministic executor acceptance control. */
    HubInstanceDispatcher(String instanceCode, int maxConcurrency, int maxPending, ThreadPoolExecutor executor) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        if (maxPending < 0) {
            throw new IllegalArgumentException("maxPending must not be negative");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.maxConcurrency = maxConcurrency;
        this.maxPending = maxPending;
        this.executor = executor;
    }

    public int getMaxConcurrency() {
        submitLock.lock();
        try {
            return maxConcurrency;
        } finally {
            submitLock.unlock();
        }
    }

    /** Changes the worker concurrency limit without dropping accepted work. */
    public void updateMaxConcurrency(int maxConcurrency) {
        submitLock.lock();
        try {
            updateLimitsLocked(maxConcurrency, maxPending);
        } finally {
            submitLock.unlock();
        }
    }

    public int getMaxPending() {
        submitLock.lock();
        try {
            return maxPending;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Changes the logical pending limit without replacing the worker or dropping queued work.
     * When the limit is reduced below the current queue size, existing tasks drain normally and
     * new submissions are rejected until the pending count falls below the new limit.
     */
    public void updateMaxPending(int maxPending) {
        submitLock.lock();
        try {
            updateLimitsLocked(maxConcurrency, maxPending);
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Applies both admission limits atomically. Expanding sets executor maximum before core;
     * shrinking sets core before maximum so running tasks drain naturally.
     */
    public void updateLimits(int maxConcurrency, int maxPending) {
        submitLock.lock();
        try {
            updateLimitsLocked(maxConcurrency, maxPending);
        } finally {
            submitLock.unlock();
        }
    }

    public int totalCapacity() {
        submitLock.lock();
        try {
            return maxConcurrency + maxPending;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Executes a task under the fair instance concurrency gate until the supplied deadline.
     * Parallel-safe work takes the read lock; every other mode fails safe to the write lock.
     */
    public <T> T executeGuarded(
        HubExecutionConcurrencyMode mode, long deadlineNanos, Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        Lock lock = mode == HubExecutionConcurrencyMode.PARALLEL_SAFE
            ? executionGate.readLock() : executionGate.writeLock();
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                "Execution deadline elapsed while waiting for concurrency gate");
        }
        boolean acquired;
        try {
            acquired = lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Interrupted while waiting for concurrency gate");
        }
        if (!acquired) {
            throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                "Execution deadline elapsed while waiting for concurrency gate");
        }
        try {
            return task.call();
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Interrupted while executing under concurrency gate");
        } catch (Exception ex) {
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Execution failed under concurrency gate");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Synchronous dispatch with an unbounded deadline. Throws the same domain exceptions
     * as {@link #dispatch(Callable, long)}; carried over only for backward compatibility
     * with legacy callers.
     */
    public <T> T dispatch(Callable<T> task) {
        return dispatch(task, Long.MAX_VALUE, null);
    }

    /**
     * Synchronous, deadline-aware dispatch. Behaves as {@link #submit(Callable, long, BooleanSupplier)}
     * then awaits the returned {@link Future}. The deadline is enforced at submit time
     * and again in the worker (via {@link DeadlineAwareCallable}); once the budget has
     * elapsed the queued task will throw {@link HubErrorCodes#QUEUE_WAIT_TIMEOUT} from
     * {@link Future#get()} without ever calling the body.
     */
    public <T> T dispatch(Callable<T> task, long deadlineNanos) {
        return dispatch(task, deadlineNanos, null);
    }

    /**
     * Like {@link #dispatch(Callable, long)} but polls {@code stillWanted} while waiting
     * in the queue. When it returns false (client gone), the pending future is cancelled
     * and {@link HubErrorCodes#CLIENT_DISCONNECTED} is thrown so opencli never starts.
     */
    public <T> T dispatch(Callable<T> task, long deadlineNanos, BooleanSupplier stillWanted) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        // Preserve the worker-side liveness check of the legacy dispatch path: the body
        // must never start once the client is gone, even if await() has not polled yet.
        Callable<T> guarded = () -> {
            if (stillWanted != null && !stillWanted.getAsBoolean()) {
                throw HubErrorCodes.CLIENT_DISCONNECTED.asThrowable(
                    "Client disconnected before execution started");
            }
            return task.call();
        };
        Future<T> future = submit(null, guarded, deadlineNanos, null);
        return await(future, stillWanted);
    }

    /**
     * Submit {@code task} to the underlying executor without blocking the caller. Returns
     * the {@link Future} so callers can either observe completion or, when the deadline
     * eventually elapses inside the worker, receive the propagated
     * {@code QUEUE_WAIT_TIMEOUT} throwable.
     */
    public <T> Future<T> submit(Callable<T> task, long deadlineNanos) {
        return submit(null, task, deadlineNanos, null);
    }

    /**
     * Submit {@code task} to the underlying executor without blocking the caller, associating
     * it with the owning {@code executionId} (may be {@code null} for non-execution tasks).
     *
     * <p>When a not-yet-running task is discarded — queue clear, force shutdown, queued
     * cancel, or client-disconnect cancel — {@code onQueuedDiscard} (may be {@code null})
     * is invoked exactly once. The execution service uses this callback to persist the
     * DB row as CANCELLED so a discarded queue handle can never leave the execution
     * PENDING. The callback must be idempotent: it may race with the worker's own
     * PENDING→RUNNING CAS and with other discard paths.
     */
    public <T> Future<T> submit(String executionId,
                                Callable<T> task,
                                long deadlineNanos,
                                Runnable onQueuedDiscard) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        submitLock.lock();
        try {
            if (shutdown) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance dispatcher is shutting down");
            }
            if (deadlineNanos - System.nanoTime() <= 0L) {
                throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                    "Queue deadline already exceeded before enqueue");
            }
            if (acceptedNotTerminalCount >= maxConcurrency + maxPending) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance capacity reached maxConcurrency=" + maxConcurrency
                        + " maxPending=" + maxPending);
            }
            TrackedFutureTask<T> future = new TrackedFutureTask<>(
                executionId, onQueuedDiscard,
                new DeadlineAwareCallable<>(task, deadlineNanos, null));
            acceptedNotTerminalCount++;
            try {
                executor.execute(future);
                future.markAccepted();
            } catch (RejectedExecutionException ex) {
                rollbackAcceptedSubmission();
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance pending queue refused the submission: "
                        + (ex.getMessage() == null ? "queue full" : ex.getMessage()));
            } catch (RuntimeException | Error ex) {
                rollbackAcceptedSubmission();
                throw ex;
            }
            return future;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Runs a short lifecycle-side operation only when this instance is idle. The same
     * {@link #submitLock} guards the idle check and the operation, so a submission cannot enter
     * between the check and the daemon call.
     */
    public <T> T executeWhenIdle(Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        submitLock.lock();
        try {
            if (shutdown) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "Instance dispatcher is shutting down");
            }
            if (acceptedNotTerminalCount != 0
                || executor.getActiveCount() != 0
                || !executor.getQueue().isEmpty()) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "Instance has accepted, active, or pending work");
            }
            try {
                return task.call();
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Exception ex) {
                throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                    ex, "Idle instance operation failed");
            }
        } finally {
            submitLock.unlock();
        }
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int pendingCount() {
        return executor.getQueue().size();
    }

    /**
     * Returns the exact number of accepted tasks that have not reached
     * {@link FutureTask#done()} yet. Unlike the executor's active/queue metrics, this also
     * covers the handoff window after acceptance and before a worker exposes the task as active.
     */
    public int acceptedNotTerminalCount() {
        submitLock.lock();
        try {
            return acceptedNotTerminalCount;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Drain pending (queued, not yet running) tasks and cancel them so blocked
     * {@link #dispatch} callers receive {@link HubErrorCodes#INSTANCE_QUEUE_CLEARED}.
     * Every discarded task notifies its {@code onQueuedDiscard} owner so the execution
     * row is persisted CANCELLED instead of being left PENDING.
     * The active worker task is left running.
     *
     * @return number of pending tasks that were cancelled
     */
    public int clearPending() {
        submitLock.lock();
        try {
            if (shutdown) {
                return 0;
            }
            List<Runnable> drained = new ArrayList<>();
            executor.getQueue().drainTo(drained);
            int cleared = 0;
            for (Runnable task : drained) {
                if (task instanceof Future<?> future && future.cancel(false)) {
                    cleared++;
                    if (task instanceof TrackedFutureTask<?> tracked) {
                        tracked.notifyQueuedDiscard();
                    }
                }
            }
            return cleared;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Cancel a single still-queued task by its execution id, releasing the queue slot
     * and notifying the discard owner so the DB row is persisted CANCELLED. Used by
     * {@code HubExecutionService.cancel} after the PENDING→CANCELLED CAS succeeds.
     *
     * @return {@code true} when the matching task was found queued and cancelled;
     *         {@code false} when it is not queued (already running or completed) or unknown
     */
    public boolean cancelPending(String executionId) {
        if (executionId == null) {
            return false;
        }
        submitLock.lock();
        try {
            for (Runnable command : executor.getQueue()) {
                if (command instanceof TrackedFutureTask<?> task
                    && executionId.equals(task.getExecutionId())) {
                    if (task.cancel(false)) {
                        executor.getQueue().remove(command);
                        task.notifyQueuedDiscard();
                        return true;
                    }
                    return false;
                }
            }
            return false;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Idle-only shutdown guarded by the same lock as {@link #submit(Callable, long)} so
     * a submit cannot slip between the busy check and the commit. Returns {@code true}
     * when the dispatcher was idle (active=0 && pending=0) and was successfully torn
     * down; {@code false} when there is still work and the caller must wait.
     */
    public boolean shutdownIfIdle() {
        submitLock.lock();
        try {
            if (shutdown) {
                return true;
            }
            if (acceptedNotTerminalCount != 0
                || executor.getActiveCount() != 0
                || !executor.getQueue().isEmpty()) {
                return false;
            }
            executor.shutdown();
            shutdown = true;
            return true;
        } finally {
            submitLock.unlock();
        }
    }

    /** Force shutdown used for unexpected runtime exit and application teardown. */
    public void shutdownNow() {
        submitLock.lock();
        try {
            List<Runnable> dropped = executor.shutdownNow();
            for (Runnable task : dropped) {
                if (task instanceof Future<?> future) {
                    future.cancel(false);
                    if (task instanceof TrackedFutureTask<?> tracked) {
                        tracked.notifyQueuedDiscard();
                    }
                }
            }
            shutdown = true;
        } finally {
            submitLock.unlock();
        }
    }

    public boolean isShuttingDown() {
        return shutdown;
    }

    /** Caller must hold {@link #submitLock}. */
    private void updateLimitsLocked(int maxConcurrency, int maxPending) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        if (maxPending < 0) {
            throw new IllegalArgumentException("maxPending must not be negative");
        }
        if (maxConcurrency > this.maxConcurrency) {
            executor.setMaximumPoolSize(maxConcurrency);
            executor.setCorePoolSize(maxConcurrency);
        } else if (maxConcurrency < this.maxConcurrency) {
            executor.setCorePoolSize(maxConcurrency);
            executor.setMaximumPoolSize(maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
        this.maxPending = maxPending;
    }

    /** Caller must hold {@link #submitLock}. */
    private void rollbackAcceptedSubmission() {
        if (acceptedNotTerminalCount > 0) {
            acceptedNotTerminalCount--;
        }
    }

    private final class TrackedFutureTask<T> extends FutureTask<T> {

        private final String executionId;
        private final Runnable onQueuedDiscard;
        private final AtomicBoolean discardNotified = new AtomicBoolean();
        private boolean accepted;
        private boolean terminal;
        private boolean terminalCounted;

        private TrackedFutureTask(String executionId, Runnable onQueuedDiscard,
                                  Callable<T> callable) {
            super(callable);
            this.executionId = executionId;
            this.onQueuedDiscard = onQueuedDiscard;
        }

        private String getExecutionId() {
            return executionId;
        }

        /**
         * Notify the owner that this task was discarded before it started running.
         * At most one notification is delivered even when multiple discard paths
         * (clear, shutdown, cancel, client disconnect) overlap.
         */
        private void notifyQueuedDiscard() {
            if (executionId != null && onQueuedDiscard != null
                && discardNotified.compareAndSet(false, true)) {
                onQueuedDiscard.run();
            }
        }

        /** Caller holds submitLock; done() may have run before executor.execute returned. */
        private void markAccepted() {
            accepted = true;
            decrementWhenTerminal();
        }

        @Override
        protected void done() {
            submitLock.lock();
            try {
                terminal = true;
                decrementWhenTerminal();
            } finally {
                submitLock.unlock();
            }
        }

        private void decrementWhenTerminal() {
            if (accepted && terminal && !terminalCounted) {
                rollbackAcceptedSubmission();
                terminalCounted = true;
            }
        }

    }

    private <T> T await(Future<T> future, BooleanSupplier stillWanted) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    // 50ms poll: cancel pending promptly after liveness is lost.
                    return future.get(50L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    if (stillWanted != null && !stillWanted.getAsBoolean()) {
                        cancelQueued(future);
                        throw HubErrorCodes.CLIENT_DISCONNECTED.asThrowable(
                            "Client disconnected while waiting in the instance queue");
                    }
                } catch (InterruptedException ex) {
                    // An accepted execution must outlive the synchronous caller. Keep
                    // waiting and restore the flag after the terminal state is persisted.
                    interrupted = true;
                } catch (CancellationException ex) {
                    // Already cancelled (clear-queue or a prior cancelQueued).
                    executor.getQueue().remove(future);
                    if (stillWanted != null && !stillWanted.getAsBoolean()) {
                        throw HubErrorCodes.CLIENT_DISCONNECTED.asThrowable(
                            "Client disconnected while waiting in the instance queue");
                    }
                    throw HubErrorCodes.INSTANCE_QUEUE_CLEARED.asThrowable(
                        "Pending execution was rejected because the instance queue was cleared");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                        cause, "Instance dispatcher task failed");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Cancel a not-yet-running task and drop it from the executor queue so it does not
     * occupy a pending slot until the active worker happens to poll it. The discard owner
     * is notified so a client-disconnect cancel never leaves the execution PENDING.
     */
    private void cancelQueued(Future<?> future) {
        future.cancel(false);
        executor.getQueue().remove(future);
        if (future instanceof TrackedFutureTask<?> tracked) {
            tracked.notifyQueuedDiscard();
        }
    }

    /**
     * Deadline-reevaluating wrapper executed inside the worker thread. When the budget
     * has already elapsed when the worker finally dequeues the task, the body never
     * runs and the wrapper throws the domain {@code QUEUE_WAIT_TIMEOUT} error.
     */
    private static final class DeadlineAwareCallable<T> implements Callable<T> {

        private final Callable<T> delegate;
        private final long deadlineNanos;
        private final BooleanSupplier stillWanted;

        DeadlineAwareCallable(Callable<T> delegate, long deadlineNanos, BooleanSupplier stillWanted) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
            this.stillWanted = stillWanted;
        }

        @Override
        public T call() throws Exception {
            if (stillWanted != null && !stillWanted.getAsBoolean()) {
                throw HubErrorCodes.CLIENT_DISCONNECTED.asThrowable(
                    "Client disconnected before execution started");
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                    "Queue deadline expired before task body started");
            }
            return delegate.call();
        }

    }

    private static class DispatcherThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();
        private final String instanceCode;

        private DispatcherThreadFactory(String instanceCode) {
            this.instanceCode = instanceCode == null ? "unknown" : instanceCode;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task);
            thread.setName("opencli-hub-" + instanceCode + '-' + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }

    }

}
