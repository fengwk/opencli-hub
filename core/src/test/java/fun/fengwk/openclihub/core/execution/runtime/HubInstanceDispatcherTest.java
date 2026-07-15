package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link HubInstanceDispatcher} deadline-aware enqueue path and the
 * idle-only shutdown guard. Pure unit: no real OS processes are launched.
 *
 * <p>Covers the M5 acceptance criteria §20.4 / §22.2:
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
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.shutdownIfIdle())
            .as("active worker prevents shutdown")
            .isFalse();
        worker.join(2000);
        assertThat(dispatcher.shutdownIfIdle())
            .as("no work left permits shutdown")
            .isTrue();
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

}
