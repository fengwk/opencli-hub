package fun.fengwk.openclihub.core.command.catalog;

import java.util.List;
import lombok.Data;

/**
 * Stable argument metadata loaded from the OpenCLI command catalog.
 *
 * @author fengwk
 */
@Data
public class OpenCliCommandArg {

    private String name;
    private String type;
    private boolean required;
    private boolean valueRequired;
    private boolean positional;
    private List<String> choices;
    private Object defaultValue;
    private String help;

}
