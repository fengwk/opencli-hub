package fun.fengwk.openclihub.share.model.plugin;

import lombok.Data;

/**
 * One installed OpenCLI plugin as reported by {@code opencli plugin list}.
 *
 * @author fengwk
 */
@Data
public class HubInstalledPluginDTO {

    private String name;
    private String raw;

}
