package fun.fengwk.openclihub.core.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.share.model.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.HubExecutionRequestDTO;

/**
 * @author fengwk
 */
public interface HubExecutionService {

    HubExecutionDTO execute(HubExecutionRequestDTO requestDTO);

    HubExecutionDTO getExecution(long id);

    Page<HubExecutionDTO> pageExecutions(PageQuery pageQuery);

}
