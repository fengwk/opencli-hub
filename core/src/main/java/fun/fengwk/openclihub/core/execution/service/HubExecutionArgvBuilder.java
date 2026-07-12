package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the final Hub-managed argv and validates that callers have not pre-supplied the
 * argument managed by an output rule.
 *
 * <p>Output shape:
 * <pre>
 *   --profile &lt;contextId&gt;
 *   &lt;normalized argv (site + name + args)&gt;
 *   &lt;managed output argument when an output rule applies&gt;
 *   --format json
 * </pre>
 *
 * @author fengwk
 */
@Component
public class HubExecutionArgvBuilder {

    private static final String FORMAT_ARG = "--format";
    private static final String FORMAT_VALUE = "json";

    /**
     * Reject the request when the caller supplied the argument managed by an output rule:
     * the Hub owns that argument and must append it itself, otherwise OpenCLI may receive
     * a duplicate flag and the Output rule's contract is broken.
     */
    public void assertNoCallerOutputArgument(NormalizedOpenCliArgv normalized,
                                             HubCommandOutputRule outputRule) {
        if (outputRule == null || normalized == null) {
            return;
        }
        String managedName = outputRule.getArgumentName();
        if (managedName == null || managedName.isBlank()) {
            return;
        }
        Map<String, List<String>> namedValues = normalized.getNamedValues();
        if (namedValues.containsKey(managedName)) {
            throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED.asThrowable(
                "Caller supplied a Hub-managed output argument: --" + managedName);
        }
    }

    /**
     * Build the complete hub-managed argv. The producer must already have validated that
     * the caller did not pre-supply {@code outputRule.argumentName}.
     */
    public List<String> build(HubInstance instance,
                              NormalizedOpenCliArgv normalized,
                              HubCommandOutputRule outputRule,
                              Path managedOutputRealPath) {
        if (instance == null || instance.getContextId() == null || instance.getContextId().isBlank()) {
            throw HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                "Instance contextId is missing for command assembly");
        }
        if (normalized == null || normalized.getNormalizedArgv() == null
            || normalized.getNormalizedArgv().isEmpty()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable("normalized argv is empty");
        }
        List<String> normalizedArgv = normalized.getNormalizedArgv();
        List<String> command = new ArrayList<>(normalizedArgv.size() + 6);
        command.add("--profile");
        command.add(instance.getContextId());
        command.addAll(normalizedArgv);
        if (outputRule != null) {
            appendManagedOutput(command, outputRule, managedOutputRealPath);
        }
        command.add(FORMAT_ARG);
        command.add(FORMAT_VALUE);
        return command;
    }

    /**
     * Resolve the real filesystem path that backs the output rule. {@code DIRECTORY}
     * resolves to the group root; {@code FILE} resolves via {@link Path#resolve(String)}
     * + {@link Path#normalize()} and validates the result remains under {@code groupDir}
     * so a caller-supplied fileName cannot escape the group.
     */
    public Path resolveManagedOutputPath(HubCommandOutputRule outputRule, Path groupDir) {
        if (outputRule == null) {
            return null;
        }
        if (groupDir == null) {
            throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
                "Output rule referenced without an execution group");
        }
        HubCommandOutputTargetType type = outputRule.getTargetType();
        if (type == null) {
            throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
                "Output rule target type is missing: " + outputRule.getCommandKey());
        }
        if (type == HubCommandOutputTargetType.DIRECTORY) {
            return groupDir.toAbsolutePath().normalize();
        }
        if (type == HubCommandOutputTargetType.FILE) {
            String fileName = outputRule.getFileName();
            if (fileName == null || fileName.isBlank()) {
                throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
                    "FILE output rule requires a fileName: " + outputRule.getCommandKey());
            }
            Path target = groupDir.resolve(fileName).toAbsolutePath().normalize();
            if (!target.startsWith(groupDir.toAbsolutePath().normalize())) {
                throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
                    "FILE output rule escapes the execution group: " + fileName);
            }
            return target;
        }
        throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
            "Unsupported output rule target type: " + type);
    }

    private static void appendManagedOutput(List<String> command,
                                            HubCommandOutputRule rule,
                                            Path realPath) {
        if (realPath == null) {
            throw HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID.asThrowable(
                "Managed output real path is null for: " + rule.getCommandKey());
        }
        command.add("--" + rule.getArgumentName());
        command.add(realPath.toString());
    }

}
