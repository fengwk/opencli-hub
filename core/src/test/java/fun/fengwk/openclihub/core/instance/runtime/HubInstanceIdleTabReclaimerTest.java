package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Orchestration tests for {@link HubInstanceIdleTabReclaimer}: it must attempt every listed
 * instance, isolate failures, and wire its fixed-period scheduler through the lifecycle hooks.
 * The per-instance reclaim semantics themselves are covered by
 * {@code HubInstanceLifecycleServiceTest} and {@code HubInstanceDispatcherIdleReclaimTest}.
 */
class HubInstanceIdleTabReclaimerTest {

    @Test
    void shouldSweepEveryInstanceAndIsolateFailures() {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycle = mock(HubInstanceLifecycleService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        HubInstance first = instance("id-1");
        HubInstance second = instance("id-2");
        when(instanceService.list()).thenReturn(List.of(first, second));
        when(lifecycle.reclaimIdleTabs("id-1")).thenThrow(new IllegalStateException("daemon down"));

        HubInstanceIdleTabReclaimer reclaimer =
            new HubInstanceIdleTabReclaimer(instanceService, lifecycle, scheduler);

        // The first instance fails; the sweep must not abort and must still visit the second.
        assertThatCode(reclaimer::sweep).doesNotThrowAnyException();
        verify(lifecycle).reclaimIdleTabs("id-1");
        verify(lifecycle).reclaimIdleTabs("id-2");
    }

    @Test
    void shouldSurviveInstanceListingFailure() {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycle = mock(HubInstanceLifecycleService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(instanceService.list()).thenThrow(new IllegalStateException("db down"));

        HubInstanceIdleTabReclaimer reclaimer =
            new HubInstanceIdleTabReclaimer(instanceService, lifecycle, scheduler);

        assertThatCode(reclaimer::sweep).doesNotThrowAnyException();
    }

    @Test
    void shouldScheduleFixedPeriodSweepAndStopOnDestroy() {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycle = mock(HubInstanceLifecycleService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        HubInstanceIdleTabReclaimer reclaimer =
            new HubInstanceIdleTabReclaimer(instanceService, lifecycle, scheduler);

        reclaimer.afterPropertiesSet();
        verify(scheduler).scheduleWithFixedDelay(
            any(Runnable.class),
            eq(HubInstanceIdleTabReclaimer.SWEEP_INTERVAL_MILLIS),
            eq(HubInstanceIdleTabReclaimer.SWEEP_INTERVAL_MILLIS),
            eq(TimeUnit.MILLISECONDS));

        reclaimer.destroy();
        verify(scheduler).shutdownNow();
    }

    private static HubInstance instance(String id) {
        HubInstance instance = new HubInstance();
        instance.setId(id);
        return instance;
    }

}
