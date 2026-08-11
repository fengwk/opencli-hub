package fun.fengwk.openclihub.core.command.service;

import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandBlacklistDTO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory cached blacklist service.
 *
 * <p>The cache is a {@link ConcurrentHashMap} keyed by canonical command key. Loaded
 * state is tracked explicitly via {@link #loaded} because an empty database result and a
 * not-yet-loaded cache both produce an empty map; treating the map's emptiness as the
 * "unloaded" signal would force a redundant {@code listAll()} on every call against a
 * legitimately empty table.
 *
 * <p>Every mutator method calls {@link #ensureLoaded()} before consulting the cache so
 * duplicate-key inserts are blocked even on a cold cache.
 *
 * @author fengwk
 */
@Slf4j
public class HubCommandBlacklistService {

    private final HubCommandBlacklistRepository repository;
    private final Clock clock;
    private final ConcurrentMap<String, HubCommandBlacklist> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public HubCommandBlacklistService(HubCommandBlacklistRepository repository, Clock clock) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Returns the cached blacklist entry for the given canonical command key, loading
     * from the database on first access.
     */
    public Optional<HubCommandBlacklist> findByCommandKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(cache.get(commandKey));
    }

    public List<HubCommandBlacklist> listAll() {
        ensureLoaded();
        return new ArrayList<>(cache.values());
    }

    /**
     * Add a new blacklist entry. The command key must be unique; inserting a duplicate is
     * treated as a no-op so the management UI can use idempotent PUT semantics.
     */
    public HubCommandBlacklist blacklist(String commandKey, String reason) {
        validateCommandKey(commandKey);
        ensureLoaded();
        HubCommandBlacklist existing = cache.get(commandKey);
        if (existing != null) {
            return existing;
        }
        HubCommandBlacklist blacklist = new HubCommandBlacklist();
        blacklist.setId(repository.generateId());
        blacklist.setCommandKey(commandKey);
        blacklist.setReason(reason);
        LocalDateTime now = LocalDateTime.now(clock);
        blacklist.setCreateTime(now);
        blacklist.setUpdateTime(now);
        if (!repository.add(blacklist)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                "Failed to persist blacklist entry: " + commandKey);
        }
        refresh();
        log.info("Blacklisted OpenCLI command {}", commandKey);
        return cache.get(commandKey);
    }

    /**
     * Remove an existing blacklist entry. Returns {@code true} when the entry existed and
     * has been deleted; {@code false} when nothing matched.
     */
    public boolean unblacklist(String commandKey) {
        if (commandKey == null) {
            return false;
        }
        ensureLoaded();
        if (!cache.containsKey(commandKey)) {
            return false;
        }
        if (!repository.deleteByCommandKey(commandKey)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                "Failed to delete blacklist entry: " + commandKey);
        }
        refresh();
        log.info("Removed blacklist for OpenCLI command {}", commandKey);
        return true;
    }

    /**
     * Force the cache to reload from the database. Intended for tests and admin tools.
     */
    public synchronized void refresh() {
        cache.clear();
        for (HubCommandBlacklist entry : repository.listAll()) {
            cache.put(entry.getCommandKey(), entry);
        }
        loaded.set(true);
    }

    private void ensureLoaded() {
        if (loaded.get()) {
            return;
        }
        synchronized (this) {
            if (!loaded.get()) {
                refresh();
            }
        }
    }

    private static void validateCommandKey(String commandKey) {
        if (commandKey == null || commandKey.isBlank()) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Command key must not be blank");
        }
        if (!commandKey.contains("/")) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Command key must use site/name form: " + commandKey);
        }
    }

    public List<HubCommandBlacklistDTO> listAllAsDTO() {
        List<HubCommandBlacklist> all = listAll();
        List<HubCommandBlacklistDTO> dtos = new ArrayList<>(all.size());
        for (HubCommandBlacklist entry : all) {
            HubCommandBlacklistDTO dto = new HubCommandBlacklistDTO();
            dto.setId(entry.getId());
            dto.setCommandKey(entry.getCommandKey());
            dto.setReason(entry.getReason());
            dto.setCreateTime(entry.getCreateTime());
            dto.setUpdateTime(entry.getUpdateTime());
            dtos.add(dto);
        }
        return Collections.unmodifiableList(dtos);
    }

    /**
     * Snapshot of the current blacklist keyed by canonical command key. Useful for tests
     * and for the DTO assembler that joins policies together.
     */
    public Map<String, HubCommandBlacklist> snapshot() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
    }

}
