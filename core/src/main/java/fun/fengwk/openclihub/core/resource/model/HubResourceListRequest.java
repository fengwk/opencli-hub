package fun.fengwk.openclihub.core.resource.model;

import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filtering and pagination knobs for browsing a day's resources. Mirrors the minimum set of
 * features required by the technical design so the M6 controller can stay thin.
 *
 * @author fengwk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubResourceListRequest {

    /** UTC date to list. */
    private String date;

    /** Optional source filter; null means both upload and execution groups. */
    private HubResourceSource source;

    /** Case-insensitive substring matched against the file name. */
    private String keyword;

    /** Sort field, defaults to modified time descending. */
    private ResourceSort sort;

    /** Page index (zero based). */
    private int page;

    /** Page size; capped by {@link HubResourceListConstants#MAX_PAGE_SIZE}. */
    private int pageSize;

    public enum ResourceSort {
        MODIFIED_DESC,
        MODIFIED_ASC,
        SIZE_DESC,
        SIZE_ASC,
        NAME_ASC,
        NAME_DESC
    }

}
