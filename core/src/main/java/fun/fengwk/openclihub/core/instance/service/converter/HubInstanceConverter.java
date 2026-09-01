package fun.fengwk.openclihub.core.instance.service.converter;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceRuntimeDTO;
import org.springframework.stereotype.Component;

/**
 * Stateless converter from the {@link HubInstance} domain aggregate to the wire
 * {@link HubInstanceDTO} exposed to web callers, optionally merging a runtime snapshot.
 *
 * <p>Contract notes:
 * <ul>
 *   <li>Always emits a {@link HubInstanceRuntimeDTO}, even when {@code snapshot} is null,
 *       so the front-end has a stable shape.</li>
 *   <li>When {@code snapshot} is null the runtime is reported as {@code registered=false}
 *       with zero active/pending counters; counters never fabricate a positive load.</li>
 *   <li>Websites are returned as the immutable list owned by the domain object.</li>
 * </ul>
 *
 * @author fengwk
 */
@Component
public class HubInstanceConverter {

    /**
     * Converts a domain instance into a DTO. Runtime fields default to "absent".
     */
    public HubInstanceDTO toDTO(HubInstance instance) {
        return toDTO(instance, null);
    }

    /**
     * Converts a domain instance plus an optional runtime snapshot into a DTO.
     */
    public HubInstanceDTO toDTO(HubInstance instance, HubInstanceRuntimeSnapshot snapshot) {
        if (instance == null) {
            return null;
        }
        HubInstanceDTO dto = new HubInstanceDTO();
        dto.setId(instance.getId());
        dto.setCode(instance.getCode());
        dto.setDisplayName(instance.getDisplayName());
        dto.setContextId(instance.getContextId());
        dto.setState(instance.getState());
        dto.setWebsites(instance.getWebsites());
        dto.setMaxPending(instance.getMaxPending());
        dto.setMaxConcurrency(instance.getMaxConcurrency());
        dto.setPriority(instance.getPriority());
        dto.setProxyMode(instance.getProxyMode());
        dto.setProxyServer(instance.getProxyServer());
        dto.setLastErrorMessage(instance.getLastErrorMessage());
        dto.setStateChangedAt(instance.getStateChangedAt());
        dto.setCreateTime(instance.getCreateTime());
        dto.setUpdateTime(instance.getUpdateTime());
        dto.setRuntime(toRuntimeDTO(snapshot));
        return dto;
    }

    private HubInstanceRuntimeDTO toRuntimeDTO(HubInstanceRuntimeSnapshot snapshot) {
        HubInstanceRuntimeDTO target = new HubInstanceRuntimeDTO();
        if (snapshot == null) {
            target.setRegistered(false);
            target.setActiveCount(0);
            target.setPendingCount(0);
            target.setDisplayNumber(null);
            target.setVncPort(null);
            return target;
        }
        target.setRegistered(snapshot.isRegistered());
        target.setDisplayNumber(snapshot.getDisplayNumber());
        target.setVncPort(snapshot.getVncPort());
        target.setActiveCount(snapshot.getActiveCount());
        target.setPendingCount(snapshot.getPendingCount());
        return target;
    }

}
