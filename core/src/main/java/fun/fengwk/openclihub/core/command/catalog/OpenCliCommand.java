package fun.fengwk.openclihub.core.command.catalog;

import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.util.List;
import lombok.Data;

/**
 * Canonical OpenCLI command metadata used by routing and validation.
 *
 * @author fengwk
 */
@Data
public class OpenCliCommand {

    private String commandKey;
    private String site;
    private String name;
    private List<String> aliases;
    private String description;
    private HubCommandAccess access;
    private boolean browser;
    private List<OpenCliCommandArg> args;
    private SiteSessionMode siteSession;
    private String defaultWindowMode;

}
