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
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-threaded bounded execution queue for one instance. Mirrors the design contract in
 * {@code docs/technical-design.md §20.4} — one active worker, finite pending queue, idle-only
 * shutdown, and a deadline-aware submit path used by the execution service to enforce
 * end-to-end timeouts.
 *
 * <p>Submit is performed via the standard {@link ThreadPoolExecutor#execute(Runnable)}
 * path so the worker's lifecycle (creation, drain, shutdown) follows the platform contract;
 * queue-full is translated from {@link RejectedExecutionException} to the domain
 * {@code INSTANCE_QUEUE_FULL}. The deadline budget is enforced twice: once at submit time
 * (caller-side, fast) and once inside the worker (right before the user task body runs),
 * so a task that has been queued past its budget can never reach the resource-acquisition
 * or process-launch stages.
 *
 * @author fengwk
 */
public class HubInstanceDispatcher {

    private int maxPending;
    private final ThreadPoolExecutor executor;
    /**
     * Serialises submit and maxPending changes against {@link #shutdownIfIdle()} so a
     * submittable window and a shutdown decision cannot race. The physical queue may retain
     * already-accepted tasks above a reduced limit; every new acceptance is bounded here.
     * {@link ThreadPoolExecutor#execute(Runnable)} already
     * races with its own {@code shutdown}, which is fine because the executor coerces
     * a post-shutdown submit into {@link RejectedExecutionException} — but our explicit
     * flag allows {@link #shutdownIfIdle()} to refuse to commit to a teardown while a
     * submit is in flight.
     */
    private final ReentrantLock submitLock = new ReentrantLock();
    private volatile boolean shutdown;

    public HubInstanceDispatcher(String instanceCode, int maxPending) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
        executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new DispatcherThreadFactory(instanceCode),
            new ThreadPoolExecutor.AbortPolicy());
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
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        submitLock.lock();
        try {
            this.maxPending = maxPending;
        } finally {
            submitLock.unlock();
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
        Future<T> future = submit(task, deadlineNanos, stillWanted);
        return await(future, stillWanted);
    }

    /**
     * Submit {@code task} to the underlying executor without blocking the caller. Returns
     * the {@link Future} so callers can either observe completion or, when the deadline
     * eventually elapses inside the worker, receive the propagated
     * {@code QUEUE_WAIT_TIMEOUT} throwable.
     *
     * <p>Failure modes (in evaluation order):
     * <ol>
     *   <li>{@code task == null} → {@link IllegalArgumentException},</li>
     *   <li>{@link #isShuttingDown()} → {@code INSTANCE_QUEUE_FULL},</li>
     *   <li>deadline already elapsed → {@code QUEUE_WAIT_TIMEOUT} without enqueueing,</li>
     *   <li>logical pending limit reached or executor rejected during shutdown →
     *       {@code INSTANCE_QUEUE_FULL}.</li>
     * </ol>
     */
    public <T> Future<T> submit(Callable<T> task, long deadlineNanos) {
        return submit(task, deadlineNanos, null);
    }

    public <T> Future<T> submit(Callable<T> task, long deadlineNanos, BooleanSupplier stillWanted) {
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
            if (stillWanted != null && !stillWanted.getAsBoolean()) {
                throw HubErrorCodes.CLIENT_DISCONNECTED.asThrowable(
                    "Client disconnected before enqueue");
            }
            if (executor.getQueue().size() >= maxPending) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance pending queue reached maxPending=" + maxPending);
            }
            FutureTask<T> future = new FutureTask<>(
                new DeadlineAwareCallable<>(task, deadlineNanos, stillWanted));
            try {
                executor.execute(future);
            } catch (RejectedExecutionException ex) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance pending queue refused the submission: "
                        + (ex.getMessage() == null ? "queue full" : ex.getMessage()));
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
            if (executor.getActiveCount() != 0 || !executor.getQueue().isEmpty()) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "Instance has active or pending work");
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
     * Drain pending (queued, not yet running) tasks and cancel them so blocked
     * {@link #dispatch} callers receive {@link HubErrorCodes#INSTANCE_QUEUE_CLEARED}.
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
                }
            }
            return cleared;
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
            if (executor.getActiveCount() != 0 || !executor.getQueue().isEmpty()) {
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
     * occupy a pending slot until the active worker happens to poll it.
     */
    private void cancelQueued(Future<?> future) {
        future.cancel(false);
        executor.getQueue().remove(future);
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
