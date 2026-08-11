package fun.fengwk.openclihub.core.instance.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Test double for {@link InstanceProcessLauncher}.
 *
 * <p>Records every launch in order, lets tests inject per-launch failures, and exposes a
 * deterministic {@link ProcessHandle} implementation that obeys {@code destroy}/descendant
 * semantics.
 *
 * @author fengwk
 */
public class FakeInstanceProcessLauncher implements InstanceProcessLauncher {

    private final Map<HubInstanceRuntime.HubInstanceProcessKind, List<FakeHandle>> launched
        = new ConcurrentHashMap<>();
    private final AtomicLong pidSeq = new AtomicLong(1);
    private final Map<String, Boolean> killOverrides = new ConcurrentHashMap<>();
    private volatile Consumer<List<String>> chromeLaunchHook;

    public FakeInstanceProcessLauncher() {
        for (HubInstanceRuntime.HubInstanceProcessKind k :
            HubInstanceRuntime.HubInstanceProcessKind.values()) {
            launched.put(k, new ArrayList<>());
        }
    }

    public List<FakeHandle> handlesOf(HubInstanceRuntime.HubInstanceProcessKind kind) {
        return List.copyOf(launched.get(kind));
    }

    public FakeHandle lastHandle(HubInstanceRuntime.HubInstanceProcessKind kind) {
        List<FakeHandle> l = launched.get(kind);
        if (l == null || l.isEmpty()) {
            return null;
        }
        return l.get(l.size() - 1);
    }

    public int launchCount(HubInstanceRuntime.HubInstanceProcessKind kind) {
        return launched.get(kind).size();
    }

    /** Test-only callback invoked with Chrome argv immediately before the fake Chrome launch. */
    public void setChromeLaunchHook(Consumer<List<String>> hook) {
        chromeLaunchHook = hook;
    }

    /**
     * Tells the next launch of {@code kind} to die immediately so the readiness check sees
     * an exited process and the lifecycle service triggers the start-failure path.
     */
    public void failNextLaunch(HubInstanceRuntime.HubInstanceProcessKind kind) {
        killOverrides.put(kind.name() + ":next", Boolean.TRUE);
    }

    public void killAll() {
        for (List<FakeHandle> l : launched.values()) {
            for (FakeHandle h : l) {
                h.kill();
            }
        }
    }

    @Override
    public LaunchedProcess launchXvfb(int displayNumber, Path logPath) {
        signalXvfbReady(displayNumber);
        return runLaunch(HubInstanceRuntime.HubInstanceProcessKind.XVFB, "Xvfb",
            List.of("Xvfb", ":" + displayNumber), logPath);
    }

    @Override
    public LaunchedProcess launchOpenbox(int displayNumber, Path logPath) {
        return runLaunch(HubInstanceRuntime.HubInstanceProcessKind.OPENBOX, "openbox",
            List.of("openbox", ":" + displayNumber), logPath);
    }

    @Override
    public LaunchedProcess launchX11vnc(int displayNumber, int port, Path logPath) {
        signalVncReady(port);
        return runLaunch(HubInstanceRuntime.HubInstanceProcessKind.X11VNC, "x11vnc",
            List.of("x11vnc", ":" + displayNumber, Integer.toString(port)), logPath);
    }

    @Override
    public LaunchedProcess launchChrome(List<String> extraArgs, Map<String, String> env,
        Path logPath) {
        List<String> args = new ArrayList<>(extraArgs);
        args.add(0, "google-chrome-stable");
        Consumer<List<String>> hook = chromeLaunchHook;
        if (hook != null) {
            hook.accept(List.copyOf(args));
        }
        return runLaunch(HubInstanceRuntime.HubInstanceProcessKind.CHROME, "chrome", args, logPath);
    }

    @Override
    public void stop(ProcessHandle handle) {
        if (handle instanceof FakeHandle fake && fake.alive) {
            fake.kill();
        }
    }

    private LaunchedProcess runLaunch(
        HubInstanceRuntime.HubInstanceProcessKind kind, String name,
        List<String> argv, Path logPath) {
        // Mirror the production launcher: ensure log parent exists and touch the log file
        // so the lifecycle service can assert on it.
        try {
            if (logPath != null) {
                if (logPath.getParent() != null) {
                    Files.createDirectories(logPath.getParent());
                }
                Files.writeString(logPath, "[fake] " + name + " started: " + String.join(" ", argv) + "\n");
            }
        } catch (IOException ignored) {
            // best-effort
        }
        FakeHandle handle = new FakeHandle(pidSeq.incrementAndGet(), name,
            String.join(" ", argv));
        launched.get(kind).add(handle);
        if (killOverrides.remove(kind.name() + ":next") != null) {
            // Simulate a process that died before readiness was checked.
            handle.kill();
        }
        return new LaunchedProcess(handle, logPath);
    }

    /**
     * Drops X11 lock files so {@link HubInstanceRuntimeStarter#waitForXvfbReady} returns
     * immediately. Mirrors the production Xvfb behaviour without needing a real server.
     */
    private void signalXvfbReady(int displayNumber) {
        try {
            Path lockFile = Path.of("/tmp/.X" + displayNumber + "-lock");
            Files.createDirectories(lockFile.getParent());
            if (!Files.exists(lockFile)) {
                Files.createFile(lockFile);
                x11FilesToCleanup.add(lockFile);
            }
            Path sockDir = Path.of("/tmp/.X11-unix");
            Files.createDirectories(sockDir);
            Path sockFile = sockDir.resolve("X" + displayNumber);
            if (!Files.exists(sockFile)) {
                Files.createFile(sockFile);
                x11FilesToCleanup.add(sockFile);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Binds a loopback ServerSocket on the requested port so {@code waitForVncReady} finds
     * the port open. We accept no real connections and immediately close on accept; the
     * lifecycle only does a connect-probe.
     */
    private void signalVncReady(int port) {
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", port));
            vncSockets.add(server);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private final List<Path> x11FilesToCleanup = new ArrayList<>();
    private final List<ServerSocket> vncSockets = new ArrayList<>();

    /** Test helper: removes X11 lock files and closes any VNC sockets we opened. */
    public void cleanupSignals() {
        for (Path p : x11FilesToCleanup) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
        x11FilesToCleanup.clear();
        for (ServerSocket s : vncSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        vncSockets.clear();
    }

    /**
     * In-memory {@link ProcessHandle} that obeys the small surface used by the runtime.
     */
    public static class FakeHandle implements ProcessHandle {

        private final long pid;
        private final String name;
        private final String command;
        private boolean alive = true;
        private final List<FakeHandle> descendants = new ArrayList<>();
        private final CompletableFuture<ProcessHandle> onExitFuture = new CompletableFuture<>();

        public FakeHandle(long pid, String name, String command) {
            this.pid = pid;
            this.name = name;
            this.command = command;
        }

        public void kill() {
            alive = false;
            for (FakeHandle d : descendants) {
                d.kill();
            }
            descendants.clear();
            if (!onExitFuture.isDone()) {
                onExitFuture.complete(this);
            }
        }

        public void addDescendant(FakeHandle descendant) {
            descendants.add(descendant);
        }

        public List<FakeHandle> directChildren() {
            return List.copyOf(descendants);
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Info info() {
            return new Info() {
                @Override public Optional<String> command() {
                    return Optional.of(name);
                }
                @Override public Optional<String> commandLine() {
                    return Optional.of(command);
                }
                @Override public Optional<String[]> arguments() {
                    return Optional.of(new String[0]);
                }
                @Override public Optional<java.time.Instant> startInstant() {
                    return Optional.of(java.time.Instant.now());
                }
                @Override public Optional<java.time.Duration> totalCpuDuration() {
                    return Optional.of(java.time.Duration.ZERO);
                }
                @Override public Optional<String> user() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public Stream<ProcessHandle> children() {
            return descendants.stream().map(d -> (ProcessHandle) d);
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            List<ProcessHandle> flat = new ArrayList<>();
            collectDescendants(flat, this);
            return flat.stream();
        }

        private static void collectDescendants(List<ProcessHandle> flat, FakeHandle node) {
            for (FakeHandle d : node.descendants) {
                flat.add(d);
                collectDescendants(flat, d);
            }
        }

        @Override
        public boolean destroy() {
            if (alive) {
                kill();
                return true;
            }
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            destroy();
            return true;
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return onExitFuture;
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        @Override
        public int compareTo(ProcessHandle o) {
            return Long.compare(pid, o.pid());
        }

    }

}
