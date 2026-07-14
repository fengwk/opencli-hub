package fun.fengwk.openclihub.share.model.settings;

import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import lombok.Data;

/**
 * Global browser traffic settings exposed by the management API.
 *
 * @author fengwk
 */
@Data
public class HubSystemSettingsDTO {

    private HubProxyMode proxyMode;
    private String proxyServer;

}
