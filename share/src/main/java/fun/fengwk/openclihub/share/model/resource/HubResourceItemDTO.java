package fun.fengwk.openclihub.share.model.resource;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * File-system resource exposed through a controlled virtual path.
 *
 * @author fengwk
 */
@Data
public class HubResourceItemDTO {

    private String date;
    private String group;
    private String relativePath;
    private String resourcePath;
    private String fileName;
    private HubResourceSource source;
    private String mimeType;
    private long size;
    private LocalDateTime modifiedAt;
    private String contentUrl;
    private String downloadUrl;

}
