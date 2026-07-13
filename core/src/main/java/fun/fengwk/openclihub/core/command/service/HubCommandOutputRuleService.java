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
 * In-memory cached output rule service.
 *
 * <p>The cache is a {@link ConcurrentHashMap} keyed by canonical command key. Loaded
 * state is tracked explicitly via {@link #loaded} because a legitimately empty database
 * result and a not-yet-loaded cache both produce an empty map; treating the map's
 * emptiness as the "unloaded" signal would force a redundant {@code listAll()} on every
 * call against an empty table.
 *
 * <p>The service also acts as the validator for rule changes: when an admin saves or
 * replaces a rule, the service checks the catalog to confirm the command exists as a
 * public browser command and that the named argument accepts a value.
 *
 * @author fengwk
 */
@Slf4j
public class HubCommandOutputRuleService {

    private static final String SAFE_FILENAME_PATTERN = "[A-Za-z0-9._-]+";

    private final HubCommandOutputRuleRepository repository;
    private final OpenCliCommandCatalog catalog;
    private final ConcurrentMap<String, HubCommandOutputRule> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public HubCommandOutputRuleService(HubCommandOutputRuleRepository repository,
                                       OpenCliCommandCatalog catalog) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        this.repository = repository;
        this.catalog = catalog;
    }

    public Optional<HubCommandOutputRule> findByCommandKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(cache.get(commandKey));
    }

    public List<HubCommandOutputRule> listAll() {
        ensureLoaded();
        return new ArrayList<>(cache.values());
    }

    /**
     * Insert or replace the rule for the canonical command key.
     */
    public HubCommandOutputRule upsert(String commandKey, String argumentName,
                                       HubCommandOutputTargetType targetType, String fileName) {
        validateRule(commandKey, argumentName, targetType, fileName);
        ensureLoaded();
        HubCommandOutputRule existing = cache.get(commandKey);
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setCommandKey(commandKey);
        rule.setArgumentName(argumentName);
        rule.setTargetType(targetType);
        rule.setFileName(fileName);
        if (existing == null) {
            rule.setId(repository.generateId());
            if (!repository.add(rule)) {
                throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                    "Failed to persist output rule: " + commandKey);
            }
        } else {
            rule.setId(existing.getId());
            rule.setCreateTime(existing.getCreateTime());
            if (!repository.update(rule)) {
                throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                    "Failed to update output rule: " + commandKey);
            }
        }
        refresh();
        log.info("Upserted output rule for OpenCLI command {} (arg={}, target={})",
            commandKey, argumentName, targetType);
        return cache.get(commandKey);
    }

    public boolean delete(String commandKey) {
        if (commandKey == null) {
            return false;
        }
        ensureLoaded();
        if (!cache.containsKey(commandKey)) {
            return false;
        }
        if (!repository.deleteByCommandKey(commandKey)) {
            throw new OpenCliCommandPolicyException(HubErrorCodes.EXECUTION_PERSIST_FAILED,
                "Failed to delete output rule: " + commandKey);
        }
        refresh();
        return true;
    }

    public synchronized void refresh() {
        cache.clear();
        for (HubCommandOutputRule rule : repository.listAll()) {
            cache.put(rule.getCommandKey(), rule);
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
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
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

}
