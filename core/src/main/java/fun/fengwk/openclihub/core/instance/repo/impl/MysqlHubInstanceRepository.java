package fun.fengwk.openclihub.core.instance.repo.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.repo.impl.mapper.HubInstanceMapper;
import fun.fengwk.openclihub.core.instance.repo.impl.model.HubInstanceDO;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MySQL/H2 implementation backed by the generated hub_instance mapper.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubInstanceRepository implements HubInstanceRepository {

    private final HubInstanceMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public String generateId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean add(HubInstance instance) {
        return instance != null && mapper.insert(toDO(instance)) == 1;
    }

    @Override
    public boolean update(HubInstance instance) {
        return instance != null && mapper.updateById(toDO(instance)) == 1;
    }

    @Override
    public boolean deleteById(String id) {
        return mapper.deleteById(id) == 1;
    }

    @Override
    public HubInstance findById(String id) {
        return fromDO(mapper.findById(id));
    }

    @Override
    public HubInstance findByCode(String code) {
        return fromDO(mapper.findByCode(code));
    }

    @Override
    public HubInstance findByContextId(String contextId) {
        return contextId == null ? null : fromDO(mapper.findByContextId(contextId));
    }

    @Override
    public List<HubInstance> listAll() {
        return mapper.findAllOrderByCreateTimeAscIdAsc().stream().map(this::fromDO).toList();
    }

    private HubInstanceDO toDO(HubInstance instance) {
        LocalDateTime now = LocalDateTime.now();
        HubInstanceDO target = new HubInstanceDO();
        target.setId(instance.getId());
        target.setCode(instance.getCode());
        target.setDisplayName(instance.getDisplayName());
        target.setContextId(instance.getContextId());
        target.setState(instance.getState() == null ? null : instance.getState().name());
        target.setWebsitesJson(writeJson(instance.getWebsites()));
        target.setMaxPending(instance.getMaxPending());
        target.setLastErrorMessage(instance.getLastErrorMessage());
        target.setStateChangedAt(instance.getStateChangedAt());
        target.setCreateTime(instance.getCreateTime() == null ? now : instance.getCreateTime());
        target.setModifiedTime(instance.getUpdateTime() == null ? now : instance.getUpdateTime());
        target.setVersion(0L);
        return target;
    }

    private HubInstance fromDO(HubInstanceDO source) {
        if (source == null) {
            return null;
        }
        HubInstance target = new HubInstance();
        target.setId(source.getId());
        target.setCode(source.getCode());
        target.setDisplayName(source.getDisplayName());
        target.setContextId(source.getContextId());
        target.setState(source.getState() == null ? null : HubInstanceState.valueOf(source.getState()));
        target.setWebsites(readStringList(source.getWebsitesJson()));
        target.setMaxPending(source.getMaxPending() == null ? 0 : source.getMaxPending());
        target.setLastErrorMessage(source.getLastErrorMessage());
        target.setStateChangedAt(source.getStateChangedAt());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getModifiedTime());
        return target;
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize instance websites", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize instance websites", ex);
        }
    }

}
