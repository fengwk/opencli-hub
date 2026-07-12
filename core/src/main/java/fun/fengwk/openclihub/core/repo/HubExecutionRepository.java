package fun.fengwk.openclihub.core.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.model.HubExecution;

/**
 * @author fengwk
 */
public interface HubExecutionRepository {

    void init();

    long generateId();

    boolean add(HubExecution execution);

    boolean update(HubExecution execution);

    HubExecution findById(long id);

    Page<HubExecution> page(PageQuery pageQuery);

}
