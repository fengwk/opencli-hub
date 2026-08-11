package fun.fengwk.openclihub.core.execution.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.automapper.annotation.MethodExpr;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.execution.repo.impl.model.HubExecutionDO;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Auto-generated SQL mapper for hub_execution.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_execution")
public interface HubExecutionMapper extends BaseMapper {

    int insert(HubExecutionDO executionDO);

    int updateById(HubExecutionDO executionDO);

    HubExecutionDO findById(String id);

    long countAll();

    /**
     * Derives {@code select ... order by queued_at desc, id desc limit #{limit} offset #{offset}}
     * with AutoMapper 1.0.0. The method name keeps its historical Java spelling; the
     * {@link MethodExpr} supplies the missing {@code And} between the two order-by fields.
     */
    @MethodExpr("pageAllOrderByQueuedAtDescAndIdDesc")
    List<HubExecutionDO> pageAllOrderByQueuedAtDescIdDesc(
        @Param("offset") long offset, @Param("limit") int limit);

    long countByInstanceId(String instanceId);

    /**
     * Derives {@code select ... where instance_id = #{instanceId} order by queued_at desc,
     * id desc limit #{limit} offset #{offset}} with AutoMapper 1.0.0; see
     * {@link #pageAllOrderByQueuedAtDescIdDesc(long, int)} for the naming rationale.
     */
    @MethodExpr("pageByInstanceIdOrderByQueuedAtDescAndIdDesc")
    List<HubExecutionDO> pageByInstanceIdOrderByQueuedAtDescIdDesc(
        @Param("instanceId") String instanceId,
        @Param("offset") long offset,
        @Param("limit") int limit);


    @Update("""
        update hub_execution
        set status = 'RUNNING',
            started_at = #{startedAt},
            gmt_modified = #{startedAt}
        where id = #{id} and status = 'PENDING'
        """)
    int markRunningIfPending(@Param("id") String id, @Param("startedAt") LocalDateTime startedAt);

    @Update("""
        update hub_execution
        set status = 'CANCELLED',
            error_message = #{errorMessage},
            finished_at = #{finishedAt},
            gmt_modified = #{finishedAt}
        where id = #{id} and status = 'PENDING'
        """)
    int markCancelledIfPending(
        @Param("id") String id,
        @Param("errorMessage") String errorMessage,
        @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
        update hub_execution
        set status = #{status},
            error_message = #{errorMessage},
            exit_code = #{exitCode},
            finished_at = #{finishedAt},
            gmt_modified = #{finishedAt}
        where id = #{id} and status = 'PENDING'
        """)
    int markTerminalIfPending(
        @Param("id") String id,
        @Param("status") String status,
        @Param("errorMessage") String errorMessage,
        @Param("exitCode") Integer exitCode,
        @Param("finishedAt") LocalDateTime finishedAt);

}
