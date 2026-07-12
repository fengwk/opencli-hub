package fun.fengwk.openclihub.share.model.command;

import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.util.List;
import lombok.Data;

/**
 * Public OpenCLI browser command and current Hub policy.
 *
 * @author fengwk
 */
@Data
public class HubCommandDTO {

    private String commandKey;
    private String site;
    private String name;
    private List<String> aliases;
    private String description;
    private HubCommandAccess access;
    private boolean browser;
    private List<HubCommandArgDTO> args;
    private SiteSessionMode siteSession;
    private String defaultWindowMode;
    private boolean blacklisted;
    private String blacklistReason;
    private HubCommandOutputRuleDTO outputRule;

}
