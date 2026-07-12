package fun.fengwk.openclihub.infra.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.infra.model.HubInstanceDO;
import java.util.List;

/**
 * @author fengwk
 */
@AutoMapper
public interface HubInstanceMapper extends BaseMapper {

    void createTableIfNotExists();

    int insertSelective(HubInstanceDO instanceDO);

    int updateSelectiveById(HubInstanceDO instanceDO);

    int deleteById(long id);

    HubInstanceDO selectById(long id);

    HubInstanceDO selectByCode(String code);

    List<HubInstanceDO> listAll();

}
