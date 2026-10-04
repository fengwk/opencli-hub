package fun.fengwk.openclihub.share.model.instance;

import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.util.List;
import lombok.Data;

/**
 * Administrator-editable instance properties.
 *
 * @author fengwk
 */
@Data
public class HubInstanceEditablePropertiesDTO {

    private String code;
    private String displayName;
    private List<String> websites;
    private Integer maxPending;
    private Integer maxConcurrency;
    /** Larger value is preferred when load is equal; default 0. */
    private Integer priority;
    /** Instance continuous-idle TTL in seconds; -1 = never reclaim, 0 = next sweep, positive = seconds. Default 1800. */
    private Integer warmTabTtlSeconds;
    private HubProxyMode proxyMode;
    private String proxyServer;

}
