package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link HubInstanceStartCoordinator} contract:
 * <ul>
 *   <li>every start path serialises on one global lock (no overlapping actions);</li>
 *   <li>the recovery barrier takes effect the moment {@link #beginRecovery()} returns — even
 *       before the recovery task has been scheduled — and API starts wait bounded until
 *       {@link #completeRecovery()};</li>
 *   <li>waits are interruptible and preserve the interrupt flag;</li>
 *   <li>nested begin is rejected, {@code completeRecovery} is idempotent, and the barrier
 *       state is always cleaned up.</li>
 * </ul>
 */
class HubInstanceStartCoordinatorTest {

    private ExecutorService executor;
    private HubInstanceStartCoordinator coordinator;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static HubInstanceStartCoordinator newCoordinator(long coordinationTimeoutMillis) {
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getRuntime().setStartCoordinationTimeoutMillis(coordinationTimeoutMillis);
        return new HubInstanceStartCoordinator(properties);
    }

    @Test
    void shouldSerializeConcurrentStartActions() throws Exception {
        coordinator = newCoordinator(5000);
        int threads = 4;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> coordinator.runStart(() -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return null;
            })));
        }
        for (Future<Void> future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }
        // All actions completed, but at most one was inside the critical section at a time.
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    /**
     * The ApplicationRunner declares the barrier synchronously before the recovery task is
     * even scheduled; an API start submitted while the task is still queued must NOT enter
     * its action, and proceeds only after the sweep completes and releases the barrier.
     */
    @Test
    void shouldHoldApiStartBehindBarrierWhileRecoveryTaskIsQueuedButNotStarted() throws Exception {
        coordinator = newCoordinator(5000);
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            // Occupy the single executor slot so the recovery task stays queued, exactly
            // mirroring the ApplicationRunner scheduling gap.
            CountDownLatch slotBusy = new CountDownLatch(1);
            CountDownLatch releaseSlot = new CountDownLatch(1);
            Future<Void> occupier = single.submit(() -> {
                slotBusy.countDown();
                if (!releaseSlot.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
                return null;
            });
            assertThat(slotBusy.await(2, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean recoveryRan = new AtomicBoolean();
            Future<Void> recoveryTask = single.submit(() -> {
                recoveryRan.set(true);
                coordinator.runRecoveryStart(() -> null);
                return null;
            });

            // runner.run() returns after beginRecovery(); the task has not started yet.
            coordinator.beginRecovery();
            assertThat(coordinator.isRecoveryInProgress()).isTrue();

            AtomicBoolean apiActionRan = new AtomicBoolean();
            Future<Boolean> apiStart = executor.submit(() -> coordinator.runStart(() -> {
                apiActionRan.set(true);
                return true;
            }));
            Thread.sleep(100);
            assertThat(apiStart.isDone())
                .as("API start must wait while the recovery task is queued but not started")
                .isFalse();
            assertThat(apiActionRan).isFalse();

            releaseSlot.countDown();
            occupier.get(2, TimeUnit.SECONDS);
            Thread.sleep(50);
            assertThat(recoveryRan).as("queued recovery task must run after the slot frees")
                .isTrue();

            // Sweep finished: release the barrier and the API start may proceed.
            coordinator.completeRecovery();
            assertThat(coordinator.isRecoveryInProgress()).isFalse();
            assertThat(apiStart.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(apiActionRan).isTrue();
            recoveryTask.get(2, TimeUnit.SECONDS);
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void shouldWaitForRecoveryThenProceed() throws Exception {
        coordinator = newCoordinator(5000);
        AtomicBoolean startRanAfterRecovery = new AtomicBoolean();

        coordinator.beginRecovery();
        Future<Boolean> apiStart = executor.submit(() -> coordinator.runStart(() -> {
            startRanAfterRecovery.set(true);
            return true;
        }));

        Thread.sleep(100);
        assertThat(apiStart.isDone()).as("API start must wait for the recovery sweep").isFalse();
        assertThat(startRanAfterRecovery).isFalse();

        coordinator.completeRecovery();
        assertThat(apiStart.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(startRanAfterRecovery).isTrue();
    }

    @Test
    void shouldTimeoutWithRecoveryInProgressCodeWhileBarrierActive() throws Exception {
        coordinator = newCoordinator(50);
        coordinator.beginRecovery();

        assertThatThrownBy(() -> coordinator.runStart(() -> null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_RECOVERY_IN_PROGRESS));

        coordinator.completeRecovery();
        // After the barrier is released the same start succeeds.
        assertThat(coordinator.runStart(() -> true)).isTrue();
    }

    @Test
    void shouldTimeoutWithBusyCodeWhenAnotherApiStartHoldsLock() throws Exception {
        coordinator = newCoordinator(50);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Void> first = executor.submit(() -> coordinator.runStart(() -> {
            firstEntered.countDown();
            if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return null;
        }));
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> coordinator.runStart(() -> null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_BUSY));

        releaseFirst.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    @Test
    void shouldRestoreInterruptFlagWhenStartWaitInterrupted() throws Exception {
        coordinator = newCoordinator(5000);
        coordinator.beginRecovery();

        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        Future<Object> apiStart = executor.submit(() -> {
            waiterThread.set(Thread.currentThread());
            try {
                return coordinator.runStart(() -> null);
            } catch (Throwable ex) {
                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                throw ex;
            }
        });
        Thread.sleep(50);
        waiterThread.get().interrupt();

        assertThatThrownBy(apiStart::get)
            .hasCauseInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("cause.code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));
        assertThat(interruptFlagPreserved)
            .as("the interrupted waiter must keep its interrupt flag")
            .isTrue();

        coordinator.completeRecovery();
    }

    @Test
    void shouldRejectNestedBeginAndLeaveCleanStateAfterComplete() {
        coordinator = newCoordinator(5000);
        coordinator.beginRecovery();
        assertThatThrownBy(coordinator::beginRecovery)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already in progress");

        // While the barrier is active an API start is held back (bounded), then after
        // completeRecovery the same start runs immediately.
        coordinator.completeRecovery();
        assertThat(coordinator.isRecoveryInProgress()).isFalse();
        assertThat(coordinator.runStart(() -> true)).isTrue();
    }

    @Test
    void shouldReleaseBarrierIdempotently() {
        coordinator = newCoordinator(5000);
        // Complete without begin, and complete twice: both are harmless no-ops.
        coordinator.completeRecovery();
        coordinator.beginRecovery();
        assertThat(coordinator.isRecoveryInProgress()).isTrue();
        coordinator.completeRecovery();
        coordinator.completeRecovery();
        assertThat(coordinator.isRecoveryInProgress()).isFalse();
        assertThat(coordinator.runStart(() -> true)).isTrue();
    }

    @Test
    void shouldRunRecoveryStartsInsideSweepWithoutBarrier() {
        coordinator = newCoordinator(5000);
        AtomicInteger started = new AtomicInteger();
        coordinator.beginRecovery();
        coordinator.runRecoveryStart(started::incrementAndGet);
        coordinator.runRecoveryStart(started::incrementAndGet);
        assertThat(started.get()).isEqualTo(2);
        coordinator.completeRecovery();
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }
}
