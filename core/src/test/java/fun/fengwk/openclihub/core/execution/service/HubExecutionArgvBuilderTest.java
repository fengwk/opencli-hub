package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HubExecutionArgvBuilder}: final argv shape, caller-managed-argument
 * rejection, {@code FILE}-rule path-escape protection, and the final-argv defensive
 * containment check performed by {@link HubLocalPathGuard}.
 */
class HubExecutionArgvBuilderTest {

    @TempDir
    Path tempDir;

    private Path resourceRoot;
    private HubExecutionArgvBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        resourceRoot = tempDir.resolve("resources");
        Files.createDirectories(resourceRoot);
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getResource().setRootDir(resourceRoot.toString());
        properties.getOpencli().setWorkdir(tempDir.resolve("workdir").toString());
        builder = new HubExecutionArgvBuilder(new HubLocalPathGuard(properties));
    }

    /**
     * The final argv must start with {@code --profile <contextId>}, include the
     * normalized site/name/args, and end with {@code --format json}. No raw caller argv
     * survives. The Hub-injected managed output (a not-yet-created file under the
     * existing execution group) passes the final containment defense.
     */
    @Test
    void shouldBuildFinalArgvWithProfileFormatAndManagedOutput() throws IOException {
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "hot", "--limit", "5"));
        HubCommandOutputRule rule = rule("bilibili/hot", "output", HubCommandOutputTargetType.FILE, "out.json");

        Path groupDir = resourceRoot.resolve("2026-07-13").resolve("execution-1001");
        Files.createDirectories(groupDir);
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);

        List<String> argv = builder.build(instance, normalized, rule, managed);

        assertThat(argv).containsExactly(
            "--profile", "ctx-a",
            "bilibili", "hot", "--limit", "5",
            "--output", managed.toString(),
            "--format", "json");
    }

    /**
     * The former per-tab warm TTL flag is no longer injected for browser commands: the
     * instance-level continuous-idle reclaim is now handled by the dispatcher, not through
     * the OpenCLI argv.
     */
    @Test
    void shouldNotInjectWarmTabTtlForBrowserCommand() {
        HubInstance instance = newInstance("ctx-b");
        instance.setWarmTabTtlSeconds(300);
        NormalizedOpenCliArgv normalized = normalizedBrowser(List.of("chatgpt", "ask", "hello"));

        List<String> argv = builder.build(instance, normalized, null, null);

        assertThat(argv).containsExactly(
            "--profile", "ctx-b",
            "chatgpt", "ask", "hello",
            "--format", "json");
        assertThat(argv).doesNotContain("--warm-tab-ttl");
    }

    /**
     * When command is non-browser (browser=false), builder must not inject --warm-tab-ttl.
     */
    @Test
    void shouldNotInjectWarmTabTtlForNonBrowserCommand() {
        HubInstance instance = newInstance("ctx-nb");
        instance.setWarmTabTtlSeconds(300);
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "hot", "--limit", "5"));

        List<String> argv = builder.build(instance, normalized, null, null);

        assertThat(argv).containsExactly(
            "--profile", "ctx-nb",
            "bilibili", "hot", "--limit", "5",
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
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.DIRECTORY, null);
        Path groupDir = resourceRoot.resolve("2026-07-13").resolve("execution-2001");
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);
        assertThat(managed).isEqualTo(groupDir.toAbsolutePath().normalize());
    }

    /**
     * {@code FILE}-mode output rule joins the group root with the safe fileName and
     * refuses to escape the group via {@code ../}.
     */
    @Test
    void shouldRejectFileRuleThatEscapesTheGroupRoot() {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "../../../etc/passwd");
        Path groupDir = resourceRoot.resolve("2026-07-13").resolve("execution-2001");
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
    void shouldResolveFileRuleInsideTheGroupDirectory() throws IOException {
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "report.json");
        Path groupDir = resourceRoot.resolve("2026-07-13").resolve("execution-3001");
        Files.createDirectories(groupDir);
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);
        assertThat(managed).isEqualTo(groupDir.resolve("report.json").toAbsolutePath().normalize());
    }

    /**
     * A null output rule yields an argv with no managed-output segment.
     */
    @Test
    void shouldOmitManagedOutputWhenNoRuleIsConfigured() {
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("chatgpt", "ask", "hi"));
        List<String> argv = builder.build(instance, normalized, null, null);
        assertThat(argv).containsExactly(
            "--profile", "ctx-a",
            "chatgpt", "ask", "hi",
            "--format", "json");
    }

    /**
     * The final defense refuses any absolute path outside the canonical resource root
     * that reaches the assembled argv (simulating a substituted input that escaped
     * earlier stages).
     */
    @Test
    void shouldRejectFinalArgvWithAbsolutePathOutsideRoot() throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "x");
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "submit", outside.toString()));

        assertThatThrownBy(() -> builder.build(instance, normalized, null, null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * The final defense refuses a managed output path that lies outside the canonical
     * resource root even when nothing exists there yet (nearest existing parent is
     * outside the root).
     */
    @Test
    void shouldRejectManagedOutputOutsideResourceRoot() {
        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "hot"));
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.FILE, "out.json");
        Path outsideGroup = tempDir.resolve("outside-group");

        assertThatThrownBy(() -> builder.build(
            instance, normalized, rule, outsideGroup.resolve("out.json")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.getCode()));
    }

    /**
     * A substituted virtual input (an existing real file under the root) together with
     * the managed output passes the final defense and keeps its argv positions.
     */
    @Test
    void shouldAcceptSubstitutedInputAndManagedOutputUnderRoot() throws IOException {
        Path group = resourceRoot.resolve("2026-08-12").resolve("upload-x");
        Files.createDirectories(group);
        Path input = group.resolve("input.txt");
        Files.writeString(input, "data");

        HubInstance instance = newInstance("ctx-a");
        NormalizedOpenCliArgv normalized = normalized(List.of("bilibili", "submit", input.toString()));
        HubCommandOutputRule rule = rule("bilibili/hot", "output",
            HubCommandOutputTargetType.DIRECTORY, null);
        Path outGroup = resourceRoot.resolve("2026-08-12").resolve("execution-4001");
        Files.createDirectories(outGroup);

        List<String> argv = builder.build(instance, normalized, rule, outGroup);

        assertThat(argv).containsExactly(
            "--profile", "ctx-a",
            "bilibili", "submit", input.toString(),
            "--output", outGroup.toString(),
            "--format", "json");
    }

    /**
     * 当命令同时声明 --op（主参数）和 --output（兼容别名）时，有效规则为 --op；
     * 调用方传入任意一个均应被拒绝为 OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED。
     * 该测试覆盖多别名输出参数在自动规则下的全面拒绝行为。
     */
    @Test
    void shouldRejectBothOpAndOutputInDualDeclarationCommand() {
        OpenCliCommandArg opArg = commandArg("op", "string", "Output directory");
        OpenCliCommandArg outputArg = commandArg("output", "string", "输出目录");
        List<OpenCliCommandArg> args = List.of(opArg, outputArg);

        HubCommandOutputRule effectiveRule = rule("douyin/download", "op",
            HubCommandOutputTargetType.DIRECTORY, null);

        // 1. Caller supplies primary output arg --op
        NormalizedOpenCliArgv normalizedOp = commandWithArgs(
            List.of("douyin", "download"), args, Map.of("op", List.of("/tmp/custom-op")));
        assertThatThrownBy(() -> builder.assertNoCallerOutputArgument(normalizedOp, effectiveRule))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.getCode()));

        // 2. Caller supplies legacy output alias --output
        NormalizedOpenCliArgv normalizedOutput = commandWithArgs(
            List.of("douyin", "download"), args, Map.of("output", List.of("/tmp/custom-output")));
        assertThatThrownBy(() -> builder.assertNoCallerOutputArgument(normalizedOutput, effectiveRule))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.getCode()));
    }

    /**
     * 当命令同时声明 --op 和 --output，但 DB 显式配置生效规则为 legacy 的 --output 时，
     * 调用方传入主输出参数 --op 或有效规则 --output 均应被拒绝。
     * 该测试验证显式旧规则场景下，另一个受管主参数同样受到边界保护。
     */
    @Test
    void shouldRejectOpEvenWhenExplicitRuleSpecifiesLegacyOutput() {
        OpenCliCommandArg opArg = commandArg("op", "string", "Output directory");
        OpenCliCommandArg outputArg = commandArg("output", "string", "输出目录");
        List<OpenCliCommandArg> args = List.of(opArg, outputArg);

        HubCommandOutputRule legacyRule = rule("douyin/download", "output",
            HubCommandOutputTargetType.DIRECTORY, null);

        // Caller supplies primary --op, which differs from effective rule argumentName "output"
        NormalizedOpenCliArgv normalizedOp = commandWithArgs(
            List.of("douyin", "download"), args, Map.of("op", List.of("/tmp/custom-op")));
        assertThatThrownBy(() -> builder.assertNoCallerOutputArgument(normalizedOp, legacyRule))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.getCode()));

        // Caller supplies legacy --output, which matches effective rule argumentName
        NormalizedOpenCliArgv normalizedOutput = commandWithArgs(
            List.of("douyin", "download"), args, Map.of("output", List.of("/tmp/custom-output")));
        assertThatThrownBy(() -> builder.assertNoCallerOutputArgument(normalizedOutput, legacyRule))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.getCode()));
    }

    /**
     * 当参数包含业务路径（如排除列表中的 destination/folder path）以及常规业务参数时，
     * 不应被 HubManagedOutputArguments 误判为受管输出，调用方传入时不应被拒绝。
     * 该测试验证不会按名字做全局过度拒绝，保证合法业务参数通行。
     */
    @Test
    void shouldNotRejectBusinessPathAndRegularArguments() {
        OpenCliCommandArg opArg = commandArg("op", "string", "Output directory");
        OpenCliCommandArg pathArg = commandArg("path", "string", "Folder path to list (empty for root)");
        OpenCliCommandArg keywordArg = commandArg("keyword", "string", "Search keyword");
        List<OpenCliCommandArg> args = List.of(opArg, pathArg, keywordArg);

        HubCommandOutputRule rule = rule("cloud/list", "op", HubCommandOutputTargetType.DIRECTORY, null);

        Map<String, List<String>> callerArgs = new LinkedHashMap<>();
        callerArgs.put("path", List.of("/remote/cloud/folder"));
        callerArgs.put("keyword", List.of("test"));
        NormalizedOpenCliArgv normalized = commandWithArgs(List.of("cloud", "list"), args, callerArgs);

        // Does not throw
        builder.assertNoCallerOutputArgument(normalized, rule);
    }

    /**
     * 当命令同时声明 --op 和 --output 时，build 方法只注入生效规则指定的那个参数（此处为 --op），
     * 绝不会注入未生效的另一个兼容别名。
     * 该测试验证单一参数注入 contract 维持现状不变。
     */
    @Test
    void shouldInjectOnlyEffectiveManagedOutputArgInDualDeclaration() throws IOException {
        OpenCliCommandArg opArg = commandArg("op", "string", "Output directory");
        OpenCliCommandArg outputArg = commandArg("output", "string", "输出目录");
        List<OpenCliCommandArg> args = List.of(opArg, outputArg);

        HubInstance instance = newInstance("ctx-douyin");
        NormalizedOpenCliArgv normalized = commandWithArgs(
            List.of("douyin", "download", "--keyword", "food"),
            args,
            Map.of("keyword", List.of("food")));

        HubCommandOutputRule rule = rule("douyin/download", "op",
            HubCommandOutputTargetType.DIRECTORY, null);
        Path groupDir = resourceRoot.resolve("2026-11-02").resolve("execution-9001");
        Files.createDirectories(groupDir);
        Path managed = builder.resolveManagedOutputPath(rule, groupDir);

        List<String> argv = builder.build(instance, normalized, rule, managed);

        assertThat(argv).containsExactly(
            "--profile", "ctx-douyin",
            "douyin", "download", "--keyword", "food",
            "--op", managed.toString(),
            "--format", "json");
        assertThat(argv).doesNotContain("--output");
    }

    private static OpenCliCommandArg commandArg(String name, String type, String help) {
        OpenCliCommandArg arg = new OpenCliCommandArg();
        arg.setName(name);
        arg.setType(type);
        arg.setHelp(help);
        arg.setRequired(false);
        arg.setValueRequired(true);
        arg.setPositional(false);
        return arg;
    }

    private static NormalizedOpenCliArgv commandWithArgs(
        List<String> argv, List<OpenCliCommandArg> args, Map<String, List<String>> named) {
        OpenCliCommand command = new OpenCliCommand();
        command.setCommandKey(argv.get(0) + "/" + argv.get(1));
        command.setSite(argv.get(0));
        command.setName(argv.get(1));
        command.setBrowser(true);
        command.setArgs(args);
        return new NormalizedOpenCliArgv(command, command.getCommandKey(), List.of(), named, argv);
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
        return normalizedWithNamed(argv, new LinkedHashMap<>(), false);
    }

    private static NormalizedOpenCliArgv normalizedBrowser(List<String> argv) {
        return normalizedWithNamed(argv, new LinkedHashMap<>(), true);
    }

    private static NormalizedOpenCliArgv normalizedWithNamed(List<String> argv, Map<String, List<String>> named) {
        return normalizedWithNamed(argv, named, false);
    }

    private static NormalizedOpenCliArgv normalizedWithNamed(List<String> argv, Map<String, List<String>> named, boolean browser) {
        OpenCliCommand command = new OpenCliCommand();
        command.setSite(argv.get(0));
        command.setName(argv.get(1));
        command.setBrowser(browser);
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
