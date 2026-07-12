package fun.fengwk.openclihub.core.instance.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.instance.repo.impl.model.HubInstanceDO;
import java.util.List;

/**
 * Auto-generated SQL mapper for hub_instance.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_instance")
public interface HubInstanceMapper extends BaseMapper {

    int insert(HubInstanceDO instanceDO);

    int updateById(HubInstanceDO instanceDO);

    int deleteById(long id);

    HubInstanceDO findById(long id);

    HubInstanceDO findByCode(String code);

    HubInstanceDO findByContextId(String contextId);

    List<HubInstanceDO> findAllOrderByIdAsc();

}
