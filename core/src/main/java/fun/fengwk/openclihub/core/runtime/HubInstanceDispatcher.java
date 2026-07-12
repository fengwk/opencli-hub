package fun.fengwk.openclihub.core.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author fengwk
 */
public class HubInstanceDispatcher {

    private final int maxPending;
    private final ThreadPoolExecutor executor;

    public HubInstanceDispatcher(String instanceCode, int maxPending) {
        this.maxPending = maxPending;
        executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, maxPending)),
            new HubDispatcherThreadFactory(instanceCode),
            new ThreadPoolExecutor.AbortPolicy());
    }

    public int getMaxPending() {
        return maxPending;
    }

    public <T> T dispatch(Callable<T> callable) {
        FutureTask<T> futureTask = new FutureTask<>(callable);
        try {
            executor.execute(futureTask);
        } catch (RejectedExecutionException ex) {
            throw ex;
        }
        try {
            return futureTask.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dispatch interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Dispatch failed", cause);
        }
    }

    public HubInstanceRuntimeSnapshot snapshot() {
        return new HubInstanceRuntimeSnapshot(executor.getActiveCount(), executor.getQueue().size());
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static class HubDispatcherThreadFactory implements ThreadFactory {

        private final AtomicInteger idGenerator = new AtomicInteger(1);
        private final String instanceCode;

        private HubDispatcherThreadFactory(String instanceCode) {
            this.instanceCode = instanceCode == null ? "unknown" : instanceCode;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("opencli-hub-" + instanceCode + '-' + idGenerator.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }

    }

}
