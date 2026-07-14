package fun.fengwk.openclihub.share.model.log;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Tail content and metadata of one managed log file.
 *
 * @author fengwk
 */
@Data
public class HubLogContentDTO {

    private HubLogSource source;
    private String instanceId;
    private String content;
    private boolean truncated;
    private long fileSize;
    private LocalDateTime modifiedAt;

}
