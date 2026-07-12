package fun.fengwk.openclihub.core.command.repo.impl;

import fun.fengwk.convention4j.common.idgen.NamespaceIdGenerator;
import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.repo.impl.mapper.HubCommandBlacklistMapper;
import fun.fengwk.openclihub.core.command.repo.impl.model.HubCommandBlacklistDO;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MySQL/H2 implementation backed by the auto-generated {@code hub_command_blacklist} mapper.
 *
 * <p>The repository is responsible only for ID generation, DO conversion and mapper
 * invocation. Schema management lives in {@code schema-{h2,mysql}.sql}; this class must
 * not call any DDL at runtime.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubCommandBlacklistRepository implements HubCommandBlacklistRepository {

    private final NamespaceIdGenerator<Long> idGenerator;
    private final HubCommandBlacklistMapper mapper;

    @Override
    public long generateId() {
        return idGenerator.next(HubCommandBlacklist.class);
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
    public boolean deleteById(long id) {
        return mapper.deleteById(id) == 1;
    }

    @Override
    public boolean deleteByCommandKey(String commandKey) {
        return commandKey != null && mapper.deleteByCommandKey(commandKey) == 1;
    }

    @Override
    public HubCommandBlacklist findById(long id) {
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
        LocalDateTime now = LocalDateTime.now();
        HubCommandBlacklistDO target = new HubCommandBlacklistDO();
        target.setId(blacklist.getId());
        target.setCommandKey(blacklist.getCommandKey());
        target.setReason(blacklist.getReason());
        target.setCreateTime(blacklist.getCreateTime() == null ? now : blacklist.getCreateTime());
        target.setModifiedTime(blacklist.getUpdateTime() == null ? now : blacklist.getUpdateTime());
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
        target.setUpdateTime(source.getModifiedTime());
        return target;
    }

}
