package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.HubCommandQueryService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandBlacklistUpdateDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputRuleUpdateDTO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public command catalog and administrator-managed command policy API.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RequestMapping("/api/opencli/commands")
@RestController
public class HubCommandController {

    private final HubCommandQueryService queryService;
    private final HubCommandBlacklistService blacklistService;
    private final HubCommandOutputRuleService outputRuleService;

    @GetMapping
    public Result<List<HubCommandDTO>> list(
        @RequestParam(required = false) String website) {
        return Results.ok(website == null || website.isBlank()
            ? queryService.listPublicCommands()
            : queryService.listPublicCommandsForWebsite(website.trim()));
    }

    @PutMapping("/{site}/{command}/blacklist")
    public Result<HubCommandDTO> blacklist(
        @PathVariable String site,
        @PathVariable String command,
        @Valid @RequestBody(required = false) HubCommandBlacklistUpdateDTO request) {
        HubCommandDTO target = findCommand(site, command);
        blacklistService.blacklist(target.getCommandKey(), request == null ? null : request.getReason());
        return Results.ok(findCommand(site, command));
    }

    @DeleteMapping("/{site}/{command}/blacklist")
    public Result<HubCommandDTO> unblacklist(
        @PathVariable String site,
        @PathVariable String command) {
        HubCommandDTO target = findCommand(site, command);
        blacklistService.unblacklist(target.getCommandKey());
        return Results.ok(findCommand(site, command));
    }

    @PutMapping("/{site}/{command}/output-rule")
    public Result<HubCommandDTO> upsertOutputRule(
        @PathVariable String site,
        @PathVariable String command,
        @Valid @RequestBody HubCommandOutputRuleUpdateDTO request) {
        HubCommandDTO target = findCommand(site, command);
        outputRuleService.upsert(
            target.getCommandKey(),
            request.getArgumentName(),
            request.getTargetType(),
            request.getFileName());
        return Results.ok(findCommand(site, command));
    }

    @DeleteMapping("/{site}/{command}/output-rule")
    public Result<HubCommandDTO> deleteOutputRule(
        @PathVariable String site,
        @PathVariable String command) {
        HubCommandDTO target = findCommand(site, command);
        outputRuleService.delete(target.getCommandKey());
        return Results.ok(findCommand(site, command));
    }

    private HubCommandDTO findCommand(String site, String nameOrAlias) {
        String normalizedSite = site == null ? null : site.trim();
        String normalizedName = nameOrAlias == null ? null : nameOrAlias.trim();
        return queryService.listPublicCommandsForWebsite(normalizedSite).stream()
            .filter(command -> normalizedName != null
                && (normalizedName.equals(command.getName())
                    || command.getAliases() != null
                        && command.getAliases().contains(normalizedName)))
            .findFirst()
            .orElseThrow(() -> HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND.asThrowable(
                "command not found: " + normalizedSite + "/" + normalizedName));
    }

}
