package fun.fengwk.openclihub.core.instance.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-free test double that records watcher registration and cancellation.
 *
 * @author fengwk
 */
public class FakeUnexpectedExitListener implements UnexpectedExitListener {

    private final List<long[]> watched = new ArrayList<>();
    private final List<long[]> unwatched = new ArrayList<>();

    @Override
    public void watch(long instanceId, HubInstanceRuntime runtime) {
        watched.add(new long[] { instanceId, runtime == null ? -1 : 1 });
    }

    @Override
    public void unwatch(long instanceId) {
        unwatched.add(new long[] { instanceId });
    }

    public int watchedCount() {
        return watched.size();
    }

    public int unwatchedCount() {
        return unwatched.size();
    }

}
