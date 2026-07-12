package fun.fengwk.openclihub.core.execution.service;

import java.util.concurrent.TimeUnit;

/**
 * Monotonic deadline shared by queue wait and process execution.
 *
 * @author fengwk
 */
public final class HubExecutionDeadline {

    private final long deadlineNanos;

    private HubExecutionDeadline(long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive: " + timeoutMillis);
        }
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    public static HubExecutionDeadline fromNow(long timeoutMillis) {
        return new HubExecutionDeadline(timeoutMillis);
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long remainingMillis() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return Math.max(1L, millis);
    }

}
