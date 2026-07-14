package fun.fengwk.openclihub.core.instance.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-free test double that records watcher registration and cancellation.
 *
 * @author fengwk
 */
public class FakeUnexpectedExitListener implements UnexpectedExitListener {

    private final List<String> watched = new ArrayList<>();
    private final List<String> unwatched = new ArrayList<>();

    @Override
    public void watch(String instanceId, HubInstanceRuntime runtime) {
        watched.add(instanceId);
    }

    @Override
    public void unwatch(String instanceId) {
        unwatched.add(instanceId);
    }

    public int watchedCount() {
        return watched.size();
    }

    public int unwatchedCount() {
        return unwatched.size();
    }

}
