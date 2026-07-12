package fun.fengwk.openclihub.core.command.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.command.repo.impl.model.HubCommandOutputRuleDO;
import java.util.List;

/**
 * Auto-generated SQL mapper for {@code hub_command_output_rule}.
 *
 * <p>See {@link HubCommandBlacklistMapper} for the no-empty-XML convention.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_command_output_rule")
public interface HubCommandOutputRuleMapper extends BaseMapper {

    int insert(HubCommandOutputRuleDO ruleDO);

    int updateById(HubCommandOutputRuleDO ruleDO);

    int deleteById(long id);

    int deleteByCommandKey(String commandKey);

    HubCommandOutputRuleDO findById(long id);

    HubCommandOutputRuleDO findByCommandKey(String commandKey);

    List<HubCommandOutputRuleDO> findAll();

}