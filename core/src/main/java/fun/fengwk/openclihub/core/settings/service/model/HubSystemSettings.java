package fun.fengwk.openclihub.core.settings.service.model;

import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persisted singleton for Hub-wide browser settings.
 *
 * @author fengwk
 */
@Data
public class HubSystemSettings {

    private int id = 1;
    private HubProxyMode proxyMode = HubProxyMode.DIRECT;
    private String proxyServer;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private long version;

}
