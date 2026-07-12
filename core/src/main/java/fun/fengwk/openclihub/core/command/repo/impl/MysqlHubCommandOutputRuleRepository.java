package fun.fengwk.openclihub.core.command.repo.impl;

import fun.fengwk.convention4j.common.idgen.NamespaceIdGenerator;
import fun.fengwk.openclihub.core.command.repo.HubCommandOutputRuleRepository;
import fun.fengwk.openclihub.core.command.repo.impl.mapper.HubCommandOutputRuleMapper;
import fun.fengwk.openclihub.core.command.repo.impl.model.HubCommandOutputRuleDO;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MySQL/H2 implementation backed by the auto-generated {@code hub_command_output_rule} mapper.
 *
 * <p>Schema management lives in {@code schema-{h2,mysql}.sql}; this class must not call any DDL.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubCommandOutputRuleRepository implements HubCommandOutputRuleRepository {

    private final NamespaceIdGenerator<Long> idGenerator;
    private final HubCommandOutputRuleMapper mapper;

    @Override
    public long generateId() {
        return idGenerator.next(HubCommandOutputRule.class);
    }

    @Override
    public boolean add(HubCommandOutputRule rule) {
        return rule != null && mapper.insert(toDO(rule)) == 1;
    }

    @Override
    public boolean update(HubCommandOutputRule rule) {
        return rule != null && mapper.updateById(toDO(rule)) == 1;
    }

    @Override
    public boolean deleteById(long id) {
        return mapper.deleteById(id) == 1;
    }

    @Override
    public boolean deleteByCommandKey(String commandKey) {
        return commandKey != null && mapper.deleteByCommandKey(commandKey) == 1;
    }

    @Override
    public HubCommandOutputRule findById(long id) {
        return fromDO(mapper.findById(id));
    }

    @Override
    public Optional<HubCommandOutputRule> findByCommandKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fromDO(mapper.findByCommandKey(commandKey)));
    }

    @Override
    public List<HubCommandOutputRule> listAll() {
        return mapper.findAll().stream().map(this::fromDO).toList();
    }

    private HubCommandOutputRuleDO toDO(HubCommandOutputRule rule) {
        LocalDateTime now = LocalDateTime.now();
        HubCommandOutputRuleDO target = new HubCommandOutputRuleDO();
        target.setId(rule.getId());
        target.setCommandKey(rule.getCommandKey());
        target.setArgumentName(rule.getArgumentName());
        target.setTargetType(rule.getTargetType() == null ? null : rule.getTargetType().name());
        target.setFileName(rule.getFileName());
        target.setCreateTime(rule.getCreateTime() == null ? now : rule.getCreateTime());
        target.setModifiedTime(rule.getUpdateTime() == null ? now : rule.getUpdateTime());
        target.setVersion(0L);
        return target;
    }

    private HubCommandOutputRule fromDO(HubCommandOutputRuleDO source) {
        if (source == null) {
            return null;
        }
        HubCommandOutputRule target = new HubCommandOutputRule();
        target.setId(source.getId());
        target.setCommandKey(source.getCommandKey());
        target.setArgumentName(source.getArgumentName());
        target.setTargetType(source.getTargetType() == null
            ? null
            : HubCommandOutputTargetType.valueOf(source.getTargetType()));
        target.setFileName(source.getFileName());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getModifiedTime());
        return target;
    }

}