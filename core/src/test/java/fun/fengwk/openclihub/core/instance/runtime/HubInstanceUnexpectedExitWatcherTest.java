package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime.HubInstanceProcessKind;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every critical child process, not only Chrome, participates in exit detection.
 */
class HubInstanceUnexpectedExitWatcherTest {

    @Test
    void shouldReportNonChromeProcessExit() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        HubInstanceUnexpectedExitWatcher watcher = new HubInstanceUnexpectedExitWatcher(
            (message, instanceId) -> {
                reason.set(message);
                delivered.countDown();
            },
            scheduler);
        try {
            HubInstanceRuntime runtime = new HubInstanceRuntime();
            runtime.setInstanceId("1001");
            FakeInstanceProcessLauncher.FakeHandle xvfb =
                new FakeInstanceProcessLauncher.FakeHandle(1L, "Xvfb", "Xvfb :8200");
            FakeInstanceProcessLauncher.FakeHandle chrome =
                new FakeInstanceProcessLauncher.FakeHandle(2L, "chrome", "chrome");
            runtime.getProcesses().put(HubInstanceProcessKind.XVFB, xvfb);
            runtime.getProcesses().put(HubInstanceProcessKind.CHROME, chrome);

            watcher.watch(runtime.getInstanceId(), runtime);
            xvfb.kill();

            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(reason.get()).contains("XVFB process exited");
        } finally {
            watcher.destroy();
        }
    }

}
