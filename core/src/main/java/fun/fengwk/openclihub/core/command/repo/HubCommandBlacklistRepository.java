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

    long generateId();

    boolean add(HubCommandBlacklist blacklist);

    boolean update(HubCommandBlacklist blacklist);

    boolean deleteById(long id);

    boolean deleteByCommandKey(String commandKey);

    HubCommandBlacklist findById(long id);

    Optional<HubCommandBlacklist> findByCommandKey(String commandKey);

    List<HubCommandBlacklist> listAll();

}