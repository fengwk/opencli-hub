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
 *   <li>the recovery sweep is a barrier: API starts wait, bounded by the configured timeout,
 *       then either proceed or fail with a precise error code;</li>
 *   <li>waits are interruptible and preserve the interrupt flag;</li>
 *   <li>nested recovery is rejected and the barrier state is always cleaned up.</li>
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

    private static HubInstanceStartCoordinator newCoordinator(long barrierTimeoutMillis) {
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getRuntime().setRecoveryBarrierTimeoutMillis(barrierTimeoutMillis);
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

    @Test
    void shouldWaitForRecoveryThenProceed() throws Exception {
        coordinator = newCoordinator(5000);
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        AtomicBoolean startRanAfterRecovery = new AtomicBoolean();

        Future<Void> recovery = executor.submit(() -> coordinator.runRecovery(() -> {
            recoveryEntered.countDown();
            if (!releaseRecovery.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return null;
        }));
        assertThat(recoveryEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> apiStart = executor.submit(() -> coordinator.runStart(() -> {
            startRanAfterRecovery.set(true);
            return true;
        }));

        // The API start must be held behind the recovery barrier.
        Thread.sleep(100);
        assertThat(apiStart.isDone()).as("API start must wait for the recovery sweep").isFalse();
        assertThat(startRanAfterRecovery).isFalse();

        releaseRecovery.countDown();
        assertThat(apiStart.get(2, TimeUnit.SECONDS)).isTrue();
        recovery.get(2, TimeUnit.SECONDS);
        assertThat(startRanAfterRecovery).isTrue();
    }

    @Test
    void shouldTimeoutWithRecoveryInProgressCodeWhileRecoveryHoldsLock() throws Exception {
        coordinator = newCoordinator(50);
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        Future<Void> recovery = executor.submit(() -> coordinator.runRecovery(() -> {
            recoveryEntered.countDown();
            if (!releaseRecovery.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return null;
        }));
        assertThat(recoveryEntered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> coordinator.runStart(() -> null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_RECOVERY_IN_PROGRESS));

        releaseRecovery.countDown();
        recovery.get(2, TimeUnit.SECONDS);
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
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        Future<Void> recovery = executor.submit(() -> coordinator.runRecovery(() -> {
            recoveryEntered.countDown();
            if (!releaseRecovery.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return null;
        }));
        assertThat(recoveryEntered.await(2, TimeUnit.SECONDS)).isTrue();

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

        releaseRecovery.countDown();
        recovery.get(2, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNestedRecoveryAndLeaveCleanStateAfterSweep() {
        coordinator = newCoordinator(5000);
        AtomicBoolean nestedRejected = new AtomicBoolean();
        coordinator.runRecovery(() -> {
            assertThatThrownBy(() -> coordinator.runRecovery(() -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
            nestedRejected.set(true);
            return null;
        });
        assertThat(nestedRejected).isTrue();

        // After the sweep the barrier is cleared: a normal API start runs immediately.
        assertThat(coordinator.runStart(() -> true)).isTrue();
    }

    @Test
    void shouldRunRecoveryStartsInsideSweepWithoutBarrier() {
        coordinator = newCoordinator(5000);
        AtomicInteger started = new AtomicInteger();
        coordinator.runRecovery(() -> {
            coordinator.runRecoveryStart(started::incrementAndGet);
            coordinator.runRecoveryStart(started::incrementAndGet);
            return null;
        });
        assertThat(started.get()).isEqualTo(2);
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }
}
