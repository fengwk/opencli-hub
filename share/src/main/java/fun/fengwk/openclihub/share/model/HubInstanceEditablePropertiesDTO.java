package fun.fengwk.openclihub.share.model;

import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubInstanceEditablePropertiesDTO {

    private String code;
    private String displayName;
    private String opencliProfile;
    private String contextId;
    private String vncEndpoint;
    private HubInstanceState state;
    private Integer maxPending;
    private List<String> supportedCommands;

}
