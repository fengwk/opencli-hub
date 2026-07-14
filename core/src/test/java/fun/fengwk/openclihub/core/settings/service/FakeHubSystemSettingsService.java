package fun.fengwk.openclihub.core.settings.service;

import fun.fengwk.openclihub.core.proxy.HubProxyValidator;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;

/** In-memory settings service for runtime tests. */
public class FakeHubSystemSettingsService implements HubSystemSettingsService {

    private HubSystemSettings settings = settings(HubProxyMode.DIRECT, null);

    public void set(HubProxyMode proxyMode, String proxyServer) {
        var normalized = HubProxyValidator.normalizeGlobal(proxyMode, proxyServer);
        settings = settings(normalized.proxyMode(), normalized.proxyServer());
    }

    @Override
    public HubSystemSettings get() {
        return settings(settings.getProxyMode(), settings.getProxyServer());
    }

    @Override
    public HubSystemSettings update(HubSystemSettingsDTO request) {
        set(request.getProxyMode(), request.getProxyServer());
        return get();
    }

    private static HubSystemSettings settings(HubProxyMode proxyMode, String proxyServer) {
        HubSystemSettings value = new HubSystemSettings();
        value.setProxyMode(proxyMode);
        value.setProxyServer(proxyServer);
        return value;
    }

}
