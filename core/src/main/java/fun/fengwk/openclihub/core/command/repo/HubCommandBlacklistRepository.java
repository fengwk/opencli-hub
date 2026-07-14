package fun.fengwk.openclihub.core.command.repo;

import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the global command blacklist.
 *
 * @author fengwk
 */
public interface HubCommandBlacklistRepository {

    String generateId();

    boolean add(HubCommandBlacklist blacklist);

    boolean update(HubCommandBlacklist blacklist);

    boolean deleteById(String id);

    boolean deleteByCommandKey(String commandKey);

    HubCommandBlacklist findById(String id);

    Optional<HubCommandBlacklist> findByCommandKey(String commandKey);

    List<HubCommandBlacklist> listAll();

}
