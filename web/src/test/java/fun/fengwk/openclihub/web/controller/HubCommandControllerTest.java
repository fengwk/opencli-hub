package fun.fengwk.openclihub.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.HubCommandQueryService;
import fun.fengwk.openclihub.core.command.service.OpenCliCommandPolicyException;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputRuleDTO;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Covers command catalog filtering and canonical policy mutations.
 */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubCommandController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubCommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HubCommandQueryService queryService;

    @MockitoBean
    private HubCommandBlacklistService blacklistService;

    @MockitoBean
    private HubCommandOutputRuleService outputRuleService;

    private HubCommandDTO command;

    @BeforeEach
    void setUp() {
        command = new HubCommandDTO();
        command.setCommandKey("bilibili/hot");
        command.setSite("bilibili");
        command.setName("hot");
        command.setAliases(List.of("trending"));
        when(queryService.listPublicCommandsForWebsite("bilibili"))
            .thenReturn(List.of(command));
    }

    /** Catalog listing supports both the full set and an optional website filter. */
    @Test
    void shouldListCommands() throws Exception {
        HubCommandOutputRuleDTO outputRule = new HubCommandOutputRuleDTO();
        outputRule.setId("rule-1");
        outputRule.setCommandKey(command.getCommandKey());
        outputRule.setArgumentName("output");
        outputRule.setTargetType(HubCommandOutputTargetType.FILE);
        outputRule.setCreateTime(LocalDateTime.of(2026, 7, 13, 10, 0));
        outputRule.setUpdateTime(LocalDateTime.of(2026, 7, 13, 10, 1));
        command.setOutputRule(outputRule);
        when(queryService.listPublicCommands()).thenReturn(List.of(command));

        mockMvc.perform(get("/api/opencli/commands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].commandKey").value("bilibili/hot"))
            .andExpect(jsonPath("$.data[0].outputRule.createTime").exists())
            .andExpect(jsonPath("$.data[0].outputRule.updateTime").exists());
        mockMvc.perform(get("/api/opencli/commands").param("website", "bilibili"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].site").value("bilibili"));
    }

    /** Alias paths are resolved to the canonical command key before blacklisting. */
    @Test
    void shouldBlacklistCanonicalCommandFromAlias() throws Exception {
        mockMvc.perform(put("/api/opencli/commands/bilibili/trending/blacklist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"disabled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commandKey").value("bilibili/hot"));

        verify(blacklistService).blacklist("bilibili/hot", "disabled");
    }

    /** Blacklist and output-rule delete endpoints remain idempotent at the HTTP layer. */
    @Test
    void shouldDeleteCommandPolicies() throws Exception {
        mockMvc.perform(delete("/api/opencli/commands/bilibili/hot/blacklist"))
            .andExpect(status().isOk());
        // The re-queried DTO after rule deletion carries no outputRule metadata.
        mockMvc.perform(delete("/api/opencli/commands/bilibili/hot/output-rule"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outputRule").doesNotExist());

        verify(blacklistService).unblacklist("bilibili/hot");
        verify(outputRuleService).delete("bilibili/hot");
    }

    /** A valid output rule is passed to core and the re-queried response reflects it. */
    @Test
    void shouldUpsertOutputRule() throws Exception {
        HubCommandDTO updated = new HubCommandDTO();
        updated.setCommandKey("bilibili/hot");
        updated.setSite("bilibili");
        updated.setName("hot");
        HubCommandOutputRuleDTO rule = new HubCommandOutputRuleDTO();
        rule.setId("rule-9");
        rule.setCommandKey("bilibili/hot");
        rule.setArgumentName("output");
        rule.setTargetType(HubCommandOutputTargetType.FILE);
        rule.setFileName("hot.json");
        updated.setOutputRule(rule);
        when(queryService.listPublicCommandsForWebsite("bilibili")).thenReturn(List.of(updated));

        mockMvc.perform(put("/api/opencli/commands/bilibili/hot/output-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"argumentName":"output","targetType":"FILE","fileName":"hot.json"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outputRule.argumentName").value("output"))
            .andExpect(jsonPath("$.data.outputRule.targetType").value("FILE"))
            .andExpect(jsonPath("$.data.outputRule.fileName").value("hot.json"));

        verify(outputRuleService).upsert(
            "bilibili/hot", "output", HubCommandOutputTargetType.FILE, "hot.json");
    }

    /** Core policy validation retains its specific 400 Hub error code. */
    @Test
    void shouldMapCommandPolicyError() throws Exception {
        doThrow(new OpenCliCommandPolicyException(
                HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND, "unknown arg"))
            .when(outputRuleService).upsert(anyString(), anyString(), any(), any());

        mockMvc.perform(put("/api/opencli/commands/bilibili/hot/output-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"argumentName":"output","targetType":"FILE","fileName":"hot.json"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(
                HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND.getCode()));
    }

    /** Bean validation rejects incomplete output rules before core mutation. */
    @Test
    void shouldRejectInvalidOutputRuleBody() throws Exception {
        mockMvc.perform(put("/api/opencli/commands/bilibili/hot/output-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"argumentName\":\"output\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        verify(outputRuleService, never()).upsert(
            anyString(), anyString(), any(), any());
    }

    /** Unknown commands return the stable command-not-found code. */
    @Test
    void shouldRejectUnknownCommand() throws Exception {
        when(queryService.listPublicCommandsForWebsite("missing")).thenReturn(List.of());

        mockMvc.perform(delete("/api/opencli/commands/missing/nope/blacklist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND.getCode()));
    }

}
