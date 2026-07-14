package fun.fengwk.openclihub.core.settings.service.converter;

import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;
import org.springframework.stereotype.Component;

/**
 * Converts the internal settings singleton to its API representation.
 *
 * @author fengwk
 */
@Component
public class HubSystemSettingsConverter {

    public HubSystemSettingsDTO toDTO(HubSystemSettings settings) {
        if (settings == null) {
            return null;
        }
        HubSystemSettingsDTO dto = new HubSystemSettingsDTO();
        dto.setProxyMode(settings.getProxyMode());
        dto.setProxyServer(settings.getProxyServer());
        return dto;
    }

}
