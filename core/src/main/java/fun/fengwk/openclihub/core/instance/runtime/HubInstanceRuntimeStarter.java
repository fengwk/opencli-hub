package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime.HubInstanceProcessKind;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.proxy.HubProxyValidator;
import fun.fengwk.openclihub.core.proxy.HubProxyValidator.ProxyConfiguration;
import fun.fengwk.openclihub.core.settings.service.HubSystemSettingsService;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Starts the 4-process runtime of one Hub Instance: allocates display/VNC, bootstraps the
 * Chrome profile, launches Xvfb / openbox / x11vnc / Chrome, and waits for each readiness
 * signal. Also owns process-alive checks and the allocation rollback for runtimes that were
 * never registered with the registry.
 *
 * @author fengwk
 */
@Component
class HubInstanceRuntimeStarter {

    private final HubInstanceRuntimeRegistry registry;
    private final InstanceProcessLauncher launcher;
    private final HubInstanceFiles files;
    private final ProfileSingletonCleaner singletonCleaner;
    private final ChromeProfileFileAccessBootstrap fileAccessBootstrap;
    private final OpenCliHubProperties properties;
    private final HubSystemSettingsService settingsService;

    HubInstanceRuntimeStarter(
        HubInstanceRuntimeRegistry registry,
        InstanceProcessLauncher launcher,
        HubInstanceFiles files,
        ProfileSingletonCleaner singletonCleaner,
        ChromeProfileFileAccessBootstrap fileAccessBootstrap,
        OpenCliHubProperties properties,
        HubSystemSettingsService settingsService) {
        this.registry = registry;
        this.launcher = launcher;
        this.files = files;
        this.singletonCleaner = singletonCleaner;
        this.fileAccessBootstrap = fileAccessBootstrap;
        this.properties = properties;
        this.settingsService = settingsService;
    }

    /**
     * Launches the full process tree for {@code descriptor} and returns the runtime. On any
     * failure every already-started process is stopped and the display/VNC allocation is
     * released, so the caller never observes a half-started runtime and the allocation is
     * freed exactly once.
     */
    HubInstanceRuntime start(HubInstance descriptor) {
        String id = descriptor.getId();
        HubInstanceAllocationService.Allocation allocation = registry.allocationService().allocate();
        HubInstanceRuntime runtime = new HubInstanceRuntime();
        runtime.setInstanceId(id);
        runtime.setInstanceCode(descriptor.getCode());
        runtime.setDisplayNumber(allocation.displayNumber);
        runtime.setVncPort(allocation.vncPort);
        try {
            HubInstanceFiles.InstanceDirectories directories = files.ensureDirectories(id);
            runtime.setInstanceDir(directories.instanceDir().toString());
            resetLog(directories.xvfbLog());
            resetLog(directories.openboxLog());
            resetLog(directories.x11vncLog());
            resetLog(directories.chromeLog());
            singletonCleaner.cleanStaleSingletons(directories.chromeDir());
            fileAccessBootstrap.bootstrap(directories.chromeDir());

            Map<String, String> displayEnv = Map.of("DISPLAY", ":" + allocation.displayNumber);
            InstanceProcessLauncher.LaunchedProcess xvfb = launcher.launchXvfb(
                allocation.displayNumber, directories.xvfbLog());
            recordHandle(runtime, HubInstanceProcessKind.XVFB, xvfb);
            waitForXvfbReady(allocation.displayNumber, xvfb.process);

            InstanceProcessLauncher.LaunchedProcess openbox = launcher.launchOpenbox(
                allocation.displayNumber, directories.openboxLog());
            recordHandle(runtime, HubInstanceProcessKind.OPENBOX, openbox);
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
            ensureProcessesAlive(runtime);

            InstanceProcessLauncher.LaunchedProcess x11vnc = launcher.launchX11vnc(
                allocation.displayNumber, allocation.vncPort, directories.x11vncLog());
            recordHandle(runtime, HubInstanceProcessKind.X11VNC, x11vnc);
            waitForVncReady(allocation.vncPort, x11vnc.process);

            InstanceProcessLauncher.LaunchedProcess chrome = launcher.launchChrome(
                chromeArgs(directories.chromeDir(), descriptor), displayEnv, directories.chromeLog());
            recordHandle(runtime, HubInstanceProcessKind.CHROME, chrome);
            runtime.setStartedAtMillis(System.currentTimeMillis());
            return runtime;
        } catch (RuntimeException ex) {
            registry.stopProcesses(runtime);
            registry.allocationService().release(allocation);
            throw ex;
        } catch (IOException ex) {
            registry.stopProcesses(runtime);
            registry.allocationService().release(allocation);
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "create instance directories failed: " + ex.getMessage());
        }
    }

    /**
     * Throws {@code INSTANCE_START_FAILED} when any tracked process of the runtime has
     * exited. Called during startup and by the context wait so a dying process is never
     * silently accepted.
     */
    void ensureProcessesAlive(HubInstanceRuntime runtime) {
        for (Map.Entry<HubInstanceProcessKind, ProcessHandle> entry
            : runtime.getProcesses().entrySet()) {
            if (!entry.getValue().isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    entry.getKey() + " process exited during instance startup");
            }
        }
    }

    /**
     * Releases the display/VNC allocation of a runtime that was never registered (start
     * rollback), mirroring what {@link HubInstanceRuntimeRegistry#unregister(String)} does
     * for registered runtimes.
     */
    void releaseAllocation(HubInstanceRuntime runtime) {
        if (runtime == null) {
            return;
        }
        registry.allocationService().release(new HubInstanceAllocationService.Allocation(
            runtime.getDisplayNumber(), runtime.getVncPort()));
    }

    private List<String> chromeArgs(Path chromeDir, HubInstance instance) {
        ProxyConfiguration proxy = resolveProxy(instance);
        List<String> args = new ArrayList<>();
        args.add("--user-data-dir=" + chromeDir.toString());
        args.add("--enable-unsafe-extension-debugging");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-sync");
        args.add("--disable-popup-blocking");
        args.add("--disable-gpu");
        args.add("--window-size=" + properties.getBrowser().getScreenWidth()
            + "," + properties.getBrowser().getScreenHeight());
        if (proxy.proxyMode() == HubProxyMode.CUSTOM) {
            args.add("--proxy-server=" + proxy.proxyServer());
            args.add("--proxy-bypass-list=localhost;127.0.0.1;[::1]");
        } else {
            args.add("--no-proxy-server");
        }
        // NOTE: deliberately NOT passing --load-extension, --disable-extensions-except,
        // --disable-features=DisableLoadExtensionCommandLineSwitch (rejected by Chrome 150),
        // --disable-background-networking or --disable-component-update (would suppress the
        // managed extension install), --disable-software-rasterizer (software rendering is
        // required on servers without a GPU). Extension is force-installed via managed policy.
        return args;
    }

    private ProxyConfiguration resolveProxy(HubInstance instance) {
        ProxyConfiguration configured = HubProxyValidator.normalizeInstance(
            instance.getProxyMode(), instance.getProxyServer());
        if (configured.proxyMode() != HubProxyMode.INHERIT) {
            return configured;
        }
        HubSystemSettings global = settingsService.get();
        return HubProxyValidator.normalizeGlobal(global.getProxyMode(), global.getProxyServer());
    }

    private void waitForXvfbReady(int displayNumber, ProcessHandle handle) {
        long deadline = System.currentTimeMillis() + properties.getVnc().getStartupTimeoutMillis();
        Path lock = Path.of("/tmp/.X" + displayNumber + "-lock");
        Path sock = Path.of("/tmp/.X11-unix/X" + displayNumber);
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    "Xvfb exited before becoming ready (display=" + displayNumber + ")");
            }
            if (Files.exists(lock) || Files.exists(sock)) {
                return;
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
            "Xvfb did not become ready within " + properties.getVnc().getStartupTimeoutMillis()
                + " ms (display=" + displayNumber + ")");
    }

    private void waitForVncReady(int port, ProcessHandle handle) {
        long deadline = System.currentTimeMillis() + properties.getVnc().getStartupTimeoutMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    "x11vnc exited before bind (port=" + port + ")");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException ignored) {
                // not yet bound
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
            "x11vnc did not bind 127.0.0.1:" + port + " within "
                + properties.getVnc().getStartupTimeoutMillis() + " ms");
    }

    private static void recordHandle(HubInstanceRuntime runtime,
        HubInstanceProcessKind kind, InstanceProcessLauncher.LaunchedProcess process) {
        runtime.getProcesses().put(kind, process.process);
    }

    private static void resetLog(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "instance startup interrupted");
        }
    }

}
