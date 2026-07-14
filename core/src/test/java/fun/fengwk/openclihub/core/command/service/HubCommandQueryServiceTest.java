package fun.fengwk.openclihub.core.command.service;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;

/**
 * Snapshot coverage for the DTO assembler. Verifies that catalog metadata, blacklist
 * state and output rules are joined correctly so the management UI can render commands
 * in one pass without re-querying each subsystem.
 *
 * @author fengwk
 */
class HubCommandQueryServiceTest {

    private OpenCliCommandCatalog catalog;
    private HubCommandBlacklistService blacklistService;
    private HubCommandOutputRuleService outputRuleService;
    private HubCommandQueryService queryService;

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(OpenCliCommandCatalog.class);
        blacklistService = Mockito.mock(HubCommandBlacklistService.class);
        outputRuleService = Mockito.mock(HubCommandOutputRuleService.class);

        OpenCliCommand bilibili = command("bilibili", "hot");
        OpenCliCommand chatgpt = command("chatgpt", "image");
        when(catalog.listPublicCommands()).thenReturn(List.of(bilibili, chatgpt));

        HubCommandBlacklist blocked = new HubCommandBlacklist();
        blocked.setId("1");
        blocked.setCommandKey("bilibili/hot");
        blocked.setReason("blocked for QA");
        when(blacklistService.snapshot()).thenReturn(Map.of("bilibili/hot", blocked));

        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setId("2");
        rule.setCommandKey("chatgpt/image");
        rule.setArgumentName("op");
        rule.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        when(outputRuleService.snapshot()).thenReturn(Map.of("chatgpt/image", rule));

        queryService = new HubCommandQueryService(catalog, blacklistService, outputRuleService);
    }

    @Test
    void shouldComposePublicCommandsWithCurrentPolicies() {
        List<fun.fengwk.openclihub.share.model.command.HubCommandDTO> dtos = queryService.listPublicCommands();

        assertThat(dtos).hasSize(2);
        var bilibiliDto = dtos.stream()
            .filter(d -> "bilibili/hot".equals(d.getCommandKey())).findFirst().orElseThrow();
        assertThat(bilibiliDto.isBlacklisted()).isTrue();
        assertThat(bilibiliDto.getBlacklistReason()).isEqualTo("blocked for QA");
        assertThat(bilibiliDto.getOutputRule()).isNull();

        var chatgptDto = dtos.stream()
            .filter(d -> "chatgpt/image".equals(d.getCommandKey())).findFirst().orElseThrow();
        assertThat(chatgptDto.isBlacklisted()).isFalse();
        assertThat(chatgptDto.getOutputRule()).isNotNull();
        assertThat(chatgptDto.getOutputRule().getTargetType())
            .isEqualTo(HubCommandOutputTargetType.DIRECTORY);
    }

    @Test
    void shouldFilterByWebsite() {
        List<fun.fengwk.openclihub.share.model.command.HubCommandDTO> dtos =
            queryService.listPublicCommandsForWebsite("chatgpt");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getCommandKey()).isEqualTo("chatgpt/image");
    }

    @Test
    void shouldReturnEmptyListForUnknownWebsite() {
        assertThat(queryService.listPublicCommandsForWebsite("unknown")).isEmpty();
        assertThat(queryService.listPublicCommandsForWebsite(null)).isEmpty();
    }

    private static OpenCliCommand command(String site, String name) {
        OpenCliCommand cmd = new OpenCliCommand();
        cmd.setSite(site);
        cmd.setName(name);
        cmd.setCommandKey(site + "/" + name);
        cmd.setBrowser(true);
        cmd.setAccess(HubCommandAccess.READ);
        OpenCliCommandArg limit = new OpenCliCommandArg();
        limit.setName("limit");
        limit.setType("int");
        cmd.setArgs(new ArrayList<>(List.of(limit)));
        return cmd;
    }

}
