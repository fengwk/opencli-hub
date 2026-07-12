package fun.fengwk.openclihub.share.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubInstanceDTO {

    private long id;
    private String code;
    private String displayName;
    private String opencliProfile;
    private String contextId;
    private String vncEndpoint;
    private HubInstanceState state;
    private int maxPending;
    private List<String> supportedCommands;
    private int activeCount;
    private int pendingCount;
    private int load;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
