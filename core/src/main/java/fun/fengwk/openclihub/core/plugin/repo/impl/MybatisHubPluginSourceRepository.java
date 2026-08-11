package fun.fengwk.openclihub.core.plugin.repo.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.plugin.repo.HubPluginSourceRepository;
import fun.fengwk.openclihub.core.plugin.repo.impl.mapper.HubPluginSourceMapper;
import fun.fengwk.openclihub.core.plugin.repo.impl.model.HubPluginSourceDO;
import fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MyBatis repository shared by the PostgreSQL, MySQL and SQLite build variants.
 *
 * <p>Audit timestamps are owned by the service layer and are copied through verbatim.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@Repository
public class MybatisHubPluginSourceRepository implements HubPluginSourceRepository {

    private final HubPluginSourceMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<HubPluginSource> listAll() {
        List<HubPluginSource> result = new ArrayList<>();
        for (HubPluginSourceDO row : mapper.listAll()) {
            result.add(fromDO(row));
        }
        return result;
    }

    @Override
    public HubPluginSource findById(String id) {
        return id == null ? null : fromDO(mapper.findById(id));
    }

    @Override
    public HubPluginSource findByName(String name) {
        return name == null ? null : fromDO(mapper.findByName(name));
    }

    @Override
    public boolean add(HubPluginSource source) {
        return source != null && mapper.insert(toDO(source)) == 1;
    }

    @Override
    public boolean update(HubPluginSource source) {
        return source != null && mapper.updateById(toDO(source)) == 1;
    }

    @Override
    public boolean deleteById(String id) {
        return id != null && mapper.deleteById(id) == 1;
    }

    private HubPluginSourceDO toDO(HubPluginSource source) {
        HubPluginSourceDO target = new HubPluginSourceDO();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setSource(source.getSource());
        target.setDesiredPluginsJson(writeJson(source.getDesiredPlugins()));
        target.setEnabled(source.isEnabled());
        target.setLastStatus(source.getLastStatus() == null
            ? HubPluginSourceStatus.IDLE.name() : source.getLastStatus().name());
        target.setLastError(source.getLastError());
        target.setLastSyncedAt(source.getLastSyncedAt());
        target.setLastResultJson(source.getLastResult());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        target.setVersion(source.getVersion());
        return target;
    }

    private HubPluginSource fromDO(HubPluginSourceDO source) {
        if (source == null) {
            return null;
        }
        HubPluginSource target = new HubPluginSource();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setSource(source.getSource());
        target.setDesiredPlugins(readStringList(source.getDesiredPluginsJson()));
        target.setEnabled(Boolean.TRUE.equals(source.getEnabled()));
        target.setLastStatus(source.getLastStatus() == null
            ? HubPluginSourceStatus.IDLE : HubPluginSourceStatus.valueOf(source.getLastStatus()));
        target.setLastError(source.getLastError());
        target.setLastSyncedAt(source.getLastSyncedAt());
        target.setLastResult(source.getLastResultJson());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        target.setVersion(source.getVersion() == null ? 0L : source.getVersion());
        return target;
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize desired plugins", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize desired plugins", ex);
        }
    }

}
