package fun.fengwk.openclihub.core.execution.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.execution.repo.impl.model.HubExecutionDO;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    @Select("""
        select id, instance_id as instanceId, instance_code as instanceCode,
               command_key as commandKey, site, site_session as siteSession,
               argv_json as argvJson, reuse_instance as reuseInstance, status,
               exit_code as exitCode, stdout_content as stdoutContent,
               stdout_truncated as stdoutTruncated, stderr_content as stderrContent,
               stderr_truncated as stderrTruncated, error_message as errorMessage,
               timeout_millis as timeoutMillis, queued_at as queuedAt,
               started_at as startedAt, finished_at as finishedAt,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_execution
        order by coalesce(finished_at, started_at, queued_at) desc, id desc
        limit #{offset}, #{limit}
        """)
    List<HubExecutionDO> pageAllOrderByQueuedAtDescIdDesc(
        @Param("offset") long offset, @Param("limit") int limit);

    long countByInstanceId(String instanceId);

    @Select("""
        select id, instance_id as instanceId, instance_code as instanceCode,
               command_key as commandKey, site, site_session as siteSession,
               argv_json as argvJson, reuse_instance as reuseInstance, status,
               exit_code as exitCode, stdout_content as stdoutContent,
               stdout_truncated as stdoutTruncated, stderr_content as stderrContent,
               stderr_truncated as stderrTruncated, error_message as errorMessage,
               timeout_millis as timeoutMillis, queued_at as queuedAt,
               started_at as startedAt, finished_at as finishedAt,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_execution
        where instance_id = #{instanceId}
        order by coalesce(finished_at, started_at, queued_at) desc, id desc
        limit #{offset}, #{limit}
        """)
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
