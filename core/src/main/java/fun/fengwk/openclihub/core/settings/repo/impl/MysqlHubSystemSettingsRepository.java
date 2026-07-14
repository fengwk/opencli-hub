package fun.fengwk.openclihub.core.settings.repo.impl;

import fun.fengwk.openclihub.core.settings.repo.HubSystemSettingsRepository;
import fun.fengwk.openclihub.core.settings.repo.impl.mapper.HubSystemSettingsMapper;
import fun.fengwk.openclihub.core.settings.repo.impl.model.HubSystemSettingsDO;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MySQL/H2 repository for the id=1 system settings singleton.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@Repository
public class MysqlHubSystemSettingsRepository implements HubSystemSettingsRepository {

    private final HubSystemSettingsMapper mapper;

    @Override
    public HubSystemSettings find() {
        return fromDO(mapper.find());
    }

    @Override
    public boolean add(HubSystemSettings settings) {
        return settings != null && mapper.insert(toDO(settings)) == 1;
    }

    @Override
    public boolean update(HubSystemSettings settings, long expectedVersion) {
        return settings != null && mapper.update(toDO(settings), expectedVersion) == 1;
    }

    private static HubSystemSettingsDO toDO(HubSystemSettings settings) {
        LocalDateTime now = LocalDateTime.now();
        HubSystemSettingsDO target = new HubSystemSettingsDO();
        target.setId(1);
        target.setProxyMode(settings.getProxyMode() == null ? null : settings.getProxyMode().name());
        target.setProxyServer(settings.getProxyServer());
        target.setCreateTime(settings.getCreateTime() == null ? now : settings.getCreateTime());
        target.setModifiedTime(now);
        target.setVersion(settings.getVersion());
        return target;
    }

    private static HubSystemSettings fromDO(HubSystemSettingsDO source) {
        if (source == null) {
            return null;
        }
        HubSystemSettings target = new HubSystemSettings();
        target.setId(source.getId() == null ? 1 : source.getId());
        target.setProxyMode(source.getProxyMode() == null
            ? HubProxyMode.DIRECT : HubProxyMode.valueOf(source.getProxyMode()));
        target.setProxyServer(source.getProxyServer());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getModifiedTime());
        target.setVersion(source.getVersion() == null ? 0L : source.getVersion());
        return target;
    }

}
