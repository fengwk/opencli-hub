package fun.fengwk.openclihub.core.resource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Upload call envelope accepted by the resource service. Wraps one or more streamed parts
 * and the caller-supplied target date. The service derives the upload group id, so the
 * caller never supplies it.
 *
 * @author fengwk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubResourceUploadRequest {

    /** Targeted UTC date for the upload. If {@code null}, {@code java.time.LocalDate.now(ZoneOffset.UTC)} is used. */
    private String date;

    /** Non-empty list of uploaded parts. */
    private List<HubResourceUploadItem> items;

}
