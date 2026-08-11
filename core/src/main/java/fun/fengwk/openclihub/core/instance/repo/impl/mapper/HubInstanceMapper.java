package fun.fengwk.openclihub.core.instance.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.instance.repo.impl.model.HubInstanceDO;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/**
 * Auto-generated SQL mapper for hub_instance.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_instance")
public interface HubInstanceMapper extends BaseMapper {

    int insert(HubInstanceDO instanceDO);

    int updateById(HubInstanceDO instanceDO);

    int deleteById(String id);

    HubInstanceDO findById(String id);

    HubInstanceDO findByCode(String code);

    HubInstanceDO findByContextId(String contextId);

    /**
     * Hand-written portable SQL: AutoMapper 1.0.0 resolves {@code OrderBy} variables through
     * the naming converter only (it does not consult {@code @FieldName}), so the semantic
     * {@code OrderByCreateTimeAsc} would emit {@code create_time} instead of the physical
     * {@code gmt_create} column. Keep the explicit SQL until an upstream release resolves
     * order-by fields through {@code @FieldName}.
     */
    @Select("""
        select id, code, display_name as displayName, context_id as contextId, state,
               websites_json as websitesJson, max_pending as maxPending, priority,
               proxy_mode as proxyMode, proxy_server as proxyServer,
               last_error_message as lastErrorMessage, state_changed_at as stateChangedAt,
               gmt_create as createTime, gmt_modified as updateTime, version
        from hub_instance
        order by gmt_create asc, id asc
        """)
    List<HubInstanceDO> findAllOrderByCreateTimeAscIdAsc();

}
