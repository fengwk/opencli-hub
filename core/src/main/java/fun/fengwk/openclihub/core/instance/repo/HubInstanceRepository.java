package fun.fengwk.openclihub.core.instance.repo;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.List;

/**
 * Instance persistence boundary.
 *
 * @author fengwk
 */
public interface HubInstanceRepository {

    String generateId();

    boolean add(HubInstance instance);

    boolean update(HubInstance instance);

    boolean deleteById(String id);

    HubInstance findById(String id);

    HubInstance findByCode(String code);

    HubInstance findByContextId(String contextId);

    List<HubInstance> listAll();

}
