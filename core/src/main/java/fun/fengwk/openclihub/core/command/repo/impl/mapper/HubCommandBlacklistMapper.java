package fun.fengwk.openclihub.core.command.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.command.repo.impl.model.HubCommandBlacklistDO;
import java.util.List;

/**
 * Auto-generated SQL mapper for {@code hub_command_blacklist}.
 *
 * <p>No empty Mapper XML is committed under {@code src/main/resources}; the annotation
 * processor emits the full mapper XML into {@code target/classes} at compile time and the
 * MyBatis mapper scanner picks it up from the generated output.
 *
 * @author fengwk
 */
@AutoMapper(tableName = "hub_command_blacklist")
public interface HubCommandBlacklistMapper extends BaseMapper {

    int insert(HubCommandBlacklistDO blacklistDO);

    int updateById(HubCommandBlacklistDO blacklistDO);

    int deleteById(long id);

    int deleteByCommandKey(String commandKey);

    HubCommandBlacklistDO findById(long id);

    HubCommandBlacklistDO findByCommandKey(String commandKey);

    List<HubCommandBlacklistDO> findAll();

}