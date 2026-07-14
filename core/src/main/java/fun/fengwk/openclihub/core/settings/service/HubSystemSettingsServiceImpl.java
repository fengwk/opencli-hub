package fun.fengwk.openclihub.core.settings.service;

import fun.fengwk.openclihub.core.proxy.HubProxyValidator;
import fun.fengwk.openclihub.core.proxy.HubProxyValidator.ProxyConfiguration;
import fun.fengwk.openclihub.core.settings.repo.HubSystemSettingsRepository;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Service for the persisted global proxy policy.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@Service
public class HubSystemSettingsServiceImpl implements HubSystemSettingsService {

    private final HubSystemSettingsRepository repository;

    @Override
    public HubSystemSettings get() {
        HubSystemSettings settings = repository.find();
        if (settings != null) {
            return settings;
        }
        HubSystemSettings defaults = defaults();
        try {
            if (repository.add(defaults)) {
                return defaults;
            }
        } catch (DuplicateKeyException ignored) {
            // Another node seeded the singleton between find and insert.
        }
        settings = repository.find();
        if (settings == null) {
            throw HubErrorCodes.SETTINGS_UPDATE_CONFLICT.asThrowable(
                "system settings singleton is unavailable");
        }
        return settings;
    }

    @Override
    public HubSystemSettings update(HubSystemSettingsDTO request) {
        if (request == null) {
            throw HubErrorCodes.SETTINGS_ARGUMENT_INVALID.asThrowable("settings payload is required");
        }
        ProxyConfiguration normalized = HubProxyValidator.normalizeGlobal(
            request.getProxyMode(), request.getProxyServer());
        for (int attempt = 0; attempt < 2; attempt++) {
            HubSystemSettings current = get();
            current.setProxyMode(normalized.proxyMode());
            current.setProxyServer(normalized.proxyServer());
            if (repository.update(current, current.getVersion())) {
                current.setVersion(current.getVersion() + 1);
                current.setUpdateTime(LocalDateTime.now());
                return current;
            }
        }
        throw HubErrorCodes.SETTINGS_UPDATE_CONFLICT.asThrowable(
            "system settings changed concurrently; retry the request");
    }

    private static HubSystemSettings defaults() {
        HubSystemSettings settings = new HubSystemSettings();
        settings.setProxyMode(HubProxyMode.DIRECT);
        settings.setProxyServer(null);
        settings.setVersion(0L);
        return settings;
    }

}
