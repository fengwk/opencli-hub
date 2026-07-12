package fun.fengwk.openclihub.share.model.resource;

import lombok.Data;

/**
 * Aggregate resource usage for one UTC date.
 *
 * @author fengwk
 */
@Data
public class HubResourceDateSummaryDTO {

    private String date;
    private long groupCount;
    private long fileCount;
    private long totalSize;

}
