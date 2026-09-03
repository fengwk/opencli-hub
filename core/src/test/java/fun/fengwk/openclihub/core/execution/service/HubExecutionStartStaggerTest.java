package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link HubExecutionStartStagger}.
 *
 * <p>Uses injectable monotonic clock, delay generator, and sleeper to test all scheduling,
 * isolation, reset, timeout, interrupt, and oversleep invariants without real-time delays.
 *
 * @author fengwk
 */
class HubExecutionStartStaggerTest {

    private static final long MIN_MS = 3000L;
    private static final long MAX_MS = 5000L;
    private static final long MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(MIN_MS);
    private static final long MAX_NANOS = TimeUnit.MILLISECONDS.toNanos(MAX_MS);

    /**
     * Intent: Verify that when no execution is active or waiting for a site, the first task
     * starts immediately without any delay or sleep.
     * Effectiveness: Asserts that sleeper is never called and task starts at current time.
     */
    @Test
    void shouldStartFirstExecutionImmediatelyWithoutSleep() {
        AtomicLong currentTime = new AtomicLong(1_000_000_000L); // 1s
        AtomicLong totalSlept = new AtomicLong(0L);
        AtomicBoolean executed = new AtomicBoolean(false);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> MIN_NANOS,
            nanos -> totalSlept.addAndGet(nanos));

        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            executed.set(true);
            assertThat(stagger.inFlightCount("bilibili")).isEqualTo(1);
        });

        assertThat(executed).isTrue();
        assertThat(totalSlept.get()).isEqualTo(0L);
        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that concurrent/overlapping executions for the same site are assigned
     * monotonic FIFO start slots separated by the configured gap.
     * Effectiveness: Injects deterministic delay and advances virtual time upon sleep,
     * asserting that the second task starts exactly at T_first + delay.
     */
    @Test
    void shouldSeparateOverlappingSameSiteStartsByConfiguredGap() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L); // 10s
        List<Long> actualStartTimes = new ArrayList<>();
        long gapNanos = TimeUnit.MILLISECONDS.toNanos(3500L);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> gapNanos,
            nanos -> currentTime.addAndGet(nanos)); // advance virtual time on sleep

        // First task starts at T=10s, takes time until T=15s
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            actualStartTimes.add(currentTime.get());

            // Second task arrives at T=10.5s while first task is still active
            currentTime.set(10_500_000_000L);
            stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
                actualStartTimes.add(currentTime.get());
            });
        });

        assertThat(actualStartTimes).hasSize(2);
        long firstStart = actualStartTimes.get(0);
        long secondStart = actualStartTimes.get(1);
        assertThat(firstStart).isEqualTo(10_000_000_000L);
        assertThat(secondStart).isEqualTo(10_000_000_000L + gapNanos);
        assertThat(secondStart - firstStart).isEqualTo(gapNanos);
    }

    /**
     * Intent: Verify that when a prior task oversleeps, the next task is still separated from
     * the prior task's ACTUAL start by a fresh random gap, rather than starting too close or ahead.
     * Effectiveness: Simulates a 2000ms oversleep in task 2, asserting that task 3 starts
     * exactly at actualStart(task2) + freshGap, preserving the requested minimum interval.
     */
    @Test
    void shouldSeparateStartFromOversleptPriorStartByFreshConfiguredGap() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L); // 10s
        List<Long> actualStartTimes = new ArrayList<>();
        long firstGap = TimeUnit.MILLISECONDS.toNanos(3000L);
        long secondGap = TimeUnit.MILLISECONDS.toNanos(4000L);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean oversleepOnce = new AtomicBoolean(true);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> callCount.getAndIncrement() == 0 ? firstGap : secondGap,
            nanos -> {
                // Simulate oversleep for the first sleeping task (Task 2): sleep nanos + 2000ms
                if (oversleepOnce.getAndSet(false)) {
                    currentTime.addAndGet(nanos + TimeUnit.MILLISECONDS.toNanos(2000L));
                } else {
                    currentTime.addAndGet(nanos);
                }
            });

        // Task 1 starts at T=10s
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            actualStartTimes.add(currentTime.get());

            // Task 2 arrives at T=10.5s while Task 1 is running
            currentTime.set(10_500_000_000L);
            stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
                actualStartTimes.add(currentTime.get());

                // Task 3 arrives at T=15.1s while Task 2 is running
                currentTime.set(15_100_000_000L);
                stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
                    actualStartTimes.add(currentTime.get());
                });
            });
        });

        assertThat(actualStartTimes).hasSize(3);
        long task1Start = actualStartTimes.get(0);
        long task2Start = actualStartTimes.get(1);
        long task3Start = actualStartTimes.get(2);

        // Task 1 started immediately at T=10s
        assertThat(task1Start).isEqualTo(10_000_000_000L);
        // Task 2 was scheduled for 13s, but overslept by 2s -> actually started at 15s
        assertThat(task2Start).isEqualTo(15_000_000_000L);
        // Task 3 must be separated from Task 2's ACTUAL start (15s) by secondGap (4s) -> 19s
        assertThat(task3Start).isEqualTo(15_000_000_000L + secondGap);
        assertThat(task3Start - task2Start).isEqualTo(secondGap);
    }

    /**
     * Intent: Verify that coordination is strictly scoped by site: an execution on site B
     * must never wait for an active/reserved execution on site A.
     * Effectiveness: Runs an overlapping execution for site B while site A is active, asserting
     * site B starts immediately with 0 sleep.
     */
    @Test
    void shouldCoordinateDifferentSitesIndependently() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        List<String> startOrder = new ArrayList<>();

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> MIN_NANOS,
            nanos -> currentTime.addAndGet(nanos));

        // Start site A
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            startOrder.add("bilibili-1-started");

            // Arrive on site B at T=10.1s
            currentTime.set(10_100_000_000L);
            stagger.execute("github", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
                startOrder.add("github-1-started");
                assertThat(currentTime.get()).isEqualTo(10_100_000_000L); // 0 sleep!
            });
        });

        assertThat(startOrder).containsExactly("bilibili-1-started", "github-1-started");
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that when all active and reserved executions for a site finish, the site
     * state is completely reset so the next isolated execution starts immediately.
     * Effectiveness: Executes task 1, waits until completion, then verifies task 2 starts with 0 sleep.
     */
    @Test
    void shouldResetSiteStateAfterAllExecutionsFinish() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        AtomicLong totalSlept = new AtomicLong(0L);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> MIN_NANOS,
            nanos -> totalSlept.addAndGet(nanos));

        // Task 1 runs and finishes
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {});
        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);

        // Task 2 arrives later: must start immediately with 0 sleep
        currentTime.set(20_000_000_000L);
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {});
        assertThat(totalSlept.get()).isEqualTo(0L);
        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that setting both min and max to 0 disables staggering entirely.
     * Effectiveness: Asserts isDisabled() is true, sleeper is never called, and multiple
     * overlapping executions execute immediately without retaining site state.
     */
    @Test
    void shouldBypassStaggerWhenDisabled() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        AtomicInteger sleeps = new AtomicInteger(0);
        AtomicInteger runs = new AtomicInteger(0);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            0L,
            0L,
            currentTime::get,
            (min, max) -> 0L,
            nanos -> sleeps.incrementAndGet());

        assertThat(stagger.isDisabled()).isTrue();

        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            runs.incrementAndGet();
            stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
                runs.incrementAndGet();
            });
        });

        assertThat(runs.get()).isEqualTo(2);
        assertThat(sleeps.get()).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that if deadline is exceeded before acquiring the per-site start gate,
     * QUEUE_WAIT_TIMEOUT is thrown without waiting on the gate, and ref count is released.
     * Effectiveness: Passes a deadline already in the past, verifies domain error and clean release.
     */
    @Test
    void shouldThrowQueueWaitTimeoutWhenDeadlineElapsedBeforeGateAcquisition() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> MIN_NANOS,
            nanos -> {});

        long pastDeadline = 9_000_000_000L; // 9s < 10s
        assertThatThrownBy(() -> stagger.execute(
            "bilibili",
            HubExecutionConcurrencyMode.PARALLEL_SAFE,
            pastDeadline,
            () -> {}))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .hasMessageContaining("Execution deadline elapsed while waiting for start stagger gate")
            .matches(ex -> HubErrorCodes.QUEUE_WAIT_TIMEOUT.getCode().equals(
                ((ThrowableConventionErrorCode) ex).getErrorCode().getCode()));

        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that if a reserved start cannot occur before the execution deadline while inside the gate,
     * QUEUE_WAIT_TIMEOUT is thrown, task is not run, and ref count is released.
     * Effectiveness: Verifies that when nextAllowedStartNanos exceeds deadline, timeout occurs immediately
     * and site state is cleanly released.
     */
    @Test
    void shouldThrowQueueWaitTimeoutAndReleaseReservationWhenWaitExceedsDeadline() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        AtomicBoolean taskRan = new AtomicBoolean(false);
        long gapNanos = TimeUnit.MILLISECONDS.toNanos(3000L);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> gapNanos,
            nanos -> {});

        // Task 1 starts at T=10s, next allowed start is T=13s
        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            // Task 2 arrives at T=10.1s with deadline at T=12s (< 13s next allowed start)
            currentTime.set(10_100_000_000L);
            long shortDeadline = 12_000_000_000L;

            assertThatThrownBy(() -> stagger.execute(
                "bilibili",
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                shortDeadline,
                () -> taskRan.set(true)))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .hasMessageContaining("Execution deadline elapsed while waiting for start stagger")
                .matches(ex -> HubErrorCodes.QUEUE_WAIT_TIMEOUT.getCode().equals(
                    ((ThrowableConventionErrorCode) ex).getErrorCode().getCode()));

            assertThat(taskRan).isFalse();
            assertThat(stagger.inFlightCount("bilibili")).isEqualTo(1);
        });

        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(stagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that thread interruption during stagger sleep restores interrupt status,
     * releases reservation, throws OPENCLI_EXECUTION_FAILED, and does not run task.
     * Effectiveness: Sleeper throws InterruptedException; asserts Thread.currentThread().isInterrupted()
     * is set and site ref count is safely decremented.
     */
    @Test
    void shouldHandleInterruptDuringWaitRestoringFlagAndReleasingReservation() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        AtomicBoolean taskRan = new AtomicBoolean(false);
        long gapNanos = TimeUnit.MILLISECONDS.toNanos(3000L);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> gapNanos,
            nanos -> {
                throw new InterruptedException("simulated interrupt");
            });

        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            currentTime.set(10_100_000_000L);

            assertThatThrownBy(() -> stagger.execute(
                "bilibili",
                HubExecutionConcurrencyMode.PARALLEL_SAFE,
                Long.MAX_VALUE,
                () -> taskRan.set(true)))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .hasMessageContaining("Interrupted while waiting for start stagger")
                .matches(ex -> HubErrorCodes.OPENCLI_EXECUTION_FAILED.getCode().equals(
                    ((ThrowableConventionErrorCode) ex).getErrorCode().getCode()));

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            // Clear interrupt flag for test runner
            Thread.interrupted();

            assertThat(taskRan).isFalse();
            assertThat(stagger.inFlightCount("bilibili")).isEqualTo(1);
        });

        assertThat(stagger.inFlightCount("bilibili")).isEqualTo(0);
    }

    /**
     * Intent: Verify that non-PARALLEL_SAFE modes (such as EXCLUSIVE) bypass start staggering entirely.
     * Effectiveness: Executes an EXCLUSIVE task while PARALLEL_SAFE task is active, verifying
     * it does not sleep and does not register in stagger site coordination.
     */
    @Test
    void shouldBypassStaggerForNonParallelSafeModes() {
        AtomicLong currentTime = new AtomicLong(10_000_000_000L);
        AtomicLong totalSlept = new AtomicLong(0L);
        AtomicBoolean exclusiveRan = new AtomicBoolean(false);

        HubExecutionStartStagger stagger = new HubExecutionStartStagger(
            MIN_MS,
            MAX_MS,
            currentTime::get,
            (min, max) -> MIN_NANOS,
            nanos -> totalSlept.addAndGet(nanos));

        stagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            assertThat(stagger.inFlightCount("bilibili")).isEqualTo(1);

            // EXCLUSIVE task targeting same site
            stagger.execute("bilibili", HubExecutionConcurrencyMode.EXCLUSIVE, Long.MAX_VALUE, () -> {
                exclusiveRan.set(true);
                // inFlightCount remains 1 (only the PARALLEL_SAFE task is counted)
                assertThat(stagger.inFlightCount("bilibili")).isEqualTo(1);
            });
        });

        assertThat(exclusiveRan).isTrue();
        assertThat(totalSlept.get()).isEqualTo(0L);
    }

    /**
     * Intent: Verify that invalid configuration values fail clearly with IllegalArgumentException.
     * Effectiveness: Tests negative min, negative max, max < min, and millis-to-nanos overflow.
     */
    @Test
    void shouldRejectInvalidConfigurationRanges() {
        assertThatThrownBy(() -> HubExecutionStartStagger.validateProperties(-1L, 5000L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parallelStartStaggerMinMillis must not be negative");

        assertThatThrownBy(() -> HubExecutionStartStagger.validateProperties(3000L, -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parallelStartStaggerMaxMillis must not be negative");

        assertThatThrownBy(() -> HubExecutionStartStagger.validateProperties(5000L, 3000L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be less than minMillis");

        long overflowMillis = (Long.MAX_VALUE / 1_000_000L) + 1L;
        assertThatThrownBy(() -> HubExecutionStartStagger.validateProperties(0L, overflowMillis))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nanosecond overflow");
    }

}
