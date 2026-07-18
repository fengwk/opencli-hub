package fun.fengwk.openclihub.share.model.plugin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * Create/update payload for a plugin source.
 *
 * @author fengwk
 */
@Data
public class HubPluginSourceUpsertDTO {

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Size(max = 1024)
    private String source;

    private List<@Size(max = 128) String> desiredPlugins;

    private Boolean enabled;

    private Boolean autoUpdate;

}
