package fun.fengwk.openclihub.core.execution.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;

/**
 * Execution persistence boundary.
 *
 * @author fengwk
 */
public interface HubExecutionRepository {

    long generateId();

    boolean add(HubExecution execution);

    boolean update(HubExecution execution);

    HubExecution findById(long id);

    Page<HubExecution> page(PageQuery pageQuery, Long instanceId);

}
