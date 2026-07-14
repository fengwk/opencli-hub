package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HubExecutionArgvBuilder}: final argv shape, caller-managed-argument
 * rejection, and {@code FILE}-rule path-escape protection.
 */
class HubExecutionArgvBuilderTest {

    /**
     * The final argv must start with {@code --profile <contextId>}, include the
     * normalized site/name/args, and end with {@code --format json}. No raw caller argv
     * survives.
     */
    @Test
    void shouldBuildFinalArgvWithProfileFormatAndManagedOutput() {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "hot", "--limit", "5"));
        HubCommandOutputRule rule = rule("bilibili/hot", "output", HubCommandOutputTargetType.FILE, "out.json");

        Path groupDir = Path.of("/data/resources/2026-07-13/execution-1001");
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);

        List<String> argv = builder.build(instance, normalized, rule, managed);

        assertThat(argv).containsExactly(
            "--profile", "ctx-a",
            "bilibili", "hot", "--limit", "5",
            "--output", managed.toString(),
            "--format", "json");
    }

    /**
     * When the caller has already supplied the argument managed by an output rule, the
     * service must refuse with OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED before any work
     * is performed. This is the "managed output argument not double-listed" check the
     * design §22.4 mandates.
     */
    @Test
    void shouldRejectWhenCallerAlreadySuppliesTheManagedOutputArgument() {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubCommandOutputRule rule = rule("bilibili/hot", "output", HubCommandOutputTargetType.FILE, "out.json");
        // Caller argv already contains --output → must refuse.
        Map<String, List<String>> namedValues = new LinkedHashMap<>();
        namedValues.put("output", List.of("/tmp/should-be-rejected.json"));
        NormalizedOpenCliArgv normalized = normalizedWithNamed(List.of("bilibili", "hot"), namedValues);

        assertThatThrownBy(() -> builder.assertNoCallerOutputArgument(normalized, rule))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.getCode()));
    }

    /**
     * {@code DIRECTORY}-mode output rule resolves to the group root, no fileName
     * append.
     */
    @Test
    void shouldResolveDirectoryOutputToGroupRoot() {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.DIRECTORY, null);
        Path groupDir = Path.of("/data/resources/2026-07-13/execution-2001");
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);
        assertThat(managed).isEqualTo(groupDir.toAbsolutePath().normalize());
    }

    /**
     * {@code FILE}-mode output rule joins the group root with the safe fileName and
     * refuses to escape the group via {@code ../}.
     */
    @Test
    void shouldRejectFileRuleThatEscapesTheGroupRoot() {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "../../../etc/passwd");
        Path groupDir = Path.of("/data/resources/2026-07-13/execution-2001");
        assertThatThrownBy(() -> builder.resolveManagedOutputPath(rule, groupDir))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.getCode()));
    }

    /**
     * {@code FILE}-mode output rule normalises to a real path under the group directory
     * using {@link Path#resolve(String)} + {@link Path#normalize()}, never raw string
     * concatenation.
     */
    @Test
    void shouldResolveFileRuleInsideTheGroupDirectory(@TempDir Path tmp) throws IOException {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "report.json");
        Path groupDir = tmp.resolve("execution-3001");
        Files.createDirectories(groupDir);
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);
        assertThat(managed).isEqualTo(groupDir.resolve("report.json").toAbsolutePath().normalize());
    }

    /**
     * A null output rule yields an argv with no managed-output segment.
     */
    @Test
    void shouldOmitManagedOutputWhenNoRuleIsConfigured() {
        HubExecutionArgvBuilder builder = new HubExecutionArgvBuilder();
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("chatgpt", "ask", "hi"));
        List<String> argv = builder.build(instance, normalized, null, null);
        assertThat(argv).containsExactly(
            "--profile", "ctx-a",
            "chatgpt", "ask", "hi",
            "--format", "json");
    }

    private static HubInstance newInstance(String contextId) {
        HubInstance instance = new HubInstance();
        instance.setId("1");
        instance.setCode("a");
        instance.setDisplayName("A");
        instance.setContextId(contextId);
        instance.setState(fun.fengwk.openclihub.share.model.instance.HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili", "chatgpt"));
        instance.setMaxPending(5);
        return instance;
    }

    private static NormalizedOpenCliArgv normalized(List<String> argv) {
        return normalizedWithNamed(argv, new LinkedHashMap<>());
    }

    private static NormalizedOpenCliArgv normalizedWithNamed(List<String> argv, Map<String, List<String>> named) {
        OpenCliCommand command = new OpenCliCommand();
        command.setSite(argv.get(0));
        command.setName(argv.get(1));
        command.setSiteSession(fun.fengwk.openclihub.share.model.execution.SiteSessionMode.EPHEMERAL);
        OpenCliCommandArg dummy = new OpenCliCommandArg();
        dummy.setName("output");
        dummy.setType("string");
        dummy.setRequired(false);
        dummy.setValueRequired(true);
        dummy.setPositional(false);
        command.setArgs(List.of(dummy));
        return new NormalizedOpenCliArgv(command, argv.get(0) + "/" + argv.get(1),
            List.of(), named, argv);
    }

    private static HubCommandOutputRule rule(String commandKey, String argName,
                                             HubCommandOutputTargetType type, String fileName) {
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setCommandKey(commandKey);
        rule.setArgumentName(argName);
        rule.setTargetType(type);
        rule.setFileName(fileName);
        return rule;
    }

}
