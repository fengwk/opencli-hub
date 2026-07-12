package fun.fengwk.openclihub.share.model.resource;

import java.util.List;
import lombok.Data;

/**
 * Resource upload response envelope.
 *
 * @author fengwk
 */
@Data
public class HubResourceUploadResultDTO {

    private String date;
    private String group;
    private List<HubResourceItemDTO> items;

}
