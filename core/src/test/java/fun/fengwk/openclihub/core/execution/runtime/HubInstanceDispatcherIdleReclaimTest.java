package fun.fengwk.openclihub.core.execution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Deterministic (sleep-free) coverage for the continuous-idle adapter tab reclaim window and
 * guard in {@link HubInstanceDispatcher}.
 *
 * <p>Every timing assertion drives an injected monotonic ticker, so the tests never depend on
 * wall-clock advancement. Covered: threshold gating, negative ticker values, active/queued/
 * accepted-handoff busy guards, extremely short task reset, running-future cancellation not
 * opening the window early, once-per-period semantics with re-arm, failure retry, and idle
 * window restoration after a rejected submission.
 */
class HubInstanceDispatcherIdleReclaimTest {

    // -----------------------------------------------------------------------------------------
    //  Threshold / clock
    // -----------------------------------------------------------------------------------------

    /**
     * The idle window is a difference between two ticker reads, so a legitimately negative
     * {@code System.nanoTime()} baseline must still gate reclaim correctly.
     */
    @Test
    void shouldGateReclaimByThresholdWithNegativeTicker() {
        AtomicLong ticker = new AtomicLong(-1_000_000_000L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "neg", 1, 0, newExecutor(1), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            assertThat(dispatcher.idleDurationNanos()).isZero();

            ticker.addAndGet(999_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(1_000_000_000L, count(runs)))
                .as("999ms idle must not satisfy a 1s TTL")
                .isFalse();

            ticker.addAndGet(1_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(1_000_000_000L, count(runs)))
                .isTrue();
            assertThat(runs).hasValue(1);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** TTL 0 reclaims on the very next eligible check. */
    @Test
    void shouldReclaimImmediatelyForZeroTtl() {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "zero", 1, 0, newExecutor(1), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
            assertThat(runs).hasValue(1);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------------------------
    //  Busy guards
    // -----------------------------------------------------------------------------------------

    /** Active and queued work both block reclaim; the window opens once everything drains. */
    @Test
    void shouldSkipReclaimWhileActiveOrQueuedThenReclaimAfterDrain() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "busy", 1, 1, newExecutor(1), ticker::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try {
            Future<String> active = dispatcher.submit(() -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "active";
            }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            ticker.addAndGet(100_000_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("active body blocks reclaim")
                .isFalse();

            Future<String> queued = dispatcher.submit(
                () -> "queued", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(dispatcher.pendingCount()).isEqualTo(1);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("queued work blocks reclaim")
                .isFalse();

            release.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).isEqualTo("active");
            assertThat(queued.get(2, TimeUnit.SECONDS)).isEqualTo("queued");
            assertThat(awaitQuiescent(dispatcher, 2_000L)).isTrue();

            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
            assertThat(runs).hasValue(1);
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    /**
     * The acceptance handoff window (task accepted, worker not yet started, queue empty) must
     * count as busy even though the executor exposes no active or queued task.
     */
    @Test
    void shouldSkipReclaimDuringAcceptedHandoffBeforeWorkerStarts() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HoldingExecutor executor = new HoldingExecutor();
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "handoff", 1, 0, executor, ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            Future<String> submitted = dispatcher.submit(
                () -> "held", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(dispatcher.activeCount()).isZero();
            assertThat(dispatcher.pendingCount()).isZero();
            assertThat(dispatcher.acceptedNotTerminalCount()).isOne();

            ticker.addAndGet(100_000_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("accepted-but-not-started work blocks reclaim")
                .isFalse();

            executor.runHeldTask();
            assertThat(submitted.get(2, TimeUnit.SECONDS)).isEqualTo("held");
            assertThat(awaitQuiescent(dispatcher, 2_000L)).isTrue();
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
        } finally {
            dispatcher.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------------------------
    //  Idle window reset semantics
    // -----------------------------------------------------------------------------------------

    /**
     * An extremely short task must reset the idle window at its true completion, not leave the
     * timer running from before the task arrived.
     */
    @Test
    void shouldResetIdleWindowForExtremelyShortTask() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "short", 1, 0, newExecutor(1), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            // Pretend the instance had already been idle for 5 seconds before the task.
            ticker.set(5_000_000_000L);
            dispatcher.submit(() -> "fast", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(awaitQuiescent(dispatcher, 2_000L)).isTrue();

            assertThat(dispatcher.idleDurationNanos())
                .as("idle window restarts at the short task's completion")
                .isZero();
            ticker.addAndGet(300_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(300_000_000L, count(runs))).isTrue();
            assertThat(runs).hasValue(1);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /**
     * Cancelling a still-running future fires {@code done()} before the body stops. The idle
     * window must not open at cancel time and start accruing against the still-running body.
     */
    @Test
    void shouldNotStartIdleWindowEarlyWhenRunningFutureCancelled() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "cancel", 1, 0, newExecutor(1), ticker::get);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        try {
            Future<String> running = dispatcher.submit(() -> {
                started.countDown();
                // Ignore interrupts: the body keeps running after cancel(true) until released.
                while (!released.get()) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
                return "body-done";
            }, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            running.cancel(true);
            assertThat(running.isCancelled()).isTrue();

            ticker.addAndGet(100_000_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("cancelled-but-still-running body must not open the idle window")
                .isFalse();

            released.set(true);
            assertThat(awaitIdleWindow(dispatcher, 2_000L)).isTrue();
            assertThat(dispatcher.idleDurationNanos())
                .as("idle window starts at the true body end, not at cancel time")
                .isZero();
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
        } finally {
            released.set(true);
            dispatcher.shutdownNow();
        }
    }

    /** Clearing a queued task opens the idle window (the task never ran). */
    @Test
    void shouldOpenIdleWindowAfterQueuedTaskCleared() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "clear", 1, 1, newExecutor(1), ticker::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try {
            dispatcher.submit(() -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "active";
            }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            dispatcher.submit(() -> "pending", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(dispatcher.pendingCount()).isEqualTo(1);

            assertThat(dispatcher.clearPending()).isEqualTo(1);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("active body still blocks reclaim after the queued task is cleared")
                .isFalse();

            release.countDown();
            assertThat(awaitQuiescent(dispatcher, 2_000L)).isTrue();
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------------------------
    //  Once per period / retry / rollback
    // -----------------------------------------------------------------------------------------

    /** A successful reclaim runs at most once per idle period and re-arms after new work. */
    @Test
    void shouldReclaimOncePerIdlePeriodAndRearmAfterNewTask() throws Exception {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "once", 1, 0, newExecutor(1), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
            ticker.addAndGet(10_000_000_000L);
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("same idle period must not reclaim twice")
                .isFalse();
            assertThat(runs).hasValue(1);

            dispatcher.submit(() -> "again", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(awaitQuiescent(dispatcher, 2_000L)).isTrue();
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("a new task opens a new idle period")
                .isTrue();
            assertThat(runs).hasValue(2);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** A failing reclaim must not consume the idle period; the next sweep retries. */
    @Test
    void shouldRetryReclaimAfterCallbackFailure() {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "retry", 1, 0, newExecutor(1), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            assertThatThrownBy(() -> dispatcher.reclaimWhenIdleLongEnough(0L, () -> {
                throw new IllegalStateException("daemon down");
            }))
                .isInstanceOf(IllegalStateException.class);

            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs)))
                .as("failure leaves the idle period eligible")
                .isTrue();
            assertThat(runs).hasValue(1);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /**
     * A submission the executor refuses must restore the previously valid idle window and its
     * once-per-period flag, otherwise a rejected task could permanently disable reclaim.
     */
    @Test
    void shouldRestoreIdleWindowWhenSubmissionRejected() {
        AtomicLong ticker = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher = new HubInstanceDispatcher(
            "reject", 1, 1, new RejectingExecutor(), ticker::get);
        AtomicInteger runs = new AtomicInteger();
        try {
            assertThatThrownBy(() -> dispatcher.submit(
                () -> "x", System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThat(dispatcher.idleDurationNanos())
                .as("rejection restores the still-open idle window")
                .isZero();
            assertThat(dispatcher.reclaimWhenIdleLongEnough(0L, count(runs))).isTrue();
        } finally {
            dispatcher.shutdownNow();
        }

        // Second scenario: an already-consumed idle period must stay consumed through a
        // rejection so the reclaim does not run twice for the same period.
        AtomicLong ticker2 = new AtomicLong(0L);
        HubInstanceDispatcher dispatcher2 = new HubInstanceDispatcher(
            "reject2", 1, 1, new RejectingExecutor(), ticker2::get);
        AtomicInteger runs2 = new AtomicInteger();
        try {
            assertThat(dispatcher2.reclaimWhenIdleLongEnough(0L, count(runs2))).isTrue();
            assertThatThrownBy(() -> dispatcher2.submit(
                () -> "x", System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                .isInstanceOf(ThrowableConventionErrorCode.class);
            assertThat(dispatcher2.reclaimWhenIdleLongEnough(0L, count(runs2)))
                .as("prior successful reclaim flag is restored")
                .isFalse();
            assertThat(runs2).hasValue(1);
        } finally {
            dispatcher2.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------------------------

    private static Callable<Void> count(AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return null;
        };
    }

    private static boolean awaitQuiescent(HubInstanceDispatcher dispatcher, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (dispatcher.acceptedNotTerminalCount() == 0
                && dispatcher.activeCount() == 0
                && dispatcher.pendingCount() == 0) {
                return true;
            }
            Thread.sleep(5L);
        }
        return dispatcher.acceptedNotTerminalCount() == 0
            && dispatcher.activeCount() == 0
            && dispatcher.pendingCount() == 0;
    }

    private static boolean awaitIdleWindow(HubInstanceDispatcher dispatcher, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (dispatcher.idleDurationNanos() >= 0L) {
                return true;
            }
            Thread.sleep(5L);
        }
        return dispatcher.idleDurationNanos() >= 0L;
    }

    private static ThreadPoolExecutor newExecutor(int threads) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "idle-reclaim-test-worker");
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }

    /** Accepts a command without ever exposing or running it, modelling the handoff window. */
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

    /** Rejects before accepting a command. */
    private static final class RejectingExecutor extends ThreadPoolExecutor {

        RejectingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("test rejection");
        }

    }

}
