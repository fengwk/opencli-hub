package fun.fengwk.openclihub.core.command.repo.impl;

import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.repo.impl.mapper.HubCommandBlacklistMapper;
import fun.fengwk.openclihub.core.command.repo.impl.model.HubCommandBlacklistDO;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MyBatis implementation (MySQL/H2 shared SQL) backed by the auto-generated
 * {@code hub_command_blacklist} mapper.
 *
 * <p>The repository is responsible only for ID generation, DO conversion and mapper
 * invocation. Schema management lives in {@code schema-{h2,mysql}.sql}; this class must
 * not call any DDL at runtime. Audit timestamps are owned by the service layer and are
 * copied through verbatim.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MybatisHubCommandBlacklistRepository implements HubCommandBlacklistRepository {

    private final HubCommandBlacklistMapper mapper;

    @Override
    public String generateId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean add(HubCommandBlacklist blacklist) {
        return blacklist != null && mapper.insert(toDO(blacklist)) == 1;
    }

    @Override
    public boolean update(HubCommandBlacklist blacklist) {
        return blacklist != null && mapper.updateById(toDO(blacklist)) == 1;
    }

    @Override
    public boolean deleteById(String id) {
        return mapper.deleteById(id) == 1;
    }

    @Override
    public boolean deleteByCommandKey(String commandKey) {
        return commandKey != null && mapper.deleteByCommandKey(commandKey) == 1;
    }

    @Override
    public HubCommandBlacklist findById(String id) {
        return fromDO(mapper.findById(id));
    }

    @Override
    public Optional<HubCommandBlacklist> findByCommandKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fromDO(mapper.findByCommandKey(commandKey)));
    }

    @Override
    public List<HubCommandBlacklist> listAll() {
        return mapper.findAll().stream().map(this::fromDO).toList();
    }

    private HubCommandBlacklistDO toDO(HubCommandBlacklist blacklist) {
        HubCommandBlacklistDO target = new HubCommandBlacklistDO();
        target.setId(blacklist.getId());
        target.setCommandKey(blacklist.getCommandKey());
        target.setReason(blacklist.getReason());
        target.setCreateTime(blacklist.getCreateTime());
        target.setUpdateTime(blacklist.getUpdateTime());
        target.setVersion(0L);
        return target;
    }

    private HubCommandBlacklist fromDO(HubCommandBlacklistDO source) {
        if (source == null) {
            return null;
        }
        HubCommandBlacklist target = new HubCommandBlacklist();
        target.setId(source.getId());
        target.setCommandKey(source.getCommandKey());
        target.setReason(source.getReason());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

}
