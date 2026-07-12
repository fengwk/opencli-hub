package fun.fengwk.openclihub.share.model;

import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubCommandSummaryDTO {

    private String commandKey;
    private int totalInstances;
    private int onlineInstances;
    private List<String> instanceCodes;

}
