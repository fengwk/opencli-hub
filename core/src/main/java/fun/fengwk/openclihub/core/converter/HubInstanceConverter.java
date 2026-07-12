package fun.fengwk.openclihub.core.converter;

import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.share.model.HubCommandSummaryDTO;
import fun.fengwk.openclihub.share.model.HubInstanceDTO;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * @author fengwk
 */
@Component
public class HubInstanceConverter {

    public HubInstanceDTO convert(HubInstance instance, HubInstanceRuntimeSnapshot snapshot) {
        if (instance == null) {
            return null;
        }
        HubInstanceRuntimeSnapshot runtimeSnapshot = snapshot == null
            ? HubInstanceRuntimeSnapshot.idle()
            : snapshot;
        HubInstanceDTO dto = new HubInstanceDTO();
        dto.setId(instance.getId());
        dto.setCode(instance.getCode());
        dto.setDisplayName(instance.getDisplayName());
        dto.setOpencliProfile(instance.getOpencliProfile());
        dto.setContextId(instance.getContextId());
        dto.setVncEndpoint(instance.getVncEndpoint());
        dto.setState(instance.getState());
        dto.setMaxPending(instance.getMaxPending());
        dto.setSupportedCommands(instance.getSupportedCommands());
        dto.setActiveCount(runtimeSnapshot.getActiveCount());
        dto.setPendingCount(runtimeSnapshot.getPendingCount());
        dto.setLoad(runtimeSnapshot.getLoad());
        dto.setCreateTime(instance.getCreateTime());
        dto.setUpdateTime(instance.getUpdateTime());
        return dto;
    }

    public HubCommandSummaryDTO convertCommandSummary(
        String commandKey,
        List<String> instanceCodes,
        int onlineInstances) {
        HubCommandSummaryDTO dto = new HubCommandSummaryDTO();
        dto.setCommandKey(commandKey);
        dto.setInstanceCodes(instanceCodes);
        dto.setTotalInstances(instanceCodes.size());
        dto.setOnlineInstances(onlineInstances);
        return dto;
    }

}
