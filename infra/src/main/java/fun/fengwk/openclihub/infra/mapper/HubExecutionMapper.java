package fun.fengwk.openclihub.infra.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.infra.model.HubExecutionDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * @author fengwk
 */
@AutoMapper
public interface HubExecutionMapper extends BaseMapper {

    void createTableIfNotExists();

    int insertSelective(HubExecutionDO executionDO);

    int updateSelectiveById(HubExecutionDO executionDO);

    HubExecutionDO selectById(long id);

    long countAll();

    List<HubExecutionDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

}
