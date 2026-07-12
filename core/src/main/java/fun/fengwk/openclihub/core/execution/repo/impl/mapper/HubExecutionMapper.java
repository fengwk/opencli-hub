package fun.fengwk.openclihub.core.execution.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.execution.repo.impl.model.HubExecutionDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Auto-generated SQL mapper for hub_execution.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_execution")
public interface HubExecutionMapper extends BaseMapper {

    int insert(HubExecutionDO executionDO);

    int updateById(HubExecutionDO executionDO);

    HubExecutionDO findById(long id);

    long countAll();

    List<HubExecutionDO> pageAllOrderByIdDesc(@Param("offset") long offset, @Param("limit") int limit);

    long countByInstanceId(long instanceId);

    List<HubExecutionDO> pageByInstanceIdOrderByIdDesc(
        @Param("instanceId") long instanceId,
        @Param("offset") long offset,
        @Param("limit") int limit);

}
