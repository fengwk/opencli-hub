package fun.fengwk.openclihub.infra.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.common.idgen.NamespaceIdGenerator;
import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.repo.HubInstanceRepository;
import fun.fengwk.openclihub.infra.mapper.HubInstanceMapper;
import fun.fengwk.openclihub.infra.model.HubInstanceDO;
import fun.fengwk.openclihub.share.model.HubInstanceState;
import java.io.IOException;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubInstanceRepository implements HubInstanceRepository {

    private final NamespaceIdGenerator<Long> idGenerator;
    private final HubInstanceMapper hubInstanceMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void init() {
        hubInstanceMapper.createTableIfNotExists();
    }

    @Override
    public long generateId() {
        return idGenerator.next(getClass());
    }

    @Override
    public boolean add(HubInstance instance) {
        return instance != null && hubInstanceMapper.insertSelective(convert(instance)) == 1;
    }

    @Override
    public boolean update(HubInstance instance) {
        return instance != null && hubInstanceMapper.updateSelectiveById(convert(instance)) == 1;
    }

    @Override
    public boolean deleteById(long id) {
        return hubInstanceMapper.deleteById(id) == 1;
    }

    @Override
    public HubInstance findById(long id) {
        return convert(hubInstanceMapper.selectById(id));
    }

    @Override
    public HubInstance findByCode(String code) {
        return convert(hubInstanceMapper.selectByCode(code));
    }

    @Override
    public List<HubInstance> listAll() {
        return hubInstanceMapper.listAll().stream().map(this::convert).toList();
    }

    private HubInstanceDO convert(HubInstance instance) {
        if (instance == null) {
            return null;
        }
        HubInstanceDO instanceDO = new HubInstanceDO();
        instanceDO.setId(instance.getId());
        instanceDO.setCode(instance.getCode());
        instanceDO.setDisplayName(instance.getDisplayName());
        instanceDO.setOpencliProfile(instance.getOpencliProfile());
        instanceDO.setContextId(instance.getContextId());
        instanceDO.setVncEndpoint(instance.getVncEndpoint());
        instanceDO.setState(instance.getState() == null ? null : instance.getState().name());
        instanceDO.setMaxPending(instance.getMaxPending());
        instanceDO.setSupportedCommandsJson(writeJson(instance.getSupportedCommands()));
        instanceDO.setCreateTime(instance.getCreateTime());
        instanceDO.setModifiedTime(instance.getUpdateTime());
        return instanceDO;
    }

    private HubInstance convert(HubInstanceDO instanceDO) {
        if (instanceDO == null) {
            return null;
        }
        HubInstance instance = new HubInstance();
        instance.setId(instanceDO.getId());
        instance.setCode(instanceDO.getCode());
        instance.setDisplayName(instanceDO.getDisplayName());
        instance.setOpencliProfile(instanceDO.getOpencliProfile());
        instance.setContextId(instanceDO.getContextId());
        instance.setVncEndpoint(instanceDO.getVncEndpoint());
        instance.setState(instanceDO.getState() == null ? null : HubInstanceState.valueOf(instanceDO.getState()));
        instance.setMaxPending(instanceDO.getMaxPending() == null ? 1 : instanceDO.getMaxPending());
        instance.setSupportedCommands(readStringList(instanceDO.getSupportedCommandsJson()));
        instance.setCreateTime(instanceDO.getCreateTime());
        instance.setUpdateTime(instanceDO.getModifiedTime());
        return instance;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Serialize json failed", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Deserialize json failed", ex);
        }
    }

}
