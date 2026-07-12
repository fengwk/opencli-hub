package fun.fengwk.openclihub.share.model.instance;

import java.util.List;
import lombok.Data;

/**
 * Administrator-editable instance properties.
 *
 * @author fengwk
 */
@Data
public class HubInstanceEditablePropertiesDTO {

    private String code;
    private String displayName;
    private List<String> websites;
    private Integer maxPending;

}
