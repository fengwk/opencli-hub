package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link HubInstanceDispatcher} deadline-aware enqueue path and the
 * idle-only shutdown guard. Pure unit: no real OS processes are launched.
 *
 * <p>Covers the dispatcher acceptance criteria in design §20.4 / §22.2:
 * <ul>
 *   <li>{@link #shouldRunTasksSeriallyThroughTheSingleWorker()},</li>
 *   <li>{@link #shouldRejectWhenPendingQueueIsFull()},</li>
 *   <li>{@link #shouldRejectZeroRemainingDeadlineWithoutTouchingQueue()},</li>
 *   <li>{@link #shouldRefuseShutdownWhileBusy()},</li>
 *   <li>{@link #shouldNotRunBodyWhenDeadlineAlreadyExpiredByEnqueueTime()},</li>
 *   <li>{@link #shouldShutdownSafelyWhileConcurrentSubmit()},</li>
 * </ul>
 */
class HubInstanceDispatcherTest {

    /**
     * Confirms the dispatcher runs tasks serially even when the queue is being flooded.
     */
    @Test
    void shouldRunTasksSeriallyThroughTheSingleWorker() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("serial", 5);
        try {
            AtomicInteger observedMax = new AtomicInteger();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();

            for (int i = 0; i < 8; i++) {
                dispatcher.dispatch(() -> {
                    int current = active.incrementAndGet();
                    observedMax.updateAndGet(prev -> Math.max(prev, current));
                    Thread.sleep(20);
                    active.decrementAndGet();
                    completed.incrementAndGet();
                    return null;
                });
            }

            assertThat(observedMax.get()).as("never more than one in flight").isOne();
            assertThat(completed.get()).isEqualTo(8);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /**
     * Verifies that {@code INSTANCE_QUEUE_FULL} is raised synchronously when the bounded
     * queue is at capacity, with no new task getting accepted.
     */
    @Test
    void shouldRejectWhenPendingQueueIsFull() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("full", 2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread activeThread = new Thread(() ->
            dispatcher.dispatch(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "active-task";
            }));
        activeThread.setDaemon(true);
        activeThread.start();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // Fill the pending queue via the non-blocking submit() so we do not deadlock
            // inside future.get().
            long farFuture = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            dispatcher.submit(() -> "pending-1", farFuture);
            dispatcher.submit(() -> "pending-2", farFuture);
            assertThat(dispatcher.pendingCount()).isEqualTo(2);

            assertThatThrownBy(() -> dispatcher.submit(() -> "rejected", farFuture))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
            activeThread.join(2000);
        }
    }

    /** Dynamic limits reject immediately without dropping work already accepted by the queue. */
    @Test
    void shouldApplyDynamicMaxPendingWithoutDroppingQueuedTasks() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("dynamic", 3);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Future<String> running = dispatcher.submit(() -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "running";
        }, deadline);
        try {
            assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();
            Future<String> first = dispatcher.submit(() -> "first", deadline);
            Future<String> second = dispatcher.submit(() -> "second", deadline);
            Future<String> third = dispatcher.submit(() -> "third", deadline);
            assertThat(dispatcher.pendingCount()).isEqualTo(3);

            dispatcher.updateMaxPending(1);
            assertThat(dispatcher.getMaxPending()).isOne();
            assertThatThrownBy(() -> dispatcher.submit(() -> "rejected", deadline))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
            assertThat(dispatcher.pendingCount()).isEqualTo(3);

            dispatcher.updateMaxPending(4);
            Future<String> fourth = dispatcher.submit(() -> "fourth", deadline);
            assertThat(dispatcher.pendingCount()).isEqualTo(4);

            release.countDown();
            assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("running");
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(third.get(2, TimeUnit.SECONDS)).isEqualTo("third");
            assertThat(fourth.get(2, TimeUnit.SECONDS)).isEqualTo("fourth");
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * Verifies that a submit whose deadline is already in the past short-circuits to
     * {@code QUEUE_WAIT_TIMEOUT} without ever touching the bounded queue.
     */
    @Test
    void shouldRejectZeroRemainingDeadlineWithoutTouchingQueue() {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("timeout", 1);
        try {
            long past = System.nanoTime() - 1_000L;
            assertThatThrownBy(() -> dispatcher.submit(() -> "past", past))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.QUEUE_WAIT_TIMEOUT.getCode()));
            assertThat(dispatcher.pendingCount()).isZero();
            assertThat(dispatcher.activeCount()).isZero();
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /**
     * shutdownIfIdle must refuse to tear down while the active worker still runs.
     */
    @Test
    void shouldRefuseShutdownWhileBusy() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("busy", 3);
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> dispatcher.dispatch(() -> {
            started.countDown();
            try {
                Thread.sleep(400);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "ran";
        }));
        worker.setDaemon(true);
        worker.start();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.shutdownIfIdle())
                .as("active worker prevents shutdown")
                .isFalse();
            worker.join(2000);
            // Future.get() may return just before ThreadPoolExecutor decrements its active
            // worker count. Wait for the dispatcher's actual idle condition, not merely the
            // caller thread's completion, before asserting that idle shutdown succeeds.
            assertThat(awaitIdle(dispatcher, 2_000L)).isTrue();
            assertThat(dispatcher.shutdownIfIdle())
                .as("no work left permits shutdown")
                .isTrue();
        } finally {
            dispatcher.shutdownNow();
            worker.join(2000);
        }
    }

    /**
     * When a task is enqueued with a deadline that is already in the past, the
     * dispatcher wrapper refuses to run the user body even though the worker thread
     * can immediately dequeue it. The wrapper enforces the deadline the moment the
     * task body would otherwise start, so the user's callable is never invoked.
     */
    @Test
    void shouldNotRunBodyWhenDeadlineAlreadyExpiredByEnqueueTime() {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("expired-by-worker", 4);
        AtomicInteger bodyEntered = new AtomicInteger();
        try {
            long past = System.nanoTime() - 1L;
            assertThatThrownBy(() -> dispatcher.dispatch(() -> {
                bodyEntered.incrementAndGet();
                return "should-not-run";
            }, past))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.QUEUE_WAIT_TIMEOUT.getCode()));
            assertThat(bodyEntered.get())
                .as("the body must never run when the deadline already elapsed at submit time")
                .isZero();
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** Ensures force shutdown completes futures removed from the pending queue. */
    @Test
    void shouldCancelPendingFuturesOnForcedShutdown() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("forced", 2);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatcher.submit(() -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "active";
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();
        Future<String> pending = dispatcher.submit(
            () -> "pending", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));

        dispatcher.shutdownNow();
        release.countDown();

        assertThat(pending.isCancelled()).isTrue();
        assertThatThrownBy(pending::get).isInstanceOf(CancellationException.class);
    }

    /** Verifies submit and idle shutdown cannot cross their shared decision boundary. */
    @Test
    void shouldShutdownSafelyWhileConcurrentSubmit() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("race", 2);
        AtomicInteger submittedOrRefused = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Kick off a long-running task so the shutdown wins the race only if it
            // happens while the worker is busy.
            Thread activeThread = new Thread(() -> {
                try {
                    dispatcher.dispatch(() -> {
                        release.await(5, TimeUnit.SECONDS);
                        return "active";
                    });
                } catch (RuntimeException ignored) {
                    // shutdownNow may interrupt during dispatch
                }
            });
            activeThread.setDaemon(true);
            activeThread.start();

            // Give the worker a moment to enter the active task.
            Thread.sleep(150);

            // Submit a follower at the same instant shutdown is requested.
            Thread shutdownThread = new Thread(() -> {
                boolean ok = dispatcher.shutdownIfIdle();
                submittedOrRefused.addAndGet(ok ? 1 : 0);
            });
            shutdownThread.setDaemon(true);
            Thread submitThread = new Thread(() -> {
                try {
                    dispatcher.submit(() -> "follower", System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5));
                    submittedOrRefused.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // expected when shutdown wins
                }
            });
            submitThread.setDaemon(true);
            shutdownThread.start();
            submitThread.start();
            shutdownThread.join(2000);
            submitThread.join(2000);
            // Either both paths completed cleanly or the submit was refused; the test
            // asserts the dispatcher returned to a consistent state.
            assertThat(dispatcher.pendingCount()).isLessThanOrEqualTo(2);
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * The idle check and callback share submitLock: a concurrent submit cannot enter while the
     * bind-like callback is in progress, and active work is rejected before the callback runs.
     */
    @Test
    void shouldGuardIdleOperationAtomicallyAgainstSubmit() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("idle-guard", 2);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Future<String>> submitted =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread operation = new Thread(() -> dispatcher.executeWhenIdle(() -> {
            callbackEntered.countDown();
            releaseCallback.await(2, TimeUnit.SECONDS);
            return "bound";
        }));
        operation.setDaemon(true);
        operation.start();
        Thread submitter = new Thread(() -> {
            submitted.set(dispatcher.submit(() -> "execution", System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5)));
            submitFinished.countDown();
        });
        submitter.setDaemon(true);
        try {
            assertThat(callbackEntered.await(1, TimeUnit.SECONDS)).isTrue();
            submitter.start();
            assertThat(submitFinished.await(150, TimeUnit.MILLISECONDS))
                .as("submit must wait for the idle operation callback")
                .isFalse();
            releaseCallback.countDown();
            operation.join(1000);
            assertThat(submitFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(submitted.get().get(1, TimeUnit.SECONDS)).isEqualTo("execution");
        } finally {
            releaseCallback.countDown();
            dispatcher.shutdownNow();
            operation.join(1000);
            submitter.join(1000);
        }

        HubInstanceDispatcher busy = new HubInstanceDispatcher("idle-busy", 1);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        try {
            busy.submit(() -> {
                activeStarted.countDown();
                releaseActive.await(2, TimeUnit.SECONDS);
                return null;
            }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(activeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> busy.executeWhenIdle(() -> "must-not-run"))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(ex -> assertThat(((ThrowableConventionErrorCode) ex).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_BUSY.getCode()));
        } finally {
            releaseActive.countDown();
            busy.shutdownNow();
        }
    }

    /**
     * acceptedNotTerminalCount closes the worker-start race: even if a future is still
     * between executor.execute() and the first Runnable tick, executeWhenIdle and
     * shutdownIfIdle must see the accepted work. The worker is held in a custom
     * thread factory so the test never relies on Thread.sleep to land the window.
     */
    @Test
    void shouldNotRunExecuteWhenIdleWhileFutureAcceptedButNotStarted() throws Exception {
        HoldingExecutor executor = new HoldingExecutor();
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("accept-window", 4, executor);
        try {
            Future<String> submitted = dispatcher.submit(
                () -> "never",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            // The controlled executor has accepted the FutureTask but deliberately has no active
            // worker or queued command. This is the same observable state as the Worker-start gap.
            assertThat(dispatcher.activeCount()).isZero();
            assertThat(dispatcher.pendingCount()).isZero();
            assertThat(dispatcher.acceptedNotTerminalCount())
                .as("routing must count the accepted handoff before executor metrics expose it")
                .isOne();
            assertThat(submitted).isNotDone();

            // The bind-like idle op must be rejected without ever invoking the body
            // or releasing the worker.
            AtomicInteger bodyRuns = new AtomicInteger();
            assertThatThrownBy(() -> dispatcher.executeWhenIdle(() -> {
                bodyRuns.incrementAndGet();
                return "must-not-run";
            }))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(ex -> assertThat(((ThrowableConventionErrorCode) ex).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_BUSY.getCode()));
            assertThat(bodyRuns).hasValue(0);
            assertThat(dispatcher.shutdownIfIdle())
                .as("accepted but not started work must block idle shutdown")
                .isFalse();

            // Release the worker; once done() runs the counter decrements and the
            // next idle op can complete.
            executor.runHeldTask();
            assertThat(submitted.get(2, TimeUnit.SECONDS)).isEqualTo("never");
            assertThat(dispatcher.acceptedNotTerminalCount()).isZero();
            assertThat(dispatcher.shutdownIfIdle())
                .as("idle shutdown resumes once the accepted task terminal-ised")
                .isTrue();
        } finally {
            if (!dispatcher.isShuttingDown()) {
                dispatcher.shutdownNow();
            }
        }
    }

    /**
     * A rejection before a worker is created must roll back the accepted counter so an idle
     * operation does not see a phantom task.
     */
    @Test
    void shouldRollbackAcceptedCounterWhenExecutorRejectsSubmission() {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "accept-rollback", 2, new RejectingExecutor());
        try {
            assertThatThrownBy(() -> dispatcher.submit(
                () -> "never",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(ex -> assertThat(((ThrowableConventionErrorCode) ex).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
            assertThat(dispatcher.executeWhenIdle(() -> "idle"))
                .as("the rejected task must not leave a phantom accepted count")
                .isEqualTo("idle");
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** Accepts one command without exposing it through ThreadPoolExecutor's active/queue metrics. */
    private static final class HoldingExecutor extends ThreadPoolExecutor {

        private Runnable held;

        HoldingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            if (held != null) {
                throw new RejectedExecutionException("test executor already holds a command");
            }
            held = command;
        }

        void runHeldTask() {
            if (held == null) {
                throw new IllegalStateException("no held command");
            }
            Runnable command = held;
            held = null;
            command.run();
        }

    }

    /** Rejects before accepting a command while leaving the dispatcher itself open. */
    private static final class RejectingExecutor extends ThreadPoolExecutor {

        RejectingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("test rejection");
        }

    }

    private static boolean awaitIdle(HubInstanceDispatcher dispatcher, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (dispatcher.activeCount() == 0 && dispatcher.pendingCount() == 0) {
                return true;
            }
            Thread.sleep(5L);
        }
        return dispatcher.activeCount() == 0 && dispatcher.pendingCount() == 0;
    }


    /**
     * clearPending cancels only queued tasks; the active worker finishes.
     * Blocked dispatch on a cleared pending task fails with INSTANCE_QUEUE_CLEARED.
     */
    @Test
    void shouldClearPendingWithoutStoppingActiveTask() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("clear", 4);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            Future<String> active = dispatcher.submit(() -> {
                activeStarted.countDown();
                releaseActive.await(5, TimeUnit.SECONDS);
                return "active-done";
            }, deadline);
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch dispatchStarted = new CountDownLatch(1);
            CountDownLatch dispatchDone = new CountDownLatch(1);
            AtomicInteger domainHit = new AtomicInteger();
            Thread blocked = new Thread(() -> {
                dispatchStarted.countDown();
                try {
                    dispatcher.dispatch(() -> "should-be-cleared", deadline);
                } catch (ThrowableConventionErrorCode ex) {
                    if (HubErrorCodes.INSTANCE_QUEUE_CLEARED.getCode().equals(ex.getCode())) {
                        domainHit.set(1);
                    }
                } finally {
                    dispatchDone.countDown();
                }
            }, "blocked-dispatch");
            blocked.setDaemon(true);
            blocked.start();
            assertThat(dispatchStarted.await(1, TimeUnit.SECONDS)).isTrue();

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (dispatcher.pendingCount() < 1 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(dispatcher.pendingCount()).isGreaterThanOrEqualTo(1);

            Future<String> another = dispatcher.submit(() -> "also-cleared", deadline);
            assertThat(dispatcher.clearPending()).isGreaterThanOrEqualTo(2);
            assertThat(dispatcher.pendingCount()).isZero();
            assertThat(another.isCancelled()).isTrue();
            assertThat(dispatchDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(domainHit.get()).isEqualTo(1);

            assertThat(active.isDone()).isFalse();
            releaseActive.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).isEqualTo("active-done");
            assertThat(dispatcher.dispatch(() -> "after-clear", deadline)).isEqualTo("after-clear");
        } finally {
            releaseActive.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * When stillWanted becomes false while queued, dispatch cancels the pending task and
     * surfaces CLIENT_DISCONNECTED without running the body.
     */
    @Test
    void shouldCancelPendingWhenClientDisconnects() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("disconnect", 4);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicInteger bodyRuns = new AtomicInteger();
        AtomicBoolean clientOpen = new AtomicBoolean(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            dispatcher.submit(() -> {
                activeStarted.countDown();
                releaseActive.await(5, TimeUnit.SECONDS);
                return "hold";
            }, deadline);
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch dispatchStarted = new CountDownLatch(1);
            CountDownLatch dispatchDone = new CountDownLatch(1);
            AtomicInteger domainHit = new AtomicInteger();
            Thread blocked = new Thread(() -> {
                dispatchStarted.countDown();
                try {
                    dispatcher.dispatch(() -> {
                        bodyRuns.incrementAndGet();
                        return "should-not-run";
                    }, deadline, clientOpen::get);
                } catch (ThrowableConventionErrorCode ex) {
                    if (HubErrorCodes.CLIENT_DISCONNECTED.getCode().equals(ex.getCode())) {
                        domainHit.set(1);
                    }
                } finally {
                    dispatchDone.countDown();
                }
            }, "disconnect-dispatch");
            blocked.setDaemon(true);
            blocked.start();
            assertThat(dispatchStarted.await(1, TimeUnit.SECONDS)).isTrue();

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (dispatcher.pendingCount() < 1 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(dispatcher.pendingCount()).isGreaterThanOrEqualTo(1);

            clientOpen.set(false);
            assertThat(dispatchDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(domainHit.get()).isEqualTo(1);
            assertThat(bodyRuns.get()).isZero();

            releaseActive.countDown();
        } finally {
            releaseActive.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * clearPending cancels queued tasks and notifies each discard owner exactly once;
     * a repeated clear sees an empty queue and never re-notifies.
     */
    @Test
    void shouldNotifyDiscardOwnerExactlyOnceWhenQueueCleared() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("clear-notify", 4);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicInteger discards = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            dispatcher.submit(() -> {
                activeStarted.countDown();
                releaseActive.await(5, TimeUnit.SECONDS);
                return "active";
            }, deadline);
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<String> first = dispatcher.submit(
                "e1", () -> "first", deadline, discards::incrementAndGet);
            Future<String> second = dispatcher.submit(
                "e2", () -> "second", deadline, discards::incrementAndGet);
            assertThat(dispatcher.pendingCount()).isEqualTo(2);

            assertThat(dispatcher.clearPending()).isEqualTo(2);
            assertThat(discards.get()).isEqualTo(2);
            assertThat(first.isCancelled()).isTrue();
            assertThat(second.isCancelled()).isTrue();
            assertThat(dispatcher.pendingCount()).isZero();

            // A second clear has nothing to drop and must not re-notify.
            assertThat(dispatcher.clearPending()).isZero();
            assertThat(discards.get()).isEqualTo(2);
        } finally {
            releaseActive.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * Force shutdown drops queued tasks and notifies their discard owners once.
     */
    @Test
    void shouldNotifyDiscardOwnerOnForcedShutdown() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("shutdown-notify", 2);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicInteger discards = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            dispatcher.submit(() -> {
                activeStarted.countDown();
                try {
                    releaseActive.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "active";
            }, deadline);
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<String> pending = dispatcher.submit(
                "e9", () -> "pending", deadline, discards::incrementAndGet);

            dispatcher.shutdownNow();
            assertThat(discards.get()).isEqualTo(1);
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            releaseActive.countDown();
        }
    }

    /**
     * cancelPending finds and cancels exactly the matching queued handle, releasing the
     * slot and notifying the owner; unknown or already-cancelled ids are no-ops.
     */
    @Test
    void shouldCancelSinglePendingTaskByExecutionId() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("cancel-one", 4);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicInteger discards = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            dispatcher.submit(() -> {
                activeStarted.countDown();
                releaseActive.await(5, TimeUnit.SECONDS);
                return "active";
            }, deadline);
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<String> target = dispatcher.submit(
                "t1", () -> "target", deadline, discards::incrementAndGet);
            Future<String> other = dispatcher.submit(
                "t2", () -> "other", deadline, discards::incrementAndGet);
            assertThat(dispatcher.pendingCount()).isEqualTo(2);

            assertThat(dispatcher.cancelPending("t1")).isTrue();
            assertThat(target.isCancelled()).isTrue();
            assertThat(other.isCancelled()).isFalse();
            assertThat(dispatcher.pendingCount()).isEqualTo(1);
            assertThat(discards.get()).isEqualTo(1);

            // The handle is gone and unknown ids never notify.
            assertThat(dispatcher.cancelPending("t1")).isFalse();
            assertThat(dispatcher.cancelPending("unknown")).isFalse();
            assertThat(discards.get()).isEqualTo(1);
        } finally {
            releaseActive.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * Dispatcher with maxConcurrency=3 must run up to 3 workers simultaneously; the 4th task
     * remains queued in pending until a worker frees up.
     */
    @Test
    void shouldAllowConfiguredWorkersToRunConcurrentlyUpToMaxConcurrencyAndQueueRemaining() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("concurrent-3", 3, 2);
        CountDownLatch threeRunning = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            Future<String> t1 = dispatcher.submit(() -> {
                threeRunning.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t1";
            }, deadline);
            Future<String> t2 = dispatcher.submit(() -> {
                threeRunning.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t2";
            }, deadline);
            Future<String> t3 = dispatcher.submit(() -> {
                threeRunning.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t3";
            }, deadline);

            assertThat(threeRunning.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.activeCount()).isEqualTo(3);

            Future<String> t4 = dispatcher.submit(() -> "t4", deadline);
            assertThat(dispatcher.pendingCount()).isEqualTo(1);
            assertThat(dispatcher.acceptedNotTerminalCount()).isEqualTo(4);

            release.countDown();
            assertThat(t1.get(2, TimeUnit.SECONDS)).isEqualTo("t1");
            assertThat(t2.get(2, TimeUnit.SECONDS)).isEqualTo("t2");
            assertThat(t3.get(2, TimeUnit.SECONDS)).isEqualTo("t3");
            assertThat(t4.get(2, TimeUnit.SECONDS)).isEqualTo("t4");
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * When maxPending=0, up to maxConcurrency tasks can be accepted and run immediately;
     * once all workers are occupied, any further submission is immediately rejected.
     */
    @Test
    void shouldAcceptTasksUpToMaxConcurrencyWhenMaxPendingIsZeroAndRejectImmediatelyWhenFull() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("zero-pending", 2, 0);
        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            Future<String> t1 = dispatcher.submit(() -> {
                twoRunning.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t1";
            }, deadline);
            Future<String> t2 = dispatcher.submit(() -> {
                twoRunning.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t2";
            }, deadline);

            assertThat(twoRunning.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.acceptedNotTerminalCount()).isEqualTo(2);

            assertThatThrownBy(() -> dispatcher.submit(() -> "rejected", deadline))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));

            release.countDown();
            assertThat(t1.get(2, TimeUnit.SECONDS)).isEqualTo("t1");
            assertThat(t2.get(2, TimeUnit.SECONDS)).isEqualTo("t2");

            assertThat(awaitIdle(dispatcher, 2000L)).isTrue();
            Future<String> t3 = dispatcher.submit(() -> "t3", deadline);
            assertThat(t3.get(2, TimeUnit.SECONDS)).isEqualTo("t3");
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * Total accepted capacity must be strictly maxConcurrency + maxPending.
     */
    @Test
    void shouldEnforceTotalCapacityStrictlyAsMaxConcurrencyPlusMaxPending() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("capacity-check", 2, 3);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int id = i;
                futures.add(dispatcher.submit(() -> {
                    running.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "active-" + id;
                }, deadline));
            }
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 3; i++) {
                final int id = i;
                futures.add(dispatcher.submit(() -> "queued-" + id, deadline));
            }

            assertThat(dispatcher.acceptedNotTerminalCount()).isEqualTo(5);

            assertThatThrownBy(() -> dispatcher.submit(() -> "overflow", deadline))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));

            release.countDown();
            for (Future<String> f : futures) {
                assertThat(f.get(2, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * Gate read locks (PARALLEL_SAFE) must be acquirable concurrently by multiple workers.
     */
    @Test
    void shouldAllowParallelSafeReadLocksToExecuteConcurrently() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("gate-read", 3, 0);
        CountDownLatch bothInGate = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);

        Thread thread1 = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                Long.MAX_VALUE,
                () -> {
                bothInGate.countDown();
                releaseGate.await(5, TimeUnit.SECONDS);
                return null;
            }));

        Thread thread2 = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                Long.MAX_VALUE,
                () -> {
                bothInGate.countDown();
                releaseGate.await(5, TimeUnit.SECONDS);
                return null;
            }));

        thread1.start();
        thread2.start();
        try {
            assertThat(bothInGate.await(2, TimeUnit.SECONDS))
                .as("Both PARALLEL_SAFE readers hold the gate concurrently")
                .isTrue();
        } finally {
            releaseGate.countDown();
            thread1.join(2000);
            thread2.join(2000);
            dispatcher.shutdownNow();
        }
    }

    /**
     * EXCLUSIVE write lock must not overlap with any read lock or write lock.
     */
    @Test
    void shouldPreventExclusiveExecutionFromOverlappingWithParallelSafeExecution() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("gate-exclusive", 2, 0);

        AtomicBoolean readerActive = new AtomicBoolean(false);
        AtomicBoolean writerOverlappedWithReader = new AtomicBoolean(false);
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);

        Thread readerThread = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                Long.MAX_VALUE,
                () -> {
                readerActive.set(true);
                try {
                    readerStarted.countDown();
                    releaseReader.await(5, TimeUnit.SECONDS);
                    return null;
                } finally {
                    readerActive.set(false);
                }
            }));

        readerThread.start();
        assertThat(readerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread writerThread = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.EXCLUSIVE,
                Long.MAX_VALUE,
                () -> {
                if (readerActive.get()) {
                    writerOverlappedWithReader.set(true);
                }
                return null;
            }));

        writerThread.start();
        Thread.sleep(100);
        assertThat(writerThread.isAlive()).as("Writer must wait for reader to release").isTrue();

        releaseReader.countDown();
        readerThread.join(2000);
        writerThread.join(2000);

        assertThat(writerOverlappedWithReader.get()).isFalse();
        dispatcher.shutdownNow();
    }

    /**
     * Fair ReadWriteLock ensures that a waiting EXCLUSIVE writer is not starved by subsequent readers.
     */
    @Test
    void shouldPreventReaderBargingWhenFairWriterIsWaiting() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("fairness", 3, 0);

        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch reader1Holding = new CountDownLatch(1);
        CountDownLatch releaseReader1 = new CountDownLatch(1);
        CountDownLatch writerQueued = new CountDownLatch(1);

        Thread r1 = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                Long.MAX_VALUE,
                () -> {
                reader1Holding.countDown();
                releaseReader1.await(5, TimeUnit.SECONDS);
                executionOrder.add("reader1");
                return null;
            }));

        Thread w = new Thread(() -> {
            try {
                reader1Holding.await(5, TimeUnit.SECONDS);
                writerQueued.countDown();
                dispatcher.executeGuarded(
                    HubExecutionConcurrencyMode.EXCLUSIVE,
                    Long.MAX_VALUE,
                    () -> {
                    executionOrder.add("writer");
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread r2 = new Thread(() -> {
            try {
                writerQueued.await(5, TimeUnit.SECONDS);
                Thread.sleep(50); // Ensure writer is already waiting on writeLock
                dispatcher.executeGuarded(
                    HubExecutionConcurrencyMode.PARALLEL_SAFE,
                    Long.MAX_VALUE,
                    () -> {
                    executionOrder.add("reader2");
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        r1.start();
        w.start();
        r2.start();

        assertThat(writerQueued.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        releaseReader1.countDown();
        r1.join(2000);
        w.join(2000);
        r2.join(2000);

        assertThat(executionOrder)
            .as("Fair lock ensures writer executes before reader2")
            .containsExactly("reader1", "writer", "reader2");

        dispatcher.shutdownNow();
    }

    /**
     * A task that cannot acquire the gate before its deadline fails without running its body.
     */
    @Test
    void shouldTimeoutWhenGateCannotBeAcquiredWithinDeadline() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("gate-timeout", 2, 0);

        CountDownLatch writer1Holding = new CountDownLatch(1);
        CountDownLatch releaseWriter1 = new CountDownLatch(1);
        AtomicBoolean bodyRan = new AtomicBoolean();

        Thread writer1 = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.EXCLUSIVE,
                Long.MAX_VALUE,
                () -> {
                writer1Holding.countDown();
                releaseWriter1.await(5, TimeUnit.SECONDS);
                return null;
            }));
        writer1.start();

        assertThat(writer1Holding.await(2, TimeUnit.SECONDS)).isTrue();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        assertThatThrownBy(() -> dispatcher.executeGuarded(
            HubExecutionConcurrencyMode.EXCLUSIVE,
            deadline,
            () -> {
                bodyRan.set(true);
                return null;
            }))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(ex -> assertThat(((ThrowableConventionErrorCode) ex).getCode())
                .isEqualTo(HubErrorCodes.QUEUE_WAIT_TIMEOUT.getCode()));
        assertThat(bodyRan).isFalse();

        releaseWriter1.countDown();
        writer1.join(2000);
        dispatcher.shutdownNow();
    }

    /** Gate interruption is a failure, not a false deadline timeout, and preserves the flag. */
    @Test
    void shouldPreserveInterruptWhileWaitingForConcurrencyGate() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("gate-interrupt", 2, 0);
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        Thread holder = new Thread(() ->
            dispatcher.executeGuarded(
                HubExecutionConcurrencyMode.EXCLUSIVE,
                Long.MAX_VALUE,
                () -> {
                    holderStarted.countDown();
                    releaseHolder.await(5, TimeUnit.SECONDS);
                    return null;
                }));
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            try {
                dispatcher.executeGuarded(
                    HubExecutionConcurrencyMode.EXCLUSIVE,
                    Long.MAX_VALUE,
                    () -> null);
            } catch (Throwable ex) {
                failure.set(ex);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        holder.start();
        assertThat(holderStarted.await(2, TimeUnit.SECONDS)).isTrue();
        waiter.start();
        assertThat(waiterStarted.await(2, TimeUnit.SECONDS)).isTrue();
        waiter.interrupt();
        waiter.join(2000);
        releaseHolder.countDown();
        holder.join(2000);

        assertThat(failure.get())
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(ex -> assertThat(((ThrowableConventionErrorCode) ex).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_EXECUTION_FAILED.getCode()));
        assertThat(interrupted).isTrue();
        dispatcher.shutdownNow();
    }

    /**
     * Dynamic concurrency updates: scaling up increases active worker capacity immediately;
     * scaling down allows running workers to finish and natural shrink.
     */
    @Test
    void shouldDynamicallyScaleUpAndScaleDownConcurrency() throws Exception {
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher("dyn-concurrency", 1, 5);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            assertThat(dispatcher.getMaxConcurrency()).isEqualTo(1);

            // Scale up to 3
            dispatcher.updateMaxConcurrency(3);
            assertThat(dispatcher.getMaxConcurrency()).isEqualTo(3);

            CountDownLatch threeRunning = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);

            Future<String> f1 = dispatcher.submit(() -> { threeRunning.countDown(); release.await(5, TimeUnit.SECONDS); return "1"; }, deadline);
            Future<String> f2 = dispatcher.submit(() -> { threeRunning.countDown(); release.await(5, TimeUnit.SECONDS); return "2"; }, deadline);
            Future<String> f3 = dispatcher.submit(() -> { threeRunning.countDown(); release.await(5, TimeUnit.SECONDS); return "3"; }, deadline);

            assertThat(threeRunning.await(2, TimeUnit.SECONDS)).as("Scaled-up workers run concurrently").isTrue();
            assertThat(dispatcher.activeCount()).isEqualTo(3);

            // Scale down to 1 while 3 are active
            dispatcher.updateMaxConcurrency(1);
            assertThat(dispatcher.getMaxConcurrency()).isEqualTo(1);

            release.countDown();
            assertThat(f1.get(2, TimeUnit.SECONDS)).isEqualTo("1");
            assertThat(f2.get(2, TimeUnit.SECONDS)).isEqualTo("2");
            assertThat(f3.get(2, TimeUnit.SECONDS)).isEqualTo("3");

            assertThat(awaitIdle(dispatcher, 2000L)).isTrue();
        } finally {
            dispatcher.shutdownNow();
        }
    }
}
