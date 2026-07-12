package fun.fengwk.openclihub.core.service;

import fun.fengwk.openclihub.share.model.HubCommandSummaryDTO;
import fun.fengwk.openclihub.share.model.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.HubInstanceDTO;
import fun.fengwk.openclihub.share.model.HubInstanceState;
import fun.fengwk.openclihub.share.model.HubInstanceUpdateDTO;
import java.util.List;

/**
 * @author fengwk
 */
public interface HubInstanceService {

    HubInstanceDTO createInstance(HubInstanceCreateDTO createDTO);

    HubInstanceDTO updateInstance(long id, HubInstanceUpdateDTO updateDTO);

    void deleteInstance(long id);

    HubInstanceDTO getInstance(long id);

    List<HubInstanceDTO> listInstances();

    List<HubCommandSummaryDTO> listCommands();

    HubInstanceDTO updateState(long id, HubInstanceState state);

}
