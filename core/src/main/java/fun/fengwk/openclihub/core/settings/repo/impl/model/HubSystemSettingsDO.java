package fun.fengwk.openclihub.core.settings.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence model for {@code hub_system_settings}.
 *
 * @author fengwk
 */
@Data
public class HubSystemSettingsDO {

    private Integer id;
    private String proxyMode;
    private String proxyServer;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime updateTime;
    private Long version;

}
