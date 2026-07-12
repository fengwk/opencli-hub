package fun.fengwk.openclihub.core.resource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;

/**
 * A single uploaded payload submitted to the resource service. Implementations of the M6
 * controller will translate multipart parts into this model before calling the core API.
 *
 * @author fengwk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubResourceUploadItem {

    /** Original form/file name supplied by the caller; will be sanitized and possibly renamed. */
    private String originalFileName;

    /** Streamed content of the uploaded part. */
    private InputStream inputStream;

    /** Caller-supplied content length, if known; -1 means unknown. */
    private long size;

}
