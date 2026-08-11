package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Allocates X displays and loopback VNC TCP ports to individual Instances.
 *
 * <p>Allocation is reservation-driven:
 * <ul>
 *   <li>An in-memory {@link Set} tracks displays and VNC ports already allocated in this
 *       process so two concurrent {@link #allocate()} calls cannot return the same number
 *       even before any X server / x11vnc has actually bound the resources. This is the
 *       primary uniqueness guarantee during a clean start.</li>
 *   <li>After incrementing the counter, the allocator also probes the OS for existing
 *       {@code /tmp/.X{n}-lock} / {@code /tmp/.X11-unix/X{n}} and TCP loopback sockets.
 *       This catches resources still held by a previous Hub process so we don't collide with
 *       leftover Xvfb / x11vnc.</li>
 *   <li>{@link #release(Allocation)} returns the handles to the in-memory set so the next
 *       allocation may reuse them. The OS-side cleanup is the responsibility of the launcher
 *       shutting Xvfb / x11vnc down.</li>
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
public class HubInstanceAllocationService {

    private final OpenCliHubProperties.Runtime runtimeProps;
    private final Set<Integer> reservedDisplays = ConcurrentHashMap.newKeySet();
    private final Set<Integer> reservedVncPorts = ConcurrentHashMap.newKeySet();

    public HubInstanceAllocationService(OpenCliHubProperties properties) {
        this.runtimeProps = properties == null || properties.getRuntime() == null
            ? new OpenCliHubProperties.Runtime()
            : properties.getRuntime();
    }

    /**
     * Result of allocating one display and one VNC port for a runtime.
     */
    public static final class Allocation {

        public final int displayNumber;
        public final int vncPort;

        Allocation(int displayNumber, int vncPort) {
            this.displayNumber = displayNumber;
            this.vncPort = vncPort;
        }

    }

    /**
     * Walks both allocators and returns a unique (display, vnc) pair. Reserves them in
     * memory so concurrent callers cannot receive the same numbers until
     * {@link #release(Allocation)} is invoked.
     */
    public Allocation allocate() {
        int display = nextDisplayNumber();
        int port;
        try {
            port = nextVncPort();
        } catch (RuntimeException portEx) {
            reservedDisplays.remove(display);
            throw portEx;
        }
        log.debug("allocated display={} vnc={}", display, port);
        return new Allocation(display, port);
    }

    /**
     * Returns the runtime's display/port handles to the in-memory reservation set. Safe to
     * call multiple times for the same allocation; subsequent calls are no-ops.
     */
    public void release(Allocation allocation) {
        if (allocation == null) {
            return;
        }
        reservedDisplays.remove(allocation.displayNumber);
        reservedVncPorts.remove(allocation.vncPort);
    }

    private int nextDisplayNumber() {
        int base = Math.max(0, runtimeProps.getDisplayBase());
        int upper = base + 1024;
        int candidate = base;
        while (candidate < upper) {
            if (!reservedDisplays.contains(candidate) && isDisplayFree(candidate)) {
                if (reservedDisplays.add(candidate)) {
                    return candidate;
                }
                // Race lost; try the next number.
            }
            candidate++;
        }
        throw new IllegalStateException(
            "No free X display in range [" + base + "," + upper + "]");
    }

    private static boolean isDisplayFree(int displayNumber) {
        Path lockFile = Path.of("/tmp/.X" + displayNumber + "-lock");
        if (Files.exists(lockFile)) {
            return false;
        }
        Path socketFile = Path.of("/tmp/.X11-unix/X" + displayNumber);
        return !Files.exists(socketFile);
    }

    private int nextVncPort() {
        int base = Math.max(1, runtimeProps.getVncPortBase());
        int max = Math.max(base, runtimeProps.getVncPortMax());
        int candidate = base;
        while (candidate <= max) {
            if (!reservedVncPorts.contains(candidate) && isTcpFree("127.0.0.1", candidate)) {
                if (reservedVncPorts.add(candidate)) {
                    return candidate;
                }
            }
            candidate++;
        }
        throw new IllegalStateException(
            "No free VNC port in range [" + base + "," + max + "]");
    }

    private static boolean isTcpFree(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 50);
            return false;
        } catch (IOException ex) {
            return true;
        }
    }

}
