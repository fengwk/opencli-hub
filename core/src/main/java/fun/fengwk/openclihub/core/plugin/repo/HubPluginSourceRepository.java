package fun.fengwk.openclihub.core.plugin.repo;

import fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource;
import java.util.List;

/**
 * Persistence port for configured OpenCLI plugin sources.
 *
 * @author fengwk
 */
public interface HubPluginSourceRepository {

    List<HubPluginSource> listAll();

    HubPluginSource findById(String id);

    HubPluginSource findByName(String name);

    boolean add(HubPluginSource source);

    boolean update(HubPluginSource source);

    boolean deleteById(String id);

}
