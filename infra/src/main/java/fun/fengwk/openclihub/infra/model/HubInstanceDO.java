package fun.fengwk.openclihub.infra.model;

import fun.fengwk.convention4j.springboot.starter.persistence.ConventionDO;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubInstanceDO extends ConventionDO<Long> {

    private String code;
    private String displayName;
    private String opencliProfile;
    private String contextId;
    private String vncEndpoint;
    private String state;
    private Integer maxPending;
    private String supportedCommandsJson;

}
