package fun.fengwk.openclihub.core.repo;

import fun.fengwk.openclihub.core.model.HubInstance;
import java.util.List;

/**
 * @author fengwk
 */
public interface HubInstanceRepository {

    void init();

    long generateId();

    boolean add(HubInstance instance);

    boolean update(HubInstance instance);

    boolean deleteById(long id);

    HubInstance findById(long id);

    HubInstance findByCode(String code);

    List<HubInstance> listAll();

}
