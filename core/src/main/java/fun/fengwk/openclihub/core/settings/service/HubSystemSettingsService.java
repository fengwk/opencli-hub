package fun.fengwk.openclihub.core.settings.service;

import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;

/**
 * Global settings service used by management APIs and instance startup.
 *
 * @author fengwk
 */
public interface HubSystemSettingsService {

    HubSystemSettings get();

    HubSystemSettings update(HubSystemSettingsDTO request);

}
