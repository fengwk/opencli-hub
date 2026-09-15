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
    /** Warm tab idle TTL in seconds; -1 = never, 0 = immediately, positive = seconds. Default 1800. */
    private Integer warmTabTtlSeconds;
    private HubProxyMode proxyMode;
    private String proxyServer;

}
