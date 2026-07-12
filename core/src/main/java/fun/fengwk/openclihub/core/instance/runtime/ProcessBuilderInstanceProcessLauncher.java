package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link InstanceProcessLauncher} backed by {@link ProcessBuilder}.
 *
 * <p>Verified launch flags come from the F1 PoC, not from earlier design prose:
 * <ul>
 *   <li>NO {@code --load-extension} / {@code --disable-extensions-except} /
 *       {@code --disable-features=DisableLoadExtensionCommandLineSwitch}. These are rejected
 *       by Google Chrome 150 stable ("is not allowed in Google Chrome, ignoring").</li>
 *   <li>NO {@code --disable-background-networking} / {@code --disable-component-update}.
 *       Both suppress the first managed extension install and would silently break connection
 *       to the daemon.</li>
 *   <li>NO {@code --no-sandbox}. Sandbox diagnostics are handled by the container entrypoint;
 *       production relaxes seccomp at the Docker level when required.</li>
 *   <li>The extension is force-installed through {@code /etc/opt/chrome/policies/managed}; the
 *       fixed ID is {@code lieajjjjjggpnhebbjmmlfofjojallpe} (PEM public key pinned in the
 *       image).</li>
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
public class ProcessBuilderInstanceProcessLauncher implements InstanceProcessLauncher {

    private final OpenCliHubProperties.Browser browserProps;
    private final OpenCliHubProperties.Runtime runtimeProps;

    public ProcessBuilderInstanceProcessLauncher(OpenCliHubProperties properties) {
        this.browserProps = properties == null || properties.getBrowser() == null
            ? new OpenCliHubProperties.Browser()
            : properties.getBrowser();
        this.runtimeProps = properties == null || properties.getRuntime() == null
            ? new OpenCliHubProperties.Runtime()
            : properties.getRuntime();
    }

    @Override
    public LaunchedProcess launchXvfb(int displayNumber, Path logPath) {
        List<String> argv = List.of(
            "Xvfb",
            ":" + displayNumber,
            "-screen", "0",
            screenGeometry(),
            "-ac",
            "-nolisten", "tcp");
        return launch(argv, Map.of(), logPath, "xvfb");
    }

    @Override
    public LaunchedProcess launchOpenbox(int displayNumber, Path logPath) {
        // openbox is launched with the same DISPLAY env as Chrome so the window manager and
        // the browser share the X screen.
        List<String> argv = List.of("openbox");
        return launch(argv, Map.of("DISPLAY", ":" + displayNumber), logPath, "openbox");
    }

    @Override
    public LaunchedProcess launchX11vnc(int displayNumber, int port, Path logPath) {
        List<String> argv = List.of(
            "x11vnc",
            "-display", ":" + displayNumber,
            "-listen", "127.0.0.1",
            "-rfbport", Integer.toString(port),
            "-localhost",
            "-nopw",
            "-shared",
            "-forever",
            "-noxdamage");
        return launch(argv, Map.of(), logPath, "x11vnc");
    }

    @Override
    public LaunchedProcess launchChrome(List<String> extraArgs, Map<String, String> env,
        Path logPath) {
        List<String> argv = new ArrayList<>();
        argv.add(browserProps.getBinary());
        argv.addAll(extraArgs);
        return launch(argv, env, logPath, "chrome");
    }

    @Override
    public void stop(ProcessHandle handle) {
        if (handle == null) {
            return;
        }
        List<ProcessHandle> descendants = handle.descendants().toList();
        if (handle.isAlive()) {
            log.info("stopping process pid={}", safePid(handle));
            handle.destroy();
        }
        try {
            long deadline = System.currentTimeMillis() + runtimeProps.getProcessStopGraceMillis();
            while (hasAliveProcess(handle, descendants) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            destroyTreeAndForcibly(handle, descendants);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyTreeAndForcibly(handle, descendants);
        }
    }

    private static boolean hasAliveProcess(ProcessHandle handle, List<ProcessHandle> descendants) {
        return handle.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    private static void destroyTreeAndForcibly(ProcessHandle handle,
        List<ProcessHandle> descendants) {
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (handle.isAlive()) {
            handle.destroyForcibly();
        }
    }

    private LaunchedProcess launch(List<String> argv, Map<String, String> env, Path logPath,
        String name) {
        try {
            Files.createDirectories(logPath.getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare log dir for " + logPath, ex);
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        builder.redirectErrorStream(true);
        try (OutputStream sink = Files.newOutputStream(logPath,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            sink.write(("[hub] starting " + name + ": " + String.join(" ", argv) + "\n").getBytes());
            sink.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot open log file " + logPath, ex);
        }
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to launch " + name + ": " + ex.getMessage(), ex);
        }
        ProcessHandle handle = process.toHandle();
        if (handle == null) {
            throw new IllegalStateException("ProcessHandle is null for " + name);
        }
        return new LaunchedProcess(handle, logPath);
    }

    private String screenGeometry() {
        int w = Math.max(1, browserProps.getScreenWidth());
        int h = Math.max(1, browserProps.getScreenHeight());
        int d = Math.max(8, browserProps.getScreenDepth());
        return w + "x" + h + "x" + d;
    }

    private static long safePid(ProcessHandle handle) {
        try {
            return handle.pid();
        } catch (Exception ex) {
            return -1L;
        }
    }

}
