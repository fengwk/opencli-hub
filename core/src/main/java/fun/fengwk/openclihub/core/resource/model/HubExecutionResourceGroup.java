package fun.fengwk.openclihub.core.resource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Identifies and locates a per-execution resource group. The same group is created by the
 * core service, scanned for output files, and conditionally removed once empty.
 *
 * @author fengwk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubExecutionResourceGroup {

    private long executionId;
    private LocalDate date;
    private String group;
    private Path realPath;

    /**
     * Convenience virtual path pointing at the group root.
     */
    public String virtualGroupPath() {
        return "/resources/" + date + "/" + group;
    }

}
