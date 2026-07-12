package fun.fengwk.openclihub.core.command.service;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandArgDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Composes {@link HubCommandDTO} snapshots by joining catalog metadata, blacklist state
 * and output rules. Each public command is decorated with its current policy state so the
 * management UI can render everything in one pass.
 *
 * <p>The composition is intentionally read-only; mutators live in the blacklist and
 * output rule services respectively.
 *
 * @author fengwk
 */
public class HubCommandQueryService {

    private final OpenCliCommandCatalog catalog;
    private final HubCommandBlacklistService blacklistService;
    private final HubCommandOutputRuleService outputRuleService;

    public HubCommandQueryService(OpenCliCommandCatalog catalog,
                                  HubCommandBlacklistService blacklistService,
                                  HubCommandOutputRuleService outputRuleService) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        if (blacklistService == null) {
            throw new IllegalArgumentException("blacklistService must not be null");
        }
        if (outputRuleService == null) {
            throw new IllegalArgumentException("outputRuleService must not be null");
        }
        this.catalog = catalog;
        this.blacklistService = blacklistService;
        this.outputRuleService = outputRuleService;
    }

    /**
     * Returns every public browser command decorated with its current policy state.
     */
    public List<HubCommandDTO> listPublicCommands() {
        Map<String, HubCommandBlacklist> blacklist = blacklistService.snapshot();
        Map<String, HubCommandOutputRule> rules = outputRuleService.snapshot();
        List<HubCommandDTO> result = new ArrayList<>();
        for (OpenCliCommand command : catalog.listPublicCommands()) {
            result.add(toDTO(command, blacklist.get(command.getCommandKey()),
                rules.get(command.getCommandKey())));
        }
        return Collections.unmodifiableList(result);
    }

    public List<HubCommandDTO> listPublicCommandsForWebsite(String website) {
        if (website == null) {
            return Collections.emptyList();
        }
        Map<String, HubCommandBlacklist> blacklist = blacklistService.snapshot();
        Map<String, HubCommandOutputRule> rules = outputRuleService.snapshot();
        List<HubCommandDTO> result = new ArrayList<>();
        for (OpenCliCommand command : catalog.listPublicCommands()) {
            if (website.equals(command.getSite())) {
                result.add(toDTO(command, blacklist.get(command.getCommandKey()),
                    rules.get(command.getCommandKey())));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public HubCommandDTO toDTO(OpenCliCommand command,
                               HubCommandBlacklist blacklistEntry,
                               HubCommandOutputRule ruleEntry) {
        if (command == null) {
            return null;
        }
        HubCommandDTO dto = new HubCommandDTO();
        dto.setCommandKey(command.getCommandKey());
        dto.setSite(command.getSite());
        dto.setName(command.getName());
        dto.setAliases(command.getAliases());
        dto.setDescription(command.getDescription());
        dto.setAccess(command.getAccess());
        dto.setBrowser(command.isBrowser());
        dto.setSiteSession(command.getSiteSession());
        dto.setDefaultWindowMode(command.getDefaultWindowMode());
        dto.setArgs(toArgDTOs(command));
        dto.setBlacklisted(blacklistEntry != null);
        dto.setBlacklistReason(blacklistEntry == null ? null : blacklistEntry.getReason());
        dto.setOutputRule(HubCommandOutputRuleService.toDTO(ruleEntry));
        return dto;
    }

    private static List<HubCommandArgDTO> toArgDTOs(OpenCliCommand command) {
        List<HubCommandArgDTO> result = new ArrayList<>();
        for (var arg : command.getArgs()) {
            HubCommandArgDTO dto = new HubCommandArgDTO();
            dto.setName(arg.getName());
            dto.setType(arg.getType());
            dto.setRequired(arg.isRequired());
            dto.setValueRequired(arg.isValueRequired());
            dto.setPositional(arg.isPositional());
            dto.setChoices(arg.getChoices());
            dto.setDefaultValue(arg.getDefaultValue());
            dto.setHelp(arg.getHelp());
            result.add(dto);
        }
        return result;
    }

}
