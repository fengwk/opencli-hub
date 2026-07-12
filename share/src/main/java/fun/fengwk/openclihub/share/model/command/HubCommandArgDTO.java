package fun.fengwk.openclihub.share.model.command;

import java.util.List;
import lombok.Data;

/**
 * Catalog argument metadata.
 *
 * @author fengwk
 */
@Data
public class HubCommandArgDTO {

    private String name;
    private String type;
    private boolean required;
    private boolean valueRequired;
    private boolean positional;
    private List<String> choices;
    private Object defaultValue;
    private String help;

}
