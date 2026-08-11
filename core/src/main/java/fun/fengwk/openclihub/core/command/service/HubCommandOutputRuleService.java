package fun.fengwk.openclihub.core.command.service;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.command.repo.HubCommandOutputRuleRepository;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgumentType;
import fun.fengwk.openclihub.core.opencli.catalog.OpenCliReservedManagementCommands;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputRuleDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
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
 * In-memory cached output rule service.
 *
 * <p>The cache is an immutable {@link Snapshot} that is atomically replaced on every
 * reload: readers only ever observe a complete snapshot and never take a lock. A reload
 * builds the replacement snapshot fully before publishing it with a single volatile
 * write, so a repository failure keeps the previous snapshot intact instead of exposing
 * an empty fail-open cache.
 *
 * <p>Loaded state travels with the snapshot because a legitimately empty database
 * result and a not-yet-loaded cache both produce an empty map; treating the map's
 * emptiness as the "unloaded" signal would force a redundant {@code listAll()} on every
 * call against an empty table.
 *
 * <p>The service also acts as the validator for rule changes: when an admin saves or
 * replaces a rule, the service checks the catalog to confirm the command exists as a
 * public browser command and that the argument is a named, value-accepting output
 * parameter (positional arguments and bare boolean flags are rejected).
 *
 * @author fengwk
 */
@Slf4j
public class HubCommandOutputRuleService {

    private static final String SAFE_FILENAME_PATTERN = "[A-Za-z0-9._-]+";

    private final HubCommandOutputRuleRepository repository;
    private final OpenCliCommandCatalog catalog;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(false, Map.of());

    public HubCommandOutputRuleService(HubCommandOutputRuleRepository repository,
                                       OpenCliCommandCatalog catalog,
                                       Clock clock) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Optional<HubCommandOutputRule> findByCommandKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(snapshot.entries.get(commandKey));
    }

    public List<HubCommandOutputRule> listAll() {
        ensureLoaded();
        return new ArrayList<>(snapshot.entries.values());
    }

    /**
     * Insert or replace the rule for the canonical command key.
     */
    public HubCommandOutputRule upsert(String commandKey, String argumentName,
                                       HubCommandOutputTargetType targetType, String fileName) {
        validateRule(commandKey, argumentName, targetType, fileName);
        ensureLoaded();
        HubCommandOutputRule existing = snapshot.entries.get(commandKey);
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setCommandKey(commandKey);
        rule.setArgumentName(argumentName);
        rule.setTargetType(targetType);
        rule.setFileName(fileName);
        if (existing == null) {
            rule.setId(repository.generateId());
            LocalDateTime now = LocalDateTime.now(clock);
            rule.setCreateTime(now);
            rule.setUpdateTime(now);
            if (!repository.add(rule)) {
                throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                    "Failed to persist output rule: " + commandKey);
            }
        } else {
            rule.setId(existing.getId());
            rule.setCreateTime(existing.getCreateTime());
            rule.setUpdateTime(LocalDateTime.now(clock));
            if (!repository.update(rule)) {
                throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                    "Failed to update output rule: " + commandKey);
            }
        }
        refresh();
        log.info("Upserted output rule for OpenCLI command {} (arg={}, target={})",
            commandKey, argumentName, targetType);
        return snapshot.entries.get(commandKey);
    }

    public boolean delete(String commandKey) {
        if (commandKey == null) {
            return false;
        }
        ensureLoaded();
        if (!snapshot.entries.containsKey(commandKey)) {
            return false;
        }
        if (!repository.deleteByCommandKey(commandKey)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                "Failed to delete output rule: " + commandKey);
        }
        refresh();
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
            Map<String, HubCommandOutputRule> built = new LinkedHashMap<>();
            for (HubCommandOutputRule rule : repository.listAll()) {
                built.put(rule.getCommandKey(), rule);
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

    private void validateRule(String commandKey, String argumentName,
                              HubCommandOutputTargetType targetType, String fileName) {
        if (commandKey == null || commandKey.isBlank() || !commandKey.contains("/")) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule command key must use site/name form: " + commandKey);
        }
        String[] parts = commandKey.split("/", 2);
        String site = parts[0];
        String name = parts[1];
        if (OpenCliReservedManagementCommands.isReserved(name)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule must not target a management command: " + commandKey);
        }
        if (argumentName == null || argumentName.isBlank()) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND,
                "Output rule argument name must not be blank");
        }
        if (targetType == null) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule target type must not be null");
        }
        if (targetType == HubCommandOutputTargetType.FILE
            && (fileName == null || fileName.isBlank())) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "FILE output rule requires a fileName");
        }
        if (targetType == HubCommandOutputTargetType.DIRECTORY && fileName != null && !fileName.isBlank()) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "DIRECTORY output rule must not specify a fileName");
        }
        if (fileName != null && !fileName.isBlank() && !fileName.matches(SAFE_FILENAME_PATTERN)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule fileName must match " + SAFE_FILENAME_PATTERN + ": " + fileName);
        }
        if (fileName != null && (fileName.equals(".") || fileName.equals(".."))) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule fileName must not be . or ..");
        }

        OpenCliCommand command = catalog.findPublicCommand(site, name)
            .orElseThrow(() -> new OpenCliCommandPolicyException(
                HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID,
                "Output rule references unknown public command: " + commandKey));
        OpenCliCommandArg argument = command.getArgs().stream()
            .filter(a -> argumentName.equals(a.getName()))
            .findFirst()
            .orElseThrow(() -> new OpenCliCommandPolicyException(
                HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND,
                "Output rule references unknown argument: " + argumentName
                    + " on command " + commandKey));
        // Positional arguments are consumed positionally by OpenCLI and cannot be
        // injected as a named managed output, so they are not usable as output args.
        if (argument.isPositional()) {
            throw new OpenCliCommandPolicyException(
                HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND,
                "Output rule references positional argument: " + argumentName
                    + " on command " + commandKey);
        }
        boolean noValueBoolean = OpenCliArgumentType.of(argument.getType())
            == OpenCliArgumentType.BOOLEAN
            && !argument.isValueRequired()
            && !argument.isRequired();
        if (noValueBoolean) {
            throw new OpenCliCommandPolicyException(
                HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND,
                "Output rule references non-value argument: " + argumentName
                    + " on command " + commandKey);
        }
    }

    public List<HubCommandOutputRuleDTO> listAllAsDTO() {
        List<HubCommandOutputRule> all = listAll();
        List<HubCommandOutputRuleDTO> dtos = new ArrayList<>(all.size());
        for (HubCommandOutputRule rule : all) {
            dtos.add(toDTO(rule));
        }
        return Collections.unmodifiableList(dtos);
    }

    public Map<String, HubCommandOutputRule> snapshot() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(snapshot.entries));
    }

    public static HubCommandOutputRuleDTO toDTO(HubCommandOutputRule rule) {
        if (rule == null) {
            return null;
        }
        HubCommandOutputRuleDTO dto = new HubCommandOutputRuleDTO();
        dto.setId(rule.getId());
        dto.setCommandKey(rule.getCommandKey());
        dto.setArgumentName(rule.getArgumentName());
        dto.setTargetType(rule.getTargetType());
        dto.setFileName(rule.getFileName());
        dto.setCreateTime(rule.getCreateTime());
        dto.setUpdateTime(rule.getUpdateTime());
        return dto;
    }

    /**
     * Immutable cache state: the loaded flag plus the complete entry map. Published via a
     * volatile field so readers never observe a partially built map.
     */
    private static final class Snapshot {

        final boolean loaded;
        final Map<String, HubCommandOutputRule> entries;

        Snapshot(boolean loaded, Map<String, HubCommandOutputRule> entries) {
            this.loaded = loaded;
            this.entries = entries;
        }
    }

}
