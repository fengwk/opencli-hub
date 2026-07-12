package fun.fengwk.openclihub.core.command.repo;

import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for managed output rules.
 *
 * @author fengwk
 */
public interface HubCommandOutputRuleRepository {

    long generateId();

    boolean add(HubCommandOutputRule rule);

    boolean update(HubCommandOutputRule rule);

    boolean deleteById(long id);

    boolean deleteByCommandKey(String commandKey);

    HubCommandOutputRule findById(long id);

    Optional<HubCommandOutputRule> findByCommandKey(String commandKey);

    List<HubCommandOutputRule> listAll();

}