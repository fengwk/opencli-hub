package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-threaded bounded execution queue for one instance. Mirrors the design contract in
 * {@code docs/technical-design.md §20.4} — one active worker, finite pending queue, idle-only
 * shutdown, and a deadline-aware submit path used by the M5 execution service to enforce
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
     * with the M4 caller path.
     */
    public <T> T dispatch(Callable<T> task) {
        return dispatch(task, Long.MAX_VALUE);
    }

    /**
     * Synchronous, deadline-aware dispatch. Behaves as {@link #submit(Callable, long)}
     * then awaits the returned {@link Future}. The deadline is enforced at submit time
     * and again in the worker (via {@link DeadlineAwareCallable}); once the budget has
     * elapsed the queued task will throw {@link HubErrorCodes#QUEUE_WAIT_TIMEOUT} from
     * {@link Future#get()} without ever calling the body.
     */
    public <T> T dispatch(Callable<T> task, long deadlineNanos) {
        Future<T> future = submit(task, deadlineNanos);
        return await(future);
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
            if (executor.getQueue().size() >= maxPending) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "Instance pending queue reached maxPending=" + maxPending);
            }
            FutureTask<T> future = new FutureTask<>(new DeadlineAwareCallable<>(task, deadlineNanos));
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

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int pendingCount() {
        return executor.getQueue().size();
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

    private static <T> T await(Future<T> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ex) {
                    // An accepted execution must outlive the synchronous caller. Keep
                    // waiting and restore the flag after the terminal state is persisted.
                    interrupted = true;
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
     * Deadline-reevaluating wrapper executed inside the worker thread. When the budget
     * has already elapsed when the worker finally dequeues the task, the body never
     * runs and the wrapper throws the domain {@code QUEUE_WAIT_TIMEOUT} error.
     */
    private static final class DeadlineAwareCallable<T> implements Callable<T> {

        private final Callable<T> delegate;
        private final long deadlineNanos;

        DeadlineAwareCallable(Callable<T> delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public T call() throws Exception {
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
