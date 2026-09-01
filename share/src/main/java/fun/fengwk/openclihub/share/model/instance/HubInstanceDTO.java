package fun.fengwk.openclihub.share.model.instance;

import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Browser instance details returned by the management API.
 *
 * @author fengwk
 */
@Data
public class HubInstanceDTO {

    private String id;
    private String code;
    private String displayName;
    private String contextId;
    private HubInstanceState state;
    private List<String> websites;
    private int maxPending;
    private int maxConcurrency = 1;
    private int priority;
    private HubProxyMode proxyMode;
    private String proxyServer;
    private String lastErrorMessage;
    private LocalDateTime stateChangedAt;
    private HubInstanceRuntimeDTO runtime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
