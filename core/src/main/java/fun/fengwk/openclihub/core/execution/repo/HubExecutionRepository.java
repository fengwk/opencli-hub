package fun.fengwk.openclihub.core.execution.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import java.time.LocalDateTime;

/**
 * Execution persistence boundary.
 *
 * @author fengwk
 */
public interface HubExecutionRepository {

    String generateId();

    boolean add(HubExecution execution);

    boolean update(HubExecution execution);

    HubExecution findById(String id);

    Page<HubExecution> page(PageQuery pageQuery, String instanceId);

    /** CAS: PENDING -> RUNNING. */
    boolean markRunningIfPending(String id, LocalDateTime startedAt);

    /** CAS: PENDING -> CANCELLED. */
    boolean markCancelledIfPending(String id, String errorMessage, LocalDateTime finishedAt);

}
