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
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory cached blacklist service.
 *
 * <p>The cache is an immutable {@link Snapshot} that is atomically replaced on every
 * reload: readers only ever observe a complete snapshot and never take a lock. A reload
 * builds the replacement snapshot fully before publishing it with a single volatile
 * write, so a repository failure keeps the previous snapshot intact instead of exposing
 * an empty fail-open cache.
 *
 * <p>Loaded state travels with the snapshot because an empty database result and a
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
    private volatile Snapshot snapshot = new Snapshot(false, Map.of());

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
        return Optional.ofNullable(snapshot.entries.get(commandKey));
    }

    public List<HubCommandBlacklist> listAll() {
        ensureLoaded();
        return new ArrayList<>(snapshot.entries.values());
    }

    /**
     * Add a new blacklist entry. The command key must be unique; inserting a duplicate is
     * treated as a no-op so the management UI can use idempotent PUT semantics.
     */
    public HubCommandBlacklist blacklist(String commandKey, String reason) {
        validateCommandKey(commandKey);
        ensureLoaded();
        HubCommandBlacklist existing = snapshot.entries.get(commandKey);
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
        return snapshot.entries.get(commandKey);
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
        if (!snapshot.entries.containsKey(commandKey)) {
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
     *
     * <p>The reload builds a complete replacement snapshot and publishes it with a
     * single volatile write; concurrent readers keep seeing either the previous or the
     * new complete snapshot. When the repository read fails, the previous snapshot data
     * is kept and the cache is marked unloaded again so the next access retries and
     * converges once the repository recovers; the cache never degrades into an empty
     * fail-open state.
     */
    public synchronized void refresh() {
        try {
            Map<String, HubCommandBlacklist> built = new LinkedHashMap<>();
            for (HubCommandBlacklist entry : repository.listAll()) {
                built.put(entry.getCommandKey(), entry);
            }
            snapshot = new Snapshot(true, Collections.unmodifiableMap(built));
        } catch (RuntimeException ex) {
            snapshot = new Snapshot(false, snapshot.entries);
            throw ex;
        }
    }

    private void ensureLoaded() {
        if (snapshot.loaded) {
            return;
        }
        synchronized (this) {
            if (!snapshot.loaded) {
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
        return Collections.unmodifiableMap(new LinkedHashMap<>(snapshot.entries));
    }

    /**
     * Immutable cache state: the loaded flag plus the complete entry map. Published via a
     * volatile field so readers never observe a partially built map.
     */
    private static final class Snapshot {

        final boolean loaded;
        final Map<String, HubCommandBlacklist> entries;

        Snapshot(boolean loaded, Map<String, HubCommandBlacklist> entries) {
            this.loaded = loaded;
            this.entries = entries;
        }
    }

}
